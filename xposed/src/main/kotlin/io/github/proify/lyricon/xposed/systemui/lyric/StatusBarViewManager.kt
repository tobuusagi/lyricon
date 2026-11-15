/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏视图管理器 (StatusBar View Manager)
 *
 * 该单例负责管理所有 [StatusBarViewController] 的生命周期。
 * 遵循 DP11 设计要求：
 * 1. 同一宿主 (PhoneStatusBarView 身份) 的重复回调仅创建一个控制器（幂等去重）；
 * 2. 同一 displayId 出现新宿主（如旋屏、布局重建）时，挂载新宿主并完全注销/销毁旧宿主；
 * 3. 宿主脱落或移除时触发 onDestroy，移除歌词视图、注销监听器并清理可见性缓存。
 *
 * @author Tomakino, Proify
 * @since 2026
 */
@Suppress("unused")
object StatusBarViewManager {
    const val TAG = "StatusBarViewManager"

    /** 全局主线程 Looper */
    val MAIN_LOOPER: Looper by lazy {
        try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        } ?: run {
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
                allocateInstance.invoke(theUnsafe, Looper::class.java) as Looper
            } catch (_: Throwable) {
                val ctor = Looper::class.java.getDeclaredConstructor().apply { isAccessible = true }
                ctor.newInstance()
            }
        }
    }

    /** 全局主线程 Handler */
    val mainHandler by lazy { Handler(MAIN_LOOPER) }

    /** 存储活跃的控制器集合 */
    private val _controllers = CopyOnWriteArrayList<StatusBarViewController>()

    /** 对外暴露的只读控制器列表 */
    val controllers: List<StatusBarViewController> get() = _controllers

    /**
     * 注册并初始化控制器。
     *
     * 检查：
     * 1. 若同一 statusBarView 宿主已存在控制器，则直接忽略（幂等去重）；
     * 2. 若同一 displayId 已存在其他旧宿主的控制器，则注销并销毁旧控制器；
     * 3. 添加新控制器并执行 [StatusBarViewController.onCreate]。
     */
    fun add(controller: StatusBarViewController) {
        synchronized(this) {
            // 1. 同一 host 幂等去重
            val existingIdentical = _controllers.find { it.statusBarView === controller.statusBarView }
            if (existingIdentical != null) {
                Log.d(TAG, "Controller for host ${controller.statusBarView} already exists, ignoring duplicate add")
                return
            }

            // 2. 同一 displayId 替换重建宿主
            val targetDisplayId = controller.displayId
            val oldControllerOnSameDisplay = _controllers.find { it.displayId == targetDisplayId }
            if (oldControllerOnSameDisplay != null) {
                Log.i(TAG, "Retiring replaced controller for display $targetDisplayId: $oldControllerOnSameDisplay")
                remove(oldControllerOnSameDisplay)
            }

            // 3. 注册新控制器
            _controllers.add(controller)
        }

        runOnMainThread {
            controller.onCreate()
            Log.d(TAG, "Successfully added and created controller: $controller on display ${controller.displayId}")
        }
    }

    /**
     * 移除并销毁控制器。
     * 从集合中移除并触发 [StatusBarViewController.onDestroy]。
     */
    fun remove(controller: StatusBarViewController) {
        val removed = _controllers.remove(controller)
        if (removed) {
            runOnMainThread {
                controller.onDestroy()
                Log.d(TAG, "Successfully removed and destroyed controller: $controller")
            }
        }
    }

    /**
     * 清空并销毁所有控制器（如服务重启或单元测试重置）
     */
    fun clear() {
        val list = ArrayList(_controllers)
        _controllers.clear()
        runOnMainThread {
            for (controller in list) {
                try {
                    controller.onDestroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying controller during clear: ${e.message}")
                }
            }
        }
    }

    /**
     * 执行遍历操作（在调用者当前线程执行）。
     */
    inline fun forEach(crossinline block: (StatusBarViewController) -> Unit) {
        val currentList = controllers
        for (controller in currentList) {
            try {
                block(controller)
            } catch (e: Exception) {
                Log.e(TAG, "Error in forEach for $controller: ${e.message}")
            }
        }
    }

    /**
     * 确保在主线程安全地遍历所有控制器。
     */
    inline fun forEachOnMainThread(crossinline block: (StatusBarViewController) -> Unit) {
        runOnMainThread {
            forEach(block)
        }
    }

    /**
     * 线程调度辅助方法。
     */
    inline fun runOnMainThread(crossinline action: () -> Unit) {
        val looper = try { Looper.getMainLooper() } catch (_: Throwable) { null }
        val isMain = looper == null ||
                Looper.myLooper() === looper ||
                Thread.currentThread() === looper.thread
        if (isMain) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}
