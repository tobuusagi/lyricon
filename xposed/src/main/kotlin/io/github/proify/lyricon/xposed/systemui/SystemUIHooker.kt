/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.doOnAttach
import io.github.proify.android.extensions.deflate
import io.github.proify.android.extensions.json
import io.github.proify.android.extensions.safeEncode
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.common.util.ViewHierarchyParser
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.xposed.ModuleEntry
import io.github.proify.lyricon.xposed.hook.PackageHooker
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslator
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarDisableHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarViewResolver
import io.github.proify.lyricon.xposed.systemui.hook.ViewVisibilityTracker
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewController
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager
import io.github.proify.lyricon.xposed.systemui.util.CrashDetector
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SystemUI Hook 入口对象
 * 负责状态栏视图注入、第三方逻辑初始化及跨进程通信绑定
 */
object SystemUIHooker : PackageHooker() {
    private const val TAG = "SystemUIHooker"

    private const val TEST_CRASH = false
    private var isSafeMode = false
    private var isAppCreated = false

    var subscriber: LyriconSubscriber? = null
        internal set

    var subscriberConnectionListener: ConnectionListener? = null
        internal set

    internal fun isCoreServiceDisabled(): Boolean {
        return try {
            ModuleEntry.instance.getRemotePreferences("default").getBoolean("core_service_disable", false)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * SystemUIHooker 生产环境中向订阅端安装的连接监听器。
     * 在订阅端连接 (onConnected) 或重新连接 (onReconnected) 时，
     * 触发 BridgeCentral.sendBootCompleted() 向全系统广播恢复信号，
     * 使已在后台运行的播放器提供端重新向中央注册并回放当前播放会话。
     */
    internal class SystemUIConnectionListener(
        private val isCoreServiceDisabled: () -> Boolean
    ) : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) {
            YLog.info(TAG, "lyriconSubscriber onConnected")
            if (!isCoreServiceDisabled()) {
                BridgeCentral.sendBootCompleted()
            }
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
            YLog.info(TAG, "lyriconSubscriber onReconnected")
            if (!isCoreServiceDisabled()) {
                BridgeCentral.sendBootCompleted()
            }
        }

        override fun onDisconnected(subscriber: LyriconSubscriber) {
            YLog.info(TAG, "lyriconSubscriber onDisconnected")
        }

        override fun onConnectTimeout(subscriber: LyriconSubscriber) {
            YLog.info(TAG, "lyriconSubscriber onConnectTimeout")
        }
    }

    @androidx.annotation.VisibleForTesting
    internal var customCoroutineScope: CoroutineScope? = null

    private val mainCoroutineScope: CoroutineScope
        get() {
            customCoroutineScope?.let { return it }
            val dispatcher = try {
                val main = Dispatchers.Main
                main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
                main
            } catch (_: Throwable) {
                Dispatchers.Default
            }
            return CoroutineScope(dispatcher + SupervisorJob())
        }

    override fun onHook() {
        YLog.info(TAG, "onHook")

        if (!isMainProcess()) {
            YLog.info(TAG, "Not main process, do nothing")
            return
        }

        doOnAppCreated {
            if (isAppCreated) {
                YLog.info(TAG, "App already created, do nothing")
                return@doOnAppCreated
            }
            isAppCreated = true
            YLog.info(TAG, "App created ")
            onPreLoad()
        }
    }

    /**
     * 应用创建前的准备工作，包含崩溃检测逻辑
     */
    private fun onPreLoad() {
        YLog.info(TAG, "onPreLoad")

        val context = appContext
        if (context == null) {
            YLog.info(TAG, "App context not available")
            return
        }

        CrashDetector.getInstance(context).apply {
            record()
            // 检测到多次连续崩溃时进入安全模式，停止后续注入
            if (isContinuousCrash()) {
                isSafeMode = true
                YLog.error(TAG, "检测到连续崩溃，已停止hook")
            }
            if (isSafeMode) reset()
        }

        initCrashDataChannel()
        if (!isSafeMode) {
            onAppCreate()
        } else {
            YLog.info(TAG, "Safe mode enabled, app create skipped")
        }
    }

    private fun onAppCreate() {
        YLog.info(TAG, "onAppCreate")
        val context = appContext
        if (context == null) {
            YLog.info(TAG, "App context not available")
            return
        }

        StatusBarViewResolver.subscribe {
            YLog.info(TAG, "New status bar view resolved ")
            addStatusBarView(it)
        }

        initialize()
    }

