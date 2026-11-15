/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.logger.YLog
import java.util.WeakHashMap

/**
 * 视图可见性追踪器
 *
 * 通过 Hook [View.setFlags] 方法拦截视图可见性变更，
 * 支持自定义可见性标记（绕过业务逻辑强制显示/隐藏），
 * 并记录被追踪视图的原始可见性状态以便恢复。
 *
 * 采用 View 弱引用与身份标识优先索引，防止跨屏幕或重建根视图时按 ID 发生冲突，
 * 并在视图卸载/销毁时清理记录。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object ViewVisibilityTracker {
    private const val TAG = "ViewVisibilityTracker"

    /** 用于标记被追踪视图的 Tag ID */
    const val TRACKING_TAG_ID: Int = 0x7F137666

    /** 自定义可见性标记：强制可见，用于绕过业务逻辑 */
    const val CUSTOM_VISIBLE: Int = 114514

    /** 自定义可见性标记：强制隐藏，用于绕过业务逻辑 */
    const val CUSTOM_GONE: Int = 1919810

    /** View 标志位中可见性相关位的掩码 (0x0000000C) */
    private const val VISIBILITY_FLAG_MASK = 0x0000000C

    /** 视图弱引用可见性缓存，依据 View 弱引用身份进行索引，消除跨屏幕及重建时的 ID 碰撞与内存泄漏 */
    private val originalVisibilityByView = WeakHashMap<View, Int>()

    /** 被本模块当前显式隐藏的视图弱引用集合，用于判定是否由本模块占有隐藏状态 */
    private val moduleHiddenViews = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * 标记当前线程是否正在将 CUSTOM_GONE / CUSTOM_VISIBLE 向底层 proceed 转换。
     * 若在 proceed 过程中发生底层 Hook 重入，立即放行且不作为系统可见性变化处理。
     */
    private val isTranslatingModuleVisibility = ThreadLocal.withInitial { false }

    fun setModuleHidden(view: View, hidden: Boolean) {
        synchronized(this) {
            if (hidden) {
                moduleHiddenViews.add(view)
            } else {
                moduleHiddenViews.remove(view)
            }
        }
    }

    fun isModuleHidden(view: View): Boolean = synchronized(this) {
        moduleHiddenViews.contains(view)
    }

    /**
     * 更新视图的原始/基线可见性记录
     * 当系统在模块隐藏期间变更视图可见性时，将新状态作为模块恢复时的目标，
     * 避免盲目恢复为旧的 VISIBLE 覆盖系统的锁屏、全屏或状态栏禁用状态。
     */
    fun updateOriginalVisibility(view: View, visibility: Int) {
        if (isComposeView(view)) return
        if (visibility == CUSTOM_GONE || visibility == CUSTOM_VISIBLE) return
        val norm = visibility and VISIBILITY_FLAG_MASK
        synchronized(this) {
            val oldBaseline = originalVisibilityByView[view]
            if (oldBaseline != norm) {
                originalVisibilityByView[view] = norm
                val resName = runCatching { io.github.proify.lyricon.common.util.ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
                YLog.info(
                    TAG,
                    "setFlags hook: baseline changed: view=$resName, flags=$visibility, oldBaseline=$oldBaseline, newBaseline=$norm"
                )
            }
        }
    }

    /**
     * 处理系统主动触发的视图可见性变更（支持从 Hook 或直接调用触发）
     */
    fun onSystemVisibilityChanged(view: View, visibility: Int) {
        if (isComposeView(view)) return
        if (visibility == CUSTOM_GONE || visibility == CUSTOM_VISIBLE) return
        val norm = visibility and VISIBILITY_FLAG_MASK
        synchronized(this) {
            val oldBaseline = originalVisibilityByView[view]
            if (isModuleHidden(view)) {
                // 当处于模块隐藏状态时：
                // 若接收到 View.GONE，由于本模块刚将视图置为 GONE，该调用属于本模块隐藏的回声或同态刷新，
                // 严禁覆盖已记录的 pre-hide 基线（如 VISIBLE 或 INVISIBLE）！
                // 并且在模块隐藏期间，即使先前未记录基线，亦绝不能记录 GONE 作为恢复基线。
                if (norm == View.GONE) {
                    return
                }
                // 仅当系统显式变更非 GONE 状态（如全屏/锁屏设为 INVISIBLE 或系统设为 VISIBLE）时才更新基线
                if (oldBaseline != norm) {
                    originalVisibilityByView[view] = norm
                    val resName = runCatching { io.github.proify.lyricon.common.util.ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
                    YLog.info(
                        TAG,
                        "setFlags hook: baseline changed: view=$resName, flags=$visibility, oldBaseline=$oldBaseline, newBaseline=$norm"
                    )
                }
            } else if (!originalVisibilityByView.containsKey(view)) {
                originalVisibilityByView[view] = norm
                val resName = runCatching { io.github.proify.lyricon.common.util.ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
                YLog.info(
                    TAG,
                    "setFlags hook: baseline changed: view=$resName, flags=$visibility, oldBaseline=$oldBaseline, newBaseline=$norm"
                )
            }
        }
    }

    /** 当前 Hook 句柄，用于反注册旧 Hook 防止重复注入 */
    private var unhookHandle: XposedInterface.HookHandle? = null

    /**
     * 获取当前被追踪的视图数量（主要用于测试与诊断）
     */
    val trackedViewCount: Int
        get() = synchronized(this) { originalVisibilityByView.size }

    /**
     * 判断指定视图是否处于追踪中
     */
    fun isTracking(view: View): Boolean =
        synchronized(this) { originalVisibilityByView.containsKey(view) }

    /** Only a Compose-backed View itself is exempt; traditional descendants still honor visibility. */
    fun isComposeView(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("ComposeView") || name.contains("androidx.compose")
    }

    /**
     * 初始化 Hook，拦截 [View.setFlags] 方法
     */
    fun initialize(module: XposedModule, classLoader: ClassLoader) {
        try {
            unhookHandle?.unhook()

            @SuppressLint("SoonBlockedPrivateApi")
            val setFlagsMethod = classLoader.loadClass(View::class.java.name)
                .getDeclaredMethod(
                    "setFlags",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )

            @Suppress("ObjectLiteralToLambda")
            unhookHandle =
                module.hook(setFlagsMethod)
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (isTranslatingModuleVisibility.get() == true) {
                                return chain.proceed()
                            }
                            val flags = try {
                                (chain.args.getOrNull(0) as? Int) ?: 0
                            } catch (_: Throwable) {
                                0
                            }
                            val isCustom = (flags == CUSTOM_GONE || flags == CUSTOM_VISIBLE)

                            val newArgs: Array<Any>? = try {
                                val view = chain.thisObject as? View
                                if (view != null) {
                                    handleSetFlags(view, chain)
                                } else {
                                    null
                                }
                            } catch (t: Throwable) {
                                YLog.error(TAG, "Error in setFlags interceptor logic", t)
                                null
                            }

                            return if (isCustom) {
                                isTranslatingModuleVisibility.set(true)
                                try {
                                    if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                                } finally {
                                    isTranslatingModuleVisibility.set(false)
                                }
                            } else {
                                if (newArgs != null) {
                                    chain.proceed(newArgs)
                                } else {
                                    chain.proceed()
                                }
                            }
                        }
                    })
            YLog.info(TAG, "Successfully hooked View.setFlags $unhookHandle")
        } catch (t: Throwable) {
            YLog.error(TAG, "Failed to initialize hook", t)
        }
    }

    /**
     * 处理 [View.setFlags] 的参数
     */
    @androidx.annotation.VisibleForTesting
    internal fun handleSetFlags(view: View, param: XposedInterface.Chain): Array<Any>? {
        if (isTranslatingModuleVisibility.get() == true) {
            return null
        }
        val flags = param.args[0] as Int
        val mask = param.args[1] as Int

        // 仅处理可见性掩码变更
        if (mask != VISIBILITY_FLAG_MASK) return null

        var newArgs: Array<Any>? = null
        when (flags) {
            CUSTOM_GONE -> {
                if (view.visibility != CUSTOM_GONE && view.visibility != CUSTOM_VISIBLE) {
                    saveOriginalVisibilityIfNeeded(view, view.visibility)
                }
                setModuleHidden(view, true)
                newArgs = arrayOf(View.GONE, mask)
            }

            CUSTOM_VISIBLE -> {
                if (view.visibility != CUSTOM_GONE && view.visibility != CUSTOM_VISIBLE) {
                    saveOriginalVisibilityIfNeeded(view, view.visibility)
                }
                setModuleHidden(view, false)
                newArgs = arrayOf(View.VISIBLE, mask)
            }

            else -> {
                val systemVisibility = flags and VISIBILITY_FLAG_MASK
                if (isModuleHidden(view)) {
                    // 系统在模块隐藏期间变更了可见性（例如进入锁屏或全屏主动 GONE/INVISIBLE，或者时钟走字 / 通知变更 VISIBLE）
                    // 记录此状态以便后续恢复时不覆盖系统变更
                    onSystemVisibilityChanged(view, systemVisibility)
                    if (systemVisibility == View.VISIBLE || systemVisibility == View.INVISIBLE) {
                        // 系统要求可见或半可见，但由于本模块歌词仍在播放，屏幕上保持 GONE 占位效果
                        newArgs = arrayOf(View.GONE, mask)
                    }
                } else if (view.getTag(TRACKING_TAG_ID) != null) {
                    saveOriginalVisibilityIfNeeded(view, systemVisibility)
                }
            }
        }
        return newArgs
    }

    /**
     * 保存视图原始可见性
     */
    fun saveOriginalVisibilityIfNeeded(view: View, currentVisibility: Int) {
        if (isComposeView(view)) {
            // 不对 Compose 树应用可见性缓存
            return
        }
        if (currentVisibility == CUSTOM_GONE || currentVisibility == CUSTOM_VISIBLE) {
            return
        }
        val norm = currentVisibility and VISIBILITY_FLAG_MASK
        synchronized(this) {
            if (!originalVisibilityByView.containsKey(view)) {
                val oldBaseline = originalVisibilityByView[view]
                originalVisibilityByView[view] = norm
                val resName = runCatching { io.github.proify.lyricon.common.util.ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
                YLog.info(
                    TAG,
                    "setFlags hook: baseline saved: view=$resName, flags=$currentVisibility, oldBaseline=$oldBaseline, newBaseline=$norm"
                )
            }
        }
    }

    /**
     * 获取指定 View 对象的原始可见性（优先按 View 身份弱引用查找）
     */
    fun getOriginalVisibility(view: View, defaultValue: Int = -1): Int {
        if (isComposeView(view)) return defaultValue
        synchronized(this) {
            originalVisibilityByView[view]?.let { return it }
            val viewId = view.id
            if (viewId != View.NO_ID) {
                return getOriginalVisibility(viewId, defaultValue)
            }
            return defaultValue
        }
    }

    /**
     * 获取视图被篡改前的原始可见性 (按 View ID 兼容查询)
     * 仅在当前存在唯一活跃实例时返回，消除 ID 碰撞与死对象陈旧数据返回
     */
    fun getOriginalVisibility(viewId: Int, defaultValue: Int = -1): Int {
        if (viewId == View.NO_ID) return defaultValue
        synchronized(this) {
            val matching = originalVisibilityByView.entries.filter { it.key.id == viewId }
            return when (matching.size) {
                0 -> defaultValue
                1 -> matching.first().value
                else -> {
                    YLog.warning(
                        TAG,
                        "Colliding view ID $viewId detected among ${matching.size} live views in ID-only lookup; returning defaultValue"
                    )
                    defaultValue
                }
            }
        }
    }

    /**
     * 移除指定视图对象的追踪记录
     */
    fun clearTracking(view: View) {
        synchronized(this) {
            originalVisibilityByView.remove(view)
            moduleHiddenViews.remove(view)
        }
    }

    /**
     * 移除指定视图 ID 的追踪记录
     */
    fun clearTracking(viewId: Int) {
        if (viewId == View.NO_ID) return
        synchronized(this) {
            val toRemove = originalVisibilityByView.keys.filter { it.id == viewId }
            toRemove.forEach {
                originalVisibilityByView.remove(it)
                moduleHiddenViews.remove(it)
            }
        }
    }

    /** 清空所有可见性追踪记录 */
    fun clearAllTracking() {
        synchronized(this) {
            originalVisibilityByView.clear()
            moduleHiddenViews.clear()
        }
    }
}
