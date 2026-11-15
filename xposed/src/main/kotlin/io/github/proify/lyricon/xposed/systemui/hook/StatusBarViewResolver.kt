/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.core.view.doOnAttach
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.logger.YLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏视图解析工具类 (StatusBarViewResolver)
 * 职责：通过 Hook 拦截系统资源加载过程或稳定容器回退发现，捕获状态栏根视图 (Root View) 并分发给订阅者。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object StatusBarViewResolver {

    const val TEST_PARENT_TAG_KEY = 0x7f0a9999
    const val TEST_CONTEXT_TAG_KEY = 0x7f0a9998
    val testParentMap = java.util.concurrent.ConcurrentHashMap<View, ViewParent>()
    private const val TAG = "StatusBarViewResolver"

    /**
     * 状态栏视图获取成功的回调定义
     */
    typealias OnViewResolvedListener = (statusBarRoot: ViewGroup) -> Unit

    private val registry = CopyOnWriteArrayList<OnViewResolvedListener>()

    @Volatile
    var isInitialized = false
        private set

    @Volatile
    var isInflateHookInstalled = false
        private set

    @Volatile
    var isFallbackHookInstalled = false
        private set

    /**
     * 订阅状态栏视图。
     * @param listener 接收 ViewGroup 的回调函数
     */
    fun subscribe(listener: OnViewResolvedListener) {
        if (!registry.contains(listener)) {
            registry.add(listener)
        }
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(listener: OnViewResolvedListener) {
        registry.remove(listener)
    }

    /**
     * 启动拦截任务
     * @param module XposedModule 实例
     * @param context 系统上下文（建议使用 SystemUI 的 Context）
     * @param classLoader 对应的 ClassLoader
     */
    @SuppressLint("DiscouragedApi")
    fun init(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader = context.classLoader
    ) {
        if (isInitialized) return

        val targetLayoutId = context.resources.getIdentifier(
            "status_bar",
            "layout",
            context.packageName
        )

        if (targetLayoutId == 0) {
            YLog.error(TAG, "Failed to resolve 'status_bar' layout ID; attempting fallback hook")
        } else {
            try {
                val inflaterClass = classLoader.loadClass("android.view.LayoutInflater")
                val inflateMethod = inflaterClass.getDeclaredMethod(
                    "inflate",
                    Int::class.javaPrimitiveType,
                    ViewGroup::class.java,
                    Boolean::class.javaPrimitiveType
                )

                @Suppress("ObjectLiteralToLambda")
                module.hook(inflateMethod).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val currentLayoutId = chain.args[0] as Int

                        if (currentLayoutId == targetLayoutId) {
                            val viewGroup = result as? ViewGroup
                            viewGroup?.let { root ->
                                publish(root)
                            }
                        }
                        return result
                    }
                })
                isInflateHookInstalled = true
                YLog.info(TAG, "Successfully installed LayoutInflater.inflate hook for status_bar ($targetLayoutId)")
            } catch (t: Throwable) {
                YLog.error(TAG, "Failed to hook LayoutInflater.inflate for status_bar", t)
            }
        }

        installFallbackHookIfNeeded(module, context, classLoader)

        isInitialized = isInflateHookInstalled || isFallbackHookInstalled
        if (!isInitialized) {
            YLog.error(TAG, "Resolver initialization failed: neither primary inflate hook nor fallback hook could be installed")
        }
    }

    /**
     * 安装回退发现 Hook：通过拦截 ViewGroup.addView 寻找符合 PhoneStatusBarView 且属于 status_bar_container 的视图
     */
    private fun installFallbackHookIfNeeded(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader
    ) {
        try {
            val viewGroupClass = classLoader.loadClass("android.view.ViewGroup")
            val layoutParamsClass = try {
                classLoader.loadClass("android.view.ViewGroup\$LayoutParams")
            } catch (_: Throwable) {
                ViewGroup.LayoutParams::class.java
            }
            val addViewMethod = viewGroupClass.getDeclaredMethod(
                "addView",
                View::class.java,
                Int::class.javaPrimitiveType,
                layoutParamsClass
            )

            @Suppress("ObjectLiteralToLambda")
            module.hook(addViewMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val child = chain.args[0] as? View
                    if (child is ViewGroup && isMatchingStatusBarHost(child, context)) {
                        publish(child)
                    }
                    return result
                }
            })
            isFallbackHookInstalled = true
            YLog.info(TAG, "Successfully installed fallback addView hook for PhoneStatusBarView discovery")
        } catch (t: Throwable) {
            YLog.warning(TAG, "Failed to install fallback addView hook: ${t.message}")
        }
    }

    /**
     * 校验候选视图是否为合法的状态栏 Host：
     * 必须同时满足：
     * 1. 自身身份：PhoneStatusBarView 类型或 R.id.status_bar 资源 ID
     * 2. 祖先归属：位于 StatusBarWindowView 或 R.id.status_bar_container 容器内
     */
    fun isMatchingStatusBarHost(view: View, context: Context): Boolean {
        if (view !is ViewGroup) return false

        val statusBarId = runCatching {
            context.resources.getIdentifier("status_bar", "id", context.packageName)
        }.getOrDefault(0)

        val isSelfStatusBar = (statusBarId != 0 && view.id == statusBarId) ||
                view.javaClass.simpleName == "PhoneStatusBarView" ||
                view.javaClass.name == "com.android.systemui.statusbar.phone.PhoneStatusBarView"

        if (!isSelfStatusBar) return false

        // 祖先容器校验
        val containerId = runCatching {
            context.resources.getIdentifier("status_bar_container", "id", context.packageName)
        }.getOrDefault(0)

        var cur: Any? = testParentMap[view] ?: (view.getTag(TEST_PARENT_TAG_KEY) as? ViewParent) ?: view.parent
        while (cur is View) {
            val isContainer = (containerId != 0 && cur.id == containerId) ||
                    cur.javaClass.simpleName == "StatusBarWindowView" ||
                    cur.javaClass.name == "com.android.systemui.statusbar.window.StatusBarWindowView"
            if (isContainer) {
                return true
            }
            cur = testParentMap[cur] ?: (cur.getTag(TEST_PARENT_TAG_KEY) as? ViewParent) ?: cur.parent
        }
        return false
    }

    /**
     * 发布视图：确保视图 attached/usable 时才通知订阅者
     */
    fun publish(view: ViewGroup) {
        if (view.isAttachedToWindow) {
            notifyAll(view)
        } else {
            view.doOnAttach {
                notifyAll(view)
            }
        }
    }

    /**
     * 广播分发，各个订阅者异常相互隔离
     */
    private fun notifyAll(view: ViewGroup) {
        registry.forEach { listener ->
            try {
                listener.invoke(view)
            } catch (t: Throwable) {
                YLog.error(TAG, "Error notifying OnViewResolvedListener", t)
            }
        }
    }

    fun resetForTesting() {
        testParentMap.clear()
        registry.clear()
        isInitialized = false
        isInflateHookInstalled = false
        isFallbackHookInstalled = false
    }
}