    /**
     * 在App onCreate完成时进行各类辅助工具和监控器的初始化
     */
    private fun initialize() {
        YLog.info(TAG, "onInit")
        val context = appContext ?: return

        ScreenStateMonitor.initialize(context)
        OplusCapsuleHooker.initialize(module, classLoader)
        NotificationCoverHelper.initialize()
        ViewVisibilityTracker.initialize(module, classLoader)
        initDataChannel()

        initLyriconService()

        StatusBarDisableHooker.inject(module, classLoader)
        if (!StatusBarDisableHooker.isAvailable) {
            YLog.error(TAG, "StatusBarDisableHooker capability is unavailable; disable-state tracking degraded", StatusBarDisableHooker.lastError)
        } else if (StatusBarDisableHooker.primaryError != null) {
            YLog.info(TAG, "StatusBarDisableHooker active via fallback; primary CommandQueue error retained: ${StatusBarDisableHooker.primaryError?.message}")
        }
        StatusBarDisableHooker.addListener(object :
            StatusBarDisableHooker.OnStatusBarDisableListener {
            private var lastDisableStateChanged: Boolean? = null

            override fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean) {
                if (lastDisableStateChanged == shouldHide) return
                lastDisableStateChanged = shouldHide
                StatusBarViewManager.forEachOnMainThread { it.onDisableStateChanged(shouldHide) }
            }
        })

        StatusBarColorMonitor.initialize(module, classLoader)
        AiTranslator.init(context)
        SystemUIMediaUtils.init(context)
        StatusBarViewResolver.init(module, context)
    }

    internal fun initLyriconService(targetContext: Context? = null) {
        val context = targetContext ?: appContext ?: return
        val coreServiceDisable = isCoreServiceDisabled()

        if (!coreServiceDisable) {
            BridgeCentral.initialize(context)
            BridgeCentral.sendBootCompleted()
        } else {
            YLog.info(TAG, "已禁用内置中心服务")
        }

        val subscriber = LyriconFactory.createSubscriber(context)
        this.subscriber = subscriber

        subscriber.subscribeActivePlayer(LyricDataHub)
        io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController // 确保单例初始化并注册到 LyricDataHub

        val listener = SystemUIConnectionListener { coreServiceDisable }
        this.subscriberConnectionListener = listener
        subscriber.addConnectionListener(listener)
        mainCoroutineScope.launch {
            delay(2000)
            subscriber.register()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        subscriber = null
        subscriberConnectionListener = null
        customCoroutineScope = null
    }

    private fun initDataChannel() {
        val context = appContext ?: return
        LyriconBridge.routing(context) {
            onCommand(AppBridgeConstants.REQUEST_HIGHLIGHT_VIEW) {
                val id = it.getString("id")
                YLog.info(TAG, "App requested view highlight id: ")

                StatusBarViewManager.forEachOnMainThread { it.highlightView(id) }
            }

            onQuery(AppBridgeConstants.REQUEST_VIEW_TREE) {
                YLog.info(TAG, "App requested view tree")

                val controller = StatusBarViewManager.controllers.firstOrNull() ?: return@onQuery

                val data =
                    json.safeEncode(ViewHierarchyParser.buildNodeTree(controller.statusBarView))
                        .toByteArray(Charsets.UTF_8)
                        .deflate()

                YLog.info(TAG, "View tree reply data: ")

                reply(Bundle().apply {
                    putByteArray("result", data)
                })
            }

            onCommand(AppBridgeConstants.REQUEST_CLEAR_TRANSLATION_DB) {
                AiTranslator.clearCache { LyricDataHub.reprocessCurrentSong() }
            }
        }
    }

    /**
     * 将自定义控制器绑定到状态栏视图
     */
    private fun addStatusBarView(view: ViewGroup) {
        val attachAndAdd = {
            // 严格绑定到解析出的 PhoneStatusBarView 宿主，而非 view.rootView
            val controller = StatusBarViewController(view, LyricPrefs.getLyricStyle())
            StatusBarViewManager.add(controller)

            val isFirst = StatusBarViewManager.controllers.size == 1
            if (isFirst) {
                if (TEST_CRASH) view.postDelayed({ error("test crash") }, 3000)
            }
        }
        if (view.isAttachedToWindow) {
            attachAndAdd()
        } else {
            view.doOnAttach {
                attachAndAdd()
            }
        }
    }

    /**
     * 初始化崩溃相关的通信频道
     */
    private fun initCrashDataChannel() {
        val context = appContext ?: return
        LyriconBridge.routing(context) {
            onQuery(AppBridgeConstants.REQUEST_CHECK_SAFE_MODE) {
                reply(Bundle().apply {
                    putBoolean("result", isSafeMode)
                })
            }
        }
    }
}
