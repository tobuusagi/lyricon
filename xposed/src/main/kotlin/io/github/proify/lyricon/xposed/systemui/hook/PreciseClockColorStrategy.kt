/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 精准策略(默认):按时钟类动态 hook 与暗色图标分发器协同取色
 *
 * 从时钟视图实例动态获取实际类名(`view.javaClass` —— AOSP `Clock`、MIUI/HyperOS `MiuiTextClock`、
 * ColorOS `OplusTextClock` 等),沿类继承树向上检查颜色相关方法(`onDarkChanged`/
 * `onDarkChangedWithContrast`/`setTextColor`/`setTint` 等)。
 * 同时挂接全局 `DarkIconDispatcherImpl` 信号通道，直接捕获暗色强度（darkIntensity）与高对比度图标色值，
 * 解决类方法未就绪、未复写或纯 Compose 无传统时钟 View 时的取色断流。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
class PreciseClockColorStrategy : StatusColorStrategy {

    companion object {
        private const val TAG = "PreciseClockStrategy"

        /** 颜色相关方法名关键词(按类继承树方法名匹配) */
        private val COLOR_KEYWORDS = arrayOf("color", "tint", "dark")

        /** 框架 TextView 本体 — 若时钟已到达纯 TextView,避免全局拦截 */
        private const val FRAMEWORK_TEXT_VIEW = "android.widget.TextView"

        /** 供测试或宿主注入生产等价的 DarkIconDispatcher 实例提供者 */
        @Volatile
        var injectedDispatcherProvider: (() -> Any?)? = null
    }

    override val id: Int = BasicStyle.STATUS_COLOR_STRATEGY_PRECISE
    override val name: String = "Precise"

    private var module: XposedModule? = null
    private var classLoader: ClassLoader? = null
    private var emit: ((Int) -> Unit)? = null

    /** 时钟视图的弱引用(用于初始/复核读取) */
    private var clockView: WeakReference<View>? = null

    /** 已按类 hook 过的类名集合(防止重复 hook) */
    private val hookedClasses = HashSet<String>()

    /** 时钟类 Hook 句柄列表，注销时统一清理 */
    private val clockHookHandles = mutableListOf<XposedInterface.HookHandle>()

    /** 全局 DarkIconDispatcher 句柄列表，注销时统一清理 */
    private val dispatcherHookHandles = mutableListOf<XposedInterface.HookHandle>()

    /** DarkIconDispatcher 实例弱引用 */
    private var dispatcherInstance: WeakReference<Any>? = null

    override fun onActivate(
        module: XposedModule,
        classLoader: ClassLoader,
        emit: (Int) -> Unit
    ) {
        this.module = module
        this.classLoader = classLoader
        this.emit = emit

        hookDarkIconDispatcher(classLoader)

        // 若视图早已绑定(策略切换场景),补装该类 hook
        clockView?.get()?.let { hookClockClass(it.javaClass) }

        // 主动尝试发现并绑定当前暗色图标分发器，立即尝试初始上报
        val dispatcher = getLiveDarkIconDispatcher(classLoader)
        if (dispatcher != null) {
            reportDispatcherColor(dispatcher)
        }
    }

    override fun onDeactivate() {
        synchronized(dispatcherHookHandles) {
            dispatcherHookHandles.forEach { runCatching { it.unhook() } }
            dispatcherHookHandles.clear()
        }
        synchronized(clockHookHandles) {
            clockHookHandles.forEach { runCatching { it.unhook() } }
            clockHookHandles.clear()
        }
        synchronized(hookedClasses) {
            hookedClasses.clear()
        }
        dispatcherInstance = null
        clockView = null
        module = null
        classLoader = null
        emit = null
    }

    override fun onBindStatusBar(root: ViewGroup?) = Unit

    override fun onBindClockView(view: View?) {
        clockView = view?.let { WeakReference(it) }
        if (view != null) {
            hookClockClass(view.javaClass)
        }
        reportCurrentColor()
    }

    override fun onRefresh() {
        reportCurrentColor()
    }

