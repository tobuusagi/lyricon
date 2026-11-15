/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.logger.YLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 状态栏禁用事件 Hook 处理器
 *
 * 通过 Hook CommandQueue.disable（DP11 语义事件边界）或备用
 * CollapsedStatusBarFragment.disable 方法，
 * 监听状态栏系统信息区域的显示/隐藏状态变化，并将事件分发给注册的监听器。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object StatusBarDisableHooker {

    /** 日志标签 */
    private const val TAG = "StatusBarDisableHooker"

    /**
     * 状态栏标志位：隐藏系统信息区域
     *
     * 当该标志位被置位时，表示系统信息区域（如时钟、通知图标等）应被隐藏。
     * 该值来源于 Android 系统内部的 View.STATUS_BAR_DISABLE_SYSTEM_INFO 标志位 (0x00800000)
     */
    const val FLAG_DISABLE_SYSTEM_INFO = 0x00800000

    /**
     * Hook 能力是否可用
     */
    @Volatile
    var isAvailable: Boolean = false
        private set

    /**
     * 主 Hook (CommandQueue.disable) 失败时的异常（若备用回退成功，则此字段保留主 Hook 的原始异常）
     */
    @Volatile
    var primaryError: Throwable? = null
        private set

    /**
     * Hook 安装完全失败时的异常
     */
    @Volatile
    var lastError: Throwable? = null
        private set

    /**
     * 当前监控的目标显示屏 ID，默认为默认显示屏 0
     */
    @Volatile
    var targetDisplayId: Int = 0

    /**
     * 上一次分发的状态，用于仅转发状态转换（避免重复分发）
     */
    @Volatile
    private var lastShouldHide: Boolean? = null

    /**
     * 状态栏禁用事件监听器集合
     */
    private val listeners = CopyOnWriteArraySet<OnStatusBarDisableListener>()

    /**
     * 注册状态栏禁用事件监听器
     */
    fun addListener(listener: OnStatusBarDisableListener) {
        listeners.add(listener)
    }

    /**
     * 移除状态栏禁用事件监听器
     */
    fun removeListener(listener: OnStatusBarDisableListener) {
        listeners.remove(listener)
    }

    /**
     * 执行 Hook 注入
     *
     * 优先 Hook [com.android.systemui.statusbar.CommandQueue.disable] 方法，
     * 若不存在则回退尝试 [CollapsedStatusBarFragment.disable]。
     */
    @SuppressLint("PrivateApi")
    fun inject(module: XposedModule, classLoader: ClassLoader) {
        // 1. 优先尝试 CommandQueue.disable(int, int, int, boolean)
        try {
            val cqClass = Class.forName(
                "com.android.systemui.statusbar.CommandQueue",
                true,
                classLoader
            )
            val cqMethod = cqClass.getDeclaredMethod(
                "disable",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )

            @Suppress("ObjectLiteralToLambda")
            module.hook(cqMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val displayId = chain.args[0] as Int
                    val state1 = chain.args[1] as Int
                    val animate = chain.args[3] as Boolean
                    handleDisableEvent(displayId, state1, animate)
                    return result
                }
            })
            isAvailable = true
            primaryError = null
            lastError = null
            YLog.info(TAG, "Successfully injected disable hook via CommandQueue.disable")
            return
        } catch (t: Throwable) {
            primaryError = t
            YLog.warning(TAG, "CommandQueue.disable hook unavailable, retaining throwable and trying legacy fallback: ${t.message}")
        }

        // 2. 备用回退尝试 CollapsedStatusBarFragment.disable
        try {
            val legacyClass = Class.forName(
                "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                true,
                classLoader
            )
            val legacyMethod = legacyClass.getDeclaredMethod(
                "disable",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )

            @Suppress("ObjectLiteralToLambda")
            module.hook(legacyMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val displayId = chain.args[0] as Int
                    val state1 = chain.args[1] as Int
                    val animate = chain.args[3] as Boolean
                    handleDisableEvent(displayId, state1, animate)
                    return result
                }
            })
            isAvailable = true
            lastError = null
            YLog.info(TAG, "Successfully injected disable hook via CollapsedStatusBarFragment fallback (primary CommandQueue error retained)")
        } catch (t: Throwable) {
            isAvailable = false
            lastError = t
            YLog.error(TAG, "Failed to inject disable hook; disable-state capability unavailable", t)
        }
    }

    /**
     * 处理禁用事件：显示屏过滤、状态提取、转换去重及监听器隔离分发
     */
    fun handleDisableEvent(displayId: Int, state1: Int, animate: Boolean) {
        if (displayId != targetDisplayId) return

        val shouldHide = (state1 and FLAG_DISABLE_SYSTEM_INFO != 0)

        synchronized(this) {
            if (lastShouldHide == shouldHide) return
            lastShouldHide = shouldHide
        }

        listeners.forEach { listener ->
            try {
                listener.onDisableStateChanged(shouldHide, animate)
            } catch (e: Throwable) {
                YLog.error(TAG, "Listener threw exception on disable state dispatch", e)
            }
        }
    }

    /**
     * 用于单元测试的状态重置
     */
    fun resetForTesting() {
        listeners.clear()
        lastShouldHide = null
        targetDisplayId = 0
        isAvailable = false
        primaryError = null
        lastError = null
    }

    /**
     * 状态栏禁用状态变化监听器接口
     */
    interface OnStatusBarDisableListener {
        fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean)
    }
}