    private fun emitColor(color: Int, intensity: Float = -1f) {
        if (this.module == null || this.emit == null) return
        val safeColor = if (color != 0 && (color ushr 24) != 0) {
            color
        } else {
            val effIntensity = if (intensity in 0f..1f) intensity else 0f
            if (effIntensity > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        StatusBarColorMonitor.emit(safeColor, intensity)
    }

    /**
     * 获取宿主当前活跃的 DarkIconDispatcher 实例
     * 1. 优先使用缓存的已捕获实例
     * 2. 反射获取 com.android.systemui.Dependency.sDependency 并查询 DarkIconDispatcher
     * 3. 回退检查 injectedDispatcherProvider (用于测试或扩展注入)
     */
    fun getLiveDarkIconDispatcher(classLoader: ClassLoader): Any? {
        dispatcherInstance?.get()?.let { return it }

        // 1. 通过 SystemUI 核心依赖注入容器 com.android.systemui.Dependency 反射获取
        try {
            val dependencyClass = runCatching {
                classLoader.loadClass("com.android.systemui.Dependency")
            }.getOrNull()

            if (dependencyClass != null) {
                val sDependencyField = runCatching {
                    dependencyClass.getDeclaredField("sDependency").apply { isAccessible = true }
                }.getOrNull()
                val sDependency = sDependencyField?.get(null)

                if (sDependency != null) {
                    val darkDispatcherClass = runCatching {
                        classLoader.loadClass("com.android.systemui.plugins.DarkIconDispatcher")
                    }.getOrNull() ?: runCatching {
                        classLoader.loadClass("com.android.systemui.statusbar.phone.DarkIconDispatcherImpl")
                    }.getOrNull()

                    if (darkDispatcherClass != null) {
                        // 方式 A：调用 sDependency.getDependencyInner(DarkIconDispatcher.class)
                        val getInnerMethod = runCatching {
                            dependencyClass.getDeclaredMethod("getDependencyInner", Any::class.java).apply { isAccessible = true }
                        }.getOrNull()
                        val dispatcher = runCatching {
                            getInnerMethod?.invoke(sDependency, darkDispatcherClass)
                        }.getOrNull()
                        if (dispatcher != null) {
                            dispatcherInstance = WeakReference(dispatcher)
                            return dispatcher
                        }

                        // 方式 B：调用静态 Dependency.get(DarkIconDispatcher.class) 若未被内联
                        val getMethod = runCatching {
                            dependencyClass.getDeclaredMethod("get", Class::class.java).apply { isAccessible = true }
                        }.getOrNull()
                        val dispatcherFromGet = runCatching {
                            getMethod?.invoke(null, darkDispatcherClass)
                        }.getOrNull()
                        if (dispatcherFromGet != null) {
                            dispatcherInstance = WeakReference(dispatcherFromGet)
                            return dispatcherFromGet
                        }
                    }

                    // 方式 C：直接读取 sDependency.mDarkIconDispatcher (dagger.Lazy)
                    val mField = runCatching {
                        dependencyClass.getDeclaredField("mDarkIconDispatcher").apply { isAccessible = true }
                    }.getOrNull()
                    val lazy = mField?.get(sDependency)
                    if (lazy != null) {
                        val getMethod = runCatching { lazy.javaClass.getMethod("get") }.getOrNull()
                        val dispatcherFromLazy = runCatching { getMethod?.invoke(lazy) }.getOrNull()
                        if (dispatcherFromLazy != null) {
                            dispatcherInstance = WeakReference(dispatcherFromLazy)
                            return dispatcherFromLazy
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            YLog.warning(TAG, "Error looking up DarkIconDispatcher from Dependency: ${t.message}")
        }

        // 2. 检查注入的提供者 (如测试桩或宿主注入)
        injectedDispatcherProvider?.invoke()?.let {
            dispatcherInstance = WeakReference(it)
            return it
        }

        return null
    }

    /**
     * 挂接全局 DarkIconDispatcherImpl 以捕获暗色强度及单色调色板
     */
    private fun hookDarkIconDispatcher(classLoader: ClassLoader) {
        val module = this.module ?: return
        try {
            val dispatcherClass = runCatching {
                classLoader.loadClass("com.android.systemui.statusbar.phone.DarkIconDispatcherImpl")
            }.getOrNull() ?: return

            // Hook 构造函数以捕获未来新建的实例
            dispatcherClass.declaredConstructors.forEach { ctor ->
                try {
                    val handle = module.hook(ctor).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            chain.proceed()
                            if (this@PreciseClockColorStrategy.module == null || this@PreciseClockColorStrategy.emit == null) return null
                            val instance = chain.thisObject
                            if (instance != null) {
                                dispatcherInstance = WeakReference(instance)
                            }
                            return null
                        }
                    })
                    synchronized(dispatcherHookHandles) {
                        dispatcherHookHandles.add(handle)
                    }
                } catch (_: Throwable) {}
            }

            // Hook applyDarkIntensity(float)
            val applyDarkIntensityMethod = dispatcherClass.declaredMethods.firstOrNull {
                it.name == "applyDarkIntensity" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Float::class.javaPrimitiveType
            }
            if (applyDarkIntensityMethod != null) {
                val handle = module.hook(applyDarkIntensityMethod).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        chain.proceed()
                        if (this@PreciseClockColorStrategy.module == null || this@PreciseClockColorStrategy.emit == null) return null
                        try {
                            dispatcherInstance = WeakReference(chain.thisObject)
                            val intensity = chain.args[0] as? Float ?: -1f
                            onDarkIntensityChanged(chain.thisObject, intensity)
                        } catch (t: Throwable) {
                            YLog.error(TAG, "DarkIconDispatcherImpl#applyDarkIntensity failed", t)
                        }
                        return null
                    }
                })
                synchronized(dispatcherHookHandles) {
                    dispatcherHookHandles.add(handle)
                }
                YLog.info(TAG, "Hooked DarkIconDispatcherImpl#applyDarkIntensity")
            }

            // Hook setIconsDarkArea(ArrayList)
            val setIconsDarkAreaMethod = dispatcherClass.declaredMethods.firstOrNull {
                it.name == "setIconsDarkArea" && it.parameterTypes.size == 1
            }
            if (setIconsDarkAreaMethod != null) {
                val handle = module.hook(setIconsDarkAreaMethod).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        chain.proceed()
                        if (this@PreciseClockColorStrategy.module == null || this@PreciseClockColorStrategy.emit == null) return null
                        try {
                            dispatcherInstance = WeakReference(chain.thisObject)
                            onDarkIntensityChanged(chain.thisObject, -1f)
                        } catch (t: Throwable) {
                            YLog.error(TAG, "DarkIconDispatcherImpl#setIconsDarkArea failed", t)
                        }
                        return null
                    }
                })
                synchronized(dispatcherHookHandles) {
                    dispatcherHookHandles.add(handle)
                }
                YLog.info(TAG, "Hooked DarkIconDispatcherImpl#setIconsDarkArea")
            }
        } catch (t: Throwable) {
            YLog.warning(TAG, "Failed to hook DarkIconDispatcherImpl: ${t.message}")
        }
    }

    private fun getClockTextColor(view: View?): Int? {
        val tv = view as? TextView ?: return null
        val directColor = runCatching { tv.currentTextColor }.getOrNull()?.takeIf { it != 0 }
        if (directColor != null) return directColor

        val fieldColor = runCatching {
            var cur: Class<*>? = tv.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val field = cur.getDeclaredField("mCurTextColor").apply { isAccessible = true }
                    return@runCatching field.getInt(tv)
                } catch (_: Throwable) {
                    cur = cur.superclass
                }
            }
            null
        }.getOrNull()?.takeIf { it != 0 }
        if (fieldColor != null) return fieldColor

        val tagColor = (tv.getTag(0x7F137667) as? Int)?.takeIf { it != 0 }
        if (tagColor != null) return tagColor

        return null
    }

    private fun onDarkIntensityChanged(dispatcher: Any, intensity: Float) {
        val clockColor = getClockTextColor(clockView?.get())
        if (clockColor != null) {
            emitColor(clockColor, intensity)
            return
        }

        reportDispatcherColor(dispatcher, intensity)
    }

    private fun reportDispatcherColor(dispatcher: Any, explicitIntensity: Float = -1f): Boolean {
        val iconTint = runCatching {
            val field = dispatcher.javaClass.getDeclaredField("mIconTint").apply { isAccessible = true }
            field.getInt(dispatcher)
        }.getOrNull()?.takeIf { it != 0 }

        val effIntensity = if (explicitIntensity in 0f..1f) explicitIntensity else {
            runCatching {
                val field = dispatcher.javaClass.getDeclaredField("mDarkIntensity").apply { isAccessible = true }
                field.getFloat(dispatcher)
            }.getOrDefault(-1f)
        }

        if (iconTint != null) {
            emitColor(iconTint, effIntensity)
            return true
        } else if (effIntensity in 0f..1f) {
            val derived = if (effIntensity > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            emitColor(derived, effIntensity)
            return true
        }
        return false
    }

    /**
     * 按视图实际类动态安装精确 hook，沿继承树向上收集颜色相关方法
     *
     * @param clazz 时钟视图的实际类
     */
    private fun hookClockClass(clazz: Class<*>) {
        val className = clazz.name

        if (className == FRAMEWORK_TEXT_VIEW) {
            YLog.warning(TAG, "Clock class is framework TextView, skip class hook")
            return
        }

        synchronized(hookedClasses) {
            if (!hookedClasses.add(className)) return
        }

        val module = this.module ?: return

        val methods = mutableListOf<Method>()
        var cur: Class<*>? = clazz
        while (cur != null && cur.name != FRAMEWORK_TEXT_VIEW && cur.name != "java.lang.Object") {
            cur.declaredMethods.filterTo(methods) { method ->
                !method.isBridge &&
                        !method.isSynthetic &&
                        !method.name.startsWith("get") &&
                        COLOR_KEYWORDS.any { keyword ->
                            method.name.contains(keyword, ignoreCase = true)
                        }
            }
            cur = cur.superclass
        }

        if (methods.isEmpty()) {
            YLog.warning(TAG, "No color-related methods declared on $className hierarchy")
            return
        }

        methods.forEach { method ->
            try {
                @Suppress("ObjectLiteralToLambda")
                val handle = module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        chain.proceed()
                        if (this@PreciseClockColorStrategy.module == null || this@PreciseClockColorStrategy.emit == null) return null
                        try {
                            val tv = chain.thisObject as? TextView ?: return null
                            val color = tv.currentTextColor
                            val darkIntensity = if (chain.args.size >= 2 && chain.args[1] is Float) {
                                chain.args[1] as Float
                            } else -1f
                            if (color != 0) {
                                emitColor(color, darkIntensity)
                            }
                        } catch (t: Throwable) {
                            YLog.error(TAG, "$className#${method.name} callback failed", t)
                        }
                        return null
                    }
                })
                synchronized(clockHookHandles) {
                    clockHookHandles.add(handle)
                }
            } catch (t: Throwable) {
                YLog.warning(TAG, "Hook $className#${method.name} failed")
            }
        }

        YLog.info(
            TAG,
            "Hooked ${methods.size} color methods on $className (${methods.joinToString { it.name }})"
        )
    }

    /**
     * 读取绑定时钟视图或 DarkIconDispatcher 的当前文字颜色并上报
     */
    private fun reportCurrentColor() {
        val color = getClockTextColor(clockView?.get())

        if (color != null) {
            emitColor(color)
            return
        }

        val cl = this.classLoader ?: clockView?.get()?.context?.classLoader
        val dispatcher = dispatcherInstance?.get() ?: (cl?.let { getLiveDarkIconDispatcher(it) })
        if (dispatcher != null) {
            if (reportDispatcherColor(dispatcher)) {
                return
            }
        }

        YLog.warning(TAG, "No valid status bar color signal bound; degrading safely to visible fallback color")
        emitColor(android.graphics.Color.WHITE, 0f)
    }
}
