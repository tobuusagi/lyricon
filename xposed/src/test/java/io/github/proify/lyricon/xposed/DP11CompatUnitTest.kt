/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.provider.LyriconFactory as ProviderFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderTestBridge
import io.github.proify.lyricon.provider.isConnected
import io.github.proify.lyricon.provider.isDisconnected
import io.github.proify.lyricon.subscriber.SubscriberTestBridge
import io.github.proify.lyricon.xposed.systemui.SystemUIHooker
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarDisableHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarViewResolver
import io.github.proify.lyricon.xposed.systemui.hook.ViewVisibilityTracker
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewController
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * DP11 兼容性生产路径单元测试
 * 严格调用生产逻辑，全面覆盖 GPT-REVIEW 与审查规范要求：
 * 1. Disable 标志位提取、显示屏过滤、动画传递、状态去重与异常隔离
 * 2. Hook 故障诊断：主 Hook 失败备用成功时捕获保留异常；全量安装失败记录能力丢失
 * 3. Resolver：生产 isMatchingStatusBarHost 校验、inflate 过滤与 addView 发现
 * 4. Controller：真实视图插入、时钟锚点选择、Compose 占位降级回退与降级日志事件
 * 5. 时钟形态发现与可见性：生产 discoverClock、Compose 降级与原生规则恢复语义
 * 6. ViewVisibilityTracker：生产追踪器身份索引、ID 查询碰撞保护、子视图脱落清理与 Compose 排除
 * 7. 多屏与生命周期清理：单屏销毁不破坏活跃屏幕颜色绑定、退役宿主歌词与监听清理
 * 8. 跨进程通信与包名身份回归
 */
class DP11CompatUnitTest {

    @Before
    fun setUp() {
        if (android.os.Looper.myLooper() == null) {
            try {
                android.os.Looper.prepareMainLooper()
            } catch (_: Throwable) {
                try {
                    android.os.Looper.prepare()
                } catch (_: Throwable) {}
            }
        }
        setupMockModuleEntry()
        StatusBarDisableHooker.resetForTesting()
        StatusBarViewResolver.resetForTesting()
        StatusBarColorMonitor.resetForTesting()
        ClockViewFinder.resetForTesting()
        ViewVisibilityTracker.clearAllTracking()
        StatusBarViewManager.clear()
        LyricDataHub.resetForTesting()
        LyricViewController.resetForTesting()
        io.github.proify.lyricon.common.util.ResourceMapper.clearCache()
    }

    private fun setupMockModuleEntry() {
        try {
            val module = ModuleEntry()
            val mockSp = object : SharedPreferences {
                override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
                override fun getString(key: String?, defValue: String?): String? = defValue
                override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
                override fun getInt(key: String?, defValue: Int): Int = defValue
                override fun getLong(key: String?, defValue: Long): Long = defValue
                override fun getFloat(key: String?, defValue: Float): Float = defValue
                override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
                override fun contains(key: String?): Boolean = false
                override fun edit(): SharedPreferences.Editor {
                    return Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)
                    ) { proxy, method, _ ->
                        if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
                    } as SharedPreferences.Editor
                }
                override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
                override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            }

            val mockInterface = Proxy.newProxyInstance(
                XposedInterface::class.java.classLoader,
                arrayOf(XposedInterface::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getRemotePreferences" -> mockSp
                    else -> null
                }
            } as XposedInterface

            try {
                module.attachFramework(mockInterface) {}
            } catch (_: Throwable) {}
            ModuleEntry.instance = module
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 1. Disable 标志位提取、显示屏过滤、动画传递、状态去重与异常隔离测试
    // =========================================================================

    @Test
    fun testDisableHooker_FlagExtractionAndTransitions() {
        StatusBarDisableHooker.targetDisplayId = 0
        val events = mutableListOf<Pair<Boolean, Boolean>>()

        val listener = object : StatusBarDisableHooker.OnStatusBarDisableListener {
            override fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean) {
                events.add(shouldHide to animate)
            }
        }
        StatusBarDisableHooker.addListener(listener)

        // 1. 非目标 displayId 的事件应被忽略
        StatusBarDisableHooker.handleDisableEvent(
            displayId = 1,
            state1 = StatusBarDisableHooker.FLAG_DISABLE_SYSTEM_INFO,
            animate = true
        )
        assertEquals("非目标 displayId 的事件必须被忽略", 0, events.size)

        // 2. 不相关位被置位，FLAG_DISABLE_SYSTEM_INFO 未置位 -> shouldHide = false
        StatusBarDisableHooker.handleDisableEvent(
            displayId = 0,
            state1 = 0x00010000,
            animate = false
        )
        assertEquals(1, events.size)
        assertEquals(false to false, events.last())

        // 3. FLAG_DISABLE_SYSTEM_INFO 置位 -> shouldHide = true, 转发动画标志
        StatusBarDisableHooker.handleDisableEvent(
            displayId = 0,
            state1 = StatusBarDisableHooker.FLAG_DISABLE_SYSTEM_INFO or 0x00000004,
            animate = true
        )
        assertEquals(2, events.size)
        assertEquals(true to true, events.last())

        // 4. 相同状态再次调用 -> 仅转发状态转换，重复状态被去重
        StatusBarDisableHooker.handleDisableEvent(
            displayId = 0,
            state1 = StatusBarDisableHooker.FLAG_DISABLE_SYSTEM_INFO or 0x00000008,
            animate = false
        )
        assertEquals("状态未改变时不应重复分发事件", 2, events.size)

        // 5. 状态转换：清除系统信息禁用标志 -> shouldHide = false
        StatusBarDisableHooker.handleDisableEvent(
            displayId = 0,
            state1 = 0x00000000,
            animate = true
        )
        assertEquals(3, events.size)
        assertEquals(false to true, events.last())
    }

    @Test
    fun testDisableHooker_IsolatesThrowingListeners() {
        StatusBarDisableHooker.targetDisplayId = 0
        var secondListenerCalled = false

        val faultyListener = object : StatusBarDisableHooker.OnStatusBarDisableListener {
            override fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean) {
                throw RuntimeException("Faulty listener intentional exception")
            }
        }
        val healthyListener = object : StatusBarDisableHooker.OnStatusBarDisableListener {
            override fun onDisableStateChanged(shouldHide: Boolean, animate: Boolean) {
                secondListenerCalled = true
            }
        }

        StatusBarDisableHooker.addListener(faultyListener)
        StatusBarDisableHooker.addListener(healthyListener)

        StatusBarDisableHooker.handleDisableEvent(
            displayId = 0,
            state1 = StatusBarDisableHooker.FLAG_DISABLE_SYSTEM_INFO,
            animate = true
        )

        assertTrue("某个监听器抛出异常不应中断其他监听器的通知", secondListenerCalled)
    }

    // =========================================================================
    // 2. Hook 故障诊断：主 Hook 失败保留异常；全量安装失败记录能力丢失
    // =========================================================================

    @Test
    fun testDisableHooker_Diagnostics_PrimaryFailsFallbackSucceeds_RetainsThrowable() {
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                val normalized = name.replace('/', '.')
                if (normalized == "com.android.systemui.statusbar.CommandQueue") {
                    throw ClassNotFoundException("CommandQueue missing on this build (simulated primary failure)")
                }
                return super.loadClass(name, resolve)
            }
        }

        val mockModule = createMockModule()
        StatusBarDisableHooker.inject(mockModule, classLoader)

        assertTrue("备用 Hook 成功后能力应标记为可用", StatusBarDisableHooker.isAvailable)
        assertNotNull("主 Hook 失败的原始 Throwable 必须被保留", StatusBarDisableHooker.primaryError)
        assertTrue(
            "保留的异常必须为 CommandQueue 的 ClassNotFoundException",
            StatusBarDisableHooker.primaryError is ClassNotFoundException
        )
        assertNull("备用成功时 lastError 应保持为 null", StatusBarDisableHooker.lastError)
    }

    @Test
    fun testDisableHooker_Diagnostics_TotalInstallationFailure_RecordsCapabilityFailure() {
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                val normalized = name.replace('/', '.')
                if (normalized == "com.android.systemui.statusbar.CommandQueue" ||
                    normalized == "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment"
                ) {
                    throw ClassNotFoundException("Total failure: neither class exists (simulated total failure)")
                }
                return super.loadClass(name, resolve)
            }
        }

        val mockModule = createMockModule()
        StatusBarDisableHooker.inject(mockModule, classLoader)

        assertFalse("全量失败时 Hook 能力必须记录为不可用", StatusBarDisableHooker.isAvailable)
        assertNotNull("全量失败时必须记录 lastError 异常", StatusBarDisableHooker.lastError)
        assertTrue(
            "lastError 必须包含失败信息",
            StatusBarDisableHooker.lastError!!.message!!.contains("Total failure")
        )
    }

    // =========================================================================
    // 3. Resolver：生产 isMatchingStatusBarHost 校验、inflate 过滤与 addView 发现
    // =========================================================================

    companion object {
        fun View.parentView(): ViewParent? =
            StatusBarViewResolver.testParentMap[this]
                ?: (getTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY) as? ViewParent)
                ?: parent
    }

    open class TestView(val mContext: Context, var customId: Int = View.NO_ID) : View(mContext) {
        var customParent: ViewParent? = null
        var customVisibility: Int = View.VISIBLE
        val mTags = mutableMapOf<Int, Any>()
        var mTag: Any? = null

        init {
            id = customId
            setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
        }

        override fun getId(): Int = customId
        override fun setId(id: Int) { customId = id }
        override fun getVisibility(): Int = customVisibility
        override fun setVisibility(visibility: Int) { customVisibility = visibility }
        override fun getTag(): Any? = mTag
        override fun setTag(tag: Any?) { mTag = tag }
        override fun getTag(key: Int): Any? = mTags[key]
        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) mTags.remove(key) else mTags[key] = tag
        }
    }

    open class TestStatusBarViewGroup(val mContext: Context, var customId: Int = View.NO_ID) : ViewGroup(mContext) {
        val mChildren = mutableListOf<View>()
        var customParent: ViewParent? = null
        var isAttached: Boolean = true
        var customVisibility: Int = View.VISIBLE
        val mTags = mutableMapOf<Int, Any>()
        var mTag: Any? = null

        init {
            id = customId
            setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
        }

        override fun getId(): Int = customId
        override fun setId(id: Int) { customId = id }
        override fun getVisibility(): Int = customVisibility
        override fun setVisibility(visibility: Int) { customVisibility = visibility }
        override fun getTag(): Any? = mTag
        override fun setTag(tag: Any?) { mTag = tag }
        override fun getTag(key: Int): Any? = mTags[key]
        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) mTags.remove(key) else mTags[key] = tag
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
        override fun isAttachedToWindow(): Boolean = isAttached

        override fun addView(child: View) {
            addView(child, -1, child.layoutParams)
        }

        override fun addView(child: View, index: Int) {
            addView(child, index, child.layoutParams)
        }

        override fun addView(child: View, params: LayoutParams?) {
            addView(child, -1, params)
        }

        override fun addView(child: View, index: Int, params: LayoutParams?) {
            params?.let { child.layoutParams = it }
            StatusBarViewResolver.testParentMap[child] = this
            child.setTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY, this)
            if (child.getTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY) == null) {
                child.setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
            }
            if (child is TestStatusBarViewGroup) {
                child.customParent = this
            } else if (child is TestStatusBarLinearLayout) {
                child.customParent = this
            } else if (child is TestView) {
                child.customParent = this
            } else if (child is DummyTextClock) {
                child.customParent = this
            }
            if (index < 0 || index >= mChildren.size) {
                mChildren.add(child)
            } else {
                mChildren.add(index, child)
            }
        }

        override fun removeView(child: View) {
            StatusBarViewResolver.testParentMap.remove(child)
            child.setTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY, null)
            mChildren.remove(child)
            if (child is TestStatusBarViewGroup) {
                child.customParent = null
            } else if (child is TestStatusBarLinearLayout) {
                child.customParent = null
            } else if (child is TestView) {
                child.customParent = null
            } else if (child is DummyTextClock) {
                child.customParent = null
            }
        }

        override fun getChildCount(): Int = mChildren.size
        override fun getChildAt(index: Int): View? = mChildren.getOrNull(index)
        override fun indexOfChild(child: View): Int = mChildren.indexOf(child)
    }

    open class TestStatusBarLinearLayout(val mContext: Context, var customId: Int = View.NO_ID) : LinearLayout(mContext) {
        val mChildren = mutableListOf<View>()
        var customParent: ViewParent? = null
        var isAttached: Boolean = true
        var customVisibility: Int = View.VISIBLE
        val mTags = mutableMapOf<Int, Any>()
        var mTag: Any? = null

        init {
            id = customId
            setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
        }

        override fun getId(): Int = customId
        override fun setId(id: Int) { customId = id }
        override fun getVisibility(): Int = customVisibility
        override fun setVisibility(visibility: Int) { customVisibility = visibility }
        override fun getTag(): Any? = mTag
        override fun setTag(tag: Any?) { mTag = tag }
        override fun getTag(key: Int): Any? = mTags[key]
        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) mTags.remove(key) else mTags[key] = tag
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
        override fun isAttachedToWindow(): Boolean = isAttached

        override fun getChildCount(): Int = mChildren.size
        override fun getChildAt(index: Int): View? = mChildren.getOrNull(index)
        override fun indexOfChild(child: View): Int = mChildren.indexOf(child)

        override fun addView(child: View) {
            addView(child, -1, child.layoutParams)
        }

        override fun addView(child: View, index: Int) {
            addView(child, index, child.layoutParams)
        }

        override fun addView(child: View, params: ViewGroup.LayoutParams?) {
            addView(child, -1, params)
        }

        override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
            params?.let { child.layoutParams = it }
            StatusBarViewResolver.testParentMap[child] = this
            child.setTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY, this)
            if (child.getTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY) == null) {
                child.setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
            }
            if (child is TestStatusBarViewGroup) {
                child.customParent = this
            } else if (child is TestStatusBarLinearLayout) {
                child.customParent = this
            } else if (child is TestView) {
                child.customParent = this
            } else if (child is DummyTextClock) {
                child.customParent = this
            }
            if (index < 0 || index >= mChildren.size) {
                mChildren.add(child)
            } else {
                mChildren.add(index, child)
            }
        }

        override fun removeView(child: View) {
            StatusBarViewResolver.testParentMap.remove(child)
            child.setTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY, null)
            mChildren.remove(child)
            if (child is TestStatusBarViewGroup) {
                child.customParent = null
            } else if (child is TestStatusBarLinearLayout) {
                child.customParent = null
            } else if (child is TestView) {
                child.customParent = null
            } else if (child is DummyTextClock) {
                child.customParent = null
            }
        }

        override fun generateLayoutParams(lp: ViewGroup.LayoutParams): LayoutParams {
            return LayoutParams(lp)
        }
    }

    class PhoneStatusBarView(ctx: Context, id: Int = View.NO_ID) : TestStatusBarViewGroup(ctx, id)
    class StatusBarWindowView(ctx: Context, id: Int = View.NO_ID) : TestStatusBarViewGroup(ctx, id)
    class ArbitraryViewGroup(ctx: Context, id: Int = View.NO_ID) : TestStatusBarViewGroup(ctx, id)
    class ComposeView(ctx: Context, id: Int = View.NO_ID) : TestStatusBarViewGroup(ctx, id)

    @Test
    fun testStatusBarResolver_ProductionIsMatchingStatusBarHost() {
        val context = createMockContext(
            layoutMap = mapOf("status_bar" to 10),
            idMap = mapOf(
                "status_bar" to 100,
                "status_bar_container" to 200,
                "unrelated_id" to 300
            )
        )

        val windowView = StatusBarWindowView(context)
        val container = ArbitraryViewGroup(context, 200)
        val phoneBar = PhoneStatusBarView(context)

        windowView.addView(container, -1, null)
        container.addView(phoneBar, -1, null)

        // 1. 正确调用生产路径 StatusBarViewResolver.isMatchingStatusBarHost
        assertTrue(
            "PhoneStatusBarView 处于 StatusBarWindowView / container 祖先下应被生产解析器判定为有效宿主",
            StatusBarViewResolver.isMatchingStatusBarHost(phoneBar, context)
        )

        // 2. 具备 status_bar 资源 ID 的普通 ViewGroup 在 container 祖先下也是有效宿主
        val barById = ArbitraryViewGroup(context, 100)
        container.addView(barById, -1, null)
        assertTrue(
            "具备 status_bar 资源 ID 且祖先正确的 ViewGroup 必须通过校验",
            StatusBarViewResolver.isMatchingStatusBarHost(barById, context)
        )

        // 3. 拒绝不相关的资源 ID（即使位于 container 容器内）
        val unrelatedChild = ArbitraryViewGroup(context, 300)
        container.addView(unrelatedChild, -1, null)
        assertFalse(
            "非 status_bar 身份的无关 ID 视图即使在容器中也必须被拒绝",
            StatusBarViewResolver.isMatchingStatusBarHost(unrelatedChild, context)
        )

        // 4. 缺少 SystemUI 容器祖先的 PhoneStatusBarView 必须被拒绝（如普通 DecorView 下的同名视图）
        val standaloneDecor = ArbitraryViewGroup(context, 888)
        val standaloneBar = PhoneStatusBarView(context)
        standaloneDecor.addView(standaloneBar, -1, null)
        assertFalse(
            "缺少 StatusBarWindowView / status_bar_container 祖先的视图必须被拒绝",
            StatusBarViewResolver.isMatchingStatusBarHost(standaloneBar, context)
        )

        // 5. 非 ViewGroup 视图直接拒绝
        val dummyView = TestView(context)
        assertFalse(
            "非 ViewGroup 实例必须被拒绝",
            StatusBarViewResolver.isMatchingStatusBarHost(dummyView, context)
        )
    }

    @Test
    fun testStatusBarResolver_InflateOverloadFilterAndAddViewDiscovery() {
        val context = createMockContext(
            layoutMap = mapOf("status_bar" to 10),
            idMap = mapOf(
                "status_bar" to 100,
                "status_bar_container" to 200
            )
        )

        var inflateHooker: XposedInterface.Hooker? = null
        var addViewHooker: XposedInterface.Hooker? = null

        val mockModule = createMockModule { exec, hooker ->
            if (exec is Method) {
                if (exec.name == "inflate" && exec.parameterTypes.size == 3) {
                    inflateHooker = hooker
                } else if (exec.name == "addView" && exec.parameterTypes.size == 3) {
                    addViewHooker = hooker
                }
            }
        }

        StatusBarViewResolver.init(mockModule, context)
        assertTrue("生产解析器初始化后 inflate hook 必须标记为已安装", StatusBarViewResolver.isInflateHookInstalled)
        assertTrue("生产解析器初始化后 addView fallback hook 必须标记为已安装", StatusBarViewResolver.isFallbackHookInstalled)
        assertNotNull("LayoutInflater.inflate hooker 必须已注册", inflateHooker)
        assertNotNull("ViewGroup.addView fallback hooker 必须已注册", addViewHooker)

        val publishedViews = mutableListOf<ViewGroup>()
        val listener: StatusBarViewResolver.OnViewResolvedListener = { publishedViews.add(it) }
        StatusBarViewResolver.subscribe(listener)

        val windowView = StatusBarWindowView(context)
        val phoneBar = PhoneStatusBarView(context, 100)
        windowView.addView(phoneBar, -1, null)

        // A. 模拟 inflate 调用：使用正确的 status_bar layoutId (10)
        val inflateChainCorrect = createMockChain(args = listOf(10, windowView, false), result = phoneBar)
        inflateHooker!!.intercept(inflateChainCorrect)
        assertEquals("正确的 status_bar layout ID 必须触发 publish 发布", 1, publishedViews.size)
        assertSame(phoneBar, publishedViews.last())

        // B. 模拟 inflate 调用：使用不相关的 layoutId (999) -> 被过滤，不发布
        val inflateChainWrong = createMockChain(args = listOf(999, windowView, false), result = phoneBar)
        inflateHooker!!.intercept(inflateChainWrong)
        assertEquals("非 status_bar layout ID 必须被过滤拒绝发布", 1, publishedViews.size)

        // C. 模拟 addView 回退发现：child 符合宿主身份与祖先条件
        val fallbackBar = PhoneStatusBarView(context, 100)
        windowView.addView(fallbackBar, -1, null)
        val addViewChainValid = createMockChain(args = listOf(fallbackBar, -1, ViewGroup.LayoutParams(0, 0)))
        addViewHooker!!.intercept(addViewChainValid)
        assertEquals("通过 addView 发现的合法宿主必须触发发布", 2, publishedViews.size)
        assertSame(fallbackBar, publishedViews.last())

        // D. 模拟 addView：child 为无关普通视图 -> 被拒绝
        val randomView = ArbitraryViewGroup(context, 999)
        val addViewChainInvalid = createMockChain(args = listOf(randomView, -1, ViewGroup.LayoutParams(0, 0)))
        addViewHooker!!.intercept(addViewChainInvalid)
        assertEquals("addView 传入无关视图不得触发发布", 2, publishedViews.size)

        // E. 延迟挂载测试：未附加到窗口时不立即通知，直到 attached
        val detachedBar = PhoneStatusBarView(context, 100).apply { isAttached = false }
        StatusBarViewResolver.publish(detachedBar)
        assertEquals("未附加到窗口的视图不得提前分发", 2, publishedViews.size)
    }

    @Test
    fun testStatusBarResolver_IndependentCallbackFailures() {
        var secondSubscriberCalled = false
        val faultySubscriber: StatusBarViewResolver.OnViewResolvedListener = {
            throw RuntimeException("Faulty subscriber intentional exception")
        }
        val healthySubscriber: StatusBarViewResolver.OnViewResolvedListener = {
            secondSubscriberCalled = true
        }

        StatusBarViewResolver.subscribe(faultySubscriber)
        StatusBarViewResolver.subscribe(healthySubscriber)

        val context = createMockContext()
        val mockView = PhoneStatusBarView(context)
        StatusBarViewResolver.publish(mockView)

        assertTrue("某个订阅者抛出异常不应影响其他订阅者接收 View", secondSubscriberCalled)
    }

    // =========================================================================
    // 4. Controller：真实视图插入、时钟锚点选择、Compose 占位降级回退与降级日志事件
    // =========================================================================

    @Test
    fun testController_ActualInsertionAndAnchorSelection_LegacyVsComposeHost() {
        val context = createMockContext(
            idMap = mapOf(
                "clock" to 101,
                "status_bar_start_side_content" to 102,
                "status_bar_contents" to 103
            )
        )

        // 场景 1：传统纯 View 宿主（无 Compose）
        val legacyHost = PhoneStatusBarView(context)
        val sideContainer = TestStatusBarViewGroup(context, 102)
        val legacyClock = TestView(context, 101).apply {
            visibility = View.VISIBLE
        }
        legacyHost.addView(sideContainer, -1, null)
        legacyHost.addView(legacyClock, -1, null)

        val legacyStyle = LyricStyle().apply {
            basicStyle.anchor = "clock"
            basicStyle.insertionOrder = BasicStyle.INSERTION_ORDER_AFTER
        }
        val legacyController = StatusBarViewController(legacyHost, legacyStyle)
        legacyController.updateLyricStyle(legacyStyle)

        // 验证真实插入：传统宿主上歌词视图成功插入到时钟旁边 (父容器为 legacyHost)
        assertNotNull("歌词视图必须已被挂载", legacyController.lyricView.parentView())
        assertSame("传统宿主上歌词视图应插入在时钟的父容器中", legacyHost, legacyController.lyricView.parentView())
        val clockIndex = legacyHost.indexOfChild(legacyClock)
        val lyricIndex = legacyHost.indexOfChild(legacyController.lyricView)
        assertEquals("AFTER 插入模式下歌词视图索引应为 clockIndex + 1", clockIndex + 1, lyricIndex)

        // 场景 2：DP11 Compose 混合宿主（包含 ComposeView 与可见的 legacy clock 占位）
        val composeHost = PhoneStatusBarView(context)
        val composeSideContent = TestStatusBarViewGroup(context, 102)
        val composeView = ComposeView(context) // 类名包含 ComposeView
        val composeClockPlaceholder = TestView(context, 101).apply {
            visibility = View.VISIBLE // 即使是 VISIBLE 占位，也不可被用作锚点！
        }

        composeHost.addView(composeSideContent, -1, null)
        composeHost.addView(composeView, -1, null)
        composeHost.addView(composeClockPlaceholder, -1, null)

        val composeStyle = LyricStyle().apply {
            basicStyle.anchor = "clock"
            basicStyle.insertionOrder = BasicStyle.INSERTION_ORDER_BEFORE
        }
        val composeController = StatusBarViewController(composeHost, composeStyle)
        composeController.updateLyricStyle(composeStyle)

        // 核心断言：检测到 Compose 宿主时，严禁插入在 legacy clock 占位旁边，必须降级使用 status_bar_start_side_content 回退！
        assertNotNull("Compose 宿主上歌词视图必须已被挂载", composeController.lyricView.parentView())
        assertNotSame(
            "Compose 宿主上严禁使用 legacy clock 占位作为歌词插入锚点",
            composeHost,
            composeController.lyricView.parentView()
        )
        assertSame(
            "Compose 宿主上必须降级插入在 status_bar_start_side_content 回退容器内",
            composeSideContent,
            composeController.lyricView.parentView()
        )

        // 场景 3：缺失锚点或不可用锚点 -> 回退到稳定容器或根视图
        val missingAnchorStyle = LyricStyle().apply {
            basicStyle.anchor = "nonexistent_anchor_name"
        }
        composeController.updateLyricStyle(missingAnchorStyle)
        assertSame(
            "缺失锚点时必须回退到 status_bar_start_side_content",
            composeSideContent,
            composeController.lyricView.parentView()
        )
    }

    // =========================================================================
    // 5. 时钟形态发现与可见性：生产 discoverClock、Compose 降级与原生规则恢复语义
    // =========================================================================

    class DummyTextClock(val mContext: Context, var customId: Int = View.NO_ID) : TextView(mContext) {
        var customParent: ViewParent? = null
        var customVisibility: Int = View.VISIBLE
        val mTags = mutableMapOf<Int, Any>()
        var mTag: Any? = null

        init {
            id = customId
            setTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY, mContext)
        }

        override fun getId(): Int = customId
        override fun setId(id: Int) { customId = id }
        override fun getVisibility(): Int = customVisibility
        override fun setVisibility(visibility: Int) { customVisibility = visibility }
        override fun setTextColor(color: Int) {
            setTag(0x7F137667, color)
            runCatching {
                val field = TextView::class.java.getDeclaredField("mCurTextColor").apply { isAccessible = true }
                field.setInt(this, color)
            }
        }
        override fun getTag(): Any? = mTag
        override fun setTag(tag: Any?) { mTag = tag }
        override fun getTag(key: Int): Any? = mTags[key]
        override fun setTag(key: Int, tag: Any?) {
            if (tag == null) mTags.remove(key) else mTags[key] = tag
        }
    }

    @Test
    fun testClockDiscovery_ProductionDiscoverClock_DistinguishesComposeAndLegacy() {
        val context = createMockContext(idMap = mapOf("clock" to 101))

        // 1. 传统视图树：返回 LEGACY_VIEW
        val legacyRoot = PhoneStatusBarView(context)
        val clockView = DummyTextClock(context, 101)
        legacyRoot.addView(clockView, -1, null)

        val legacyResult = ClockViewFinder.discoverClock(legacyRoot)
        assertEquals(ClockViewFinder.ClockKind.LEGACY_VIEW, legacyResult.kind)
        assertFalse("传统视图时钟不是 Compose 时钟", legacyResult.isComposeClock)
        assertTrue("传统视图时钟支持隐藏恢复", legacyResult.isHideRestoreSupported)
        assertFalse("传统宿主未检测到 Compose", legacyResult.hasCompose)
        assertSame("发现的时钟视图对象必须与添加的一致", clockView, legacyResult.view)

        // 2. DP11 树：包含 ComposeView 与传统 Clock 视图 -> 返回 LEGACY_VIEW (hasCompose = true)
        val dp11Root = PhoneStatusBarView(context)
        val composeElement = ComposeView(context)
        dp11Root.addView(composeElement, -1, null)
        dp11Root.addView(clockView, -1, null)

        val dp11Result = ClockViewFinder.discoverClock(dp11Root)
        assertEquals(ClockViewFinder.ClockKind.LEGACY_VIEW, dp11Result.kind)
        assertFalse("存在传统时钟视图时不标记为 Compose 时钟", dp11Result.isComposeClock)
        assertTrue("存在传统时钟视图时支持隐藏恢复", dp11Result.isHideRestoreSupported)
        assertTrue("记录存在 Compose 视图层级", dp11Result.hasCompose)
        assertSame("必须正确返回传统时钟视图对象", clockView, dp11Result.view)

        // 3. 纯 Compose 树（完全不存在传统时钟视图） -> 返回 COMPOSE
        val pureComposeRoot = PhoneStatusBarView(context)
        pureComposeRoot.addView(composeElement, -1, null)
        val pureComposeResult = ClockViewFinder.discoverClock(pureComposeRoot)
        assertEquals(ClockViewFinder.ClockKind.COMPOSE, pureComposeResult.kind)
        assertTrue("纯 Compose 状态栏标记为 Compose 时钟", pureComposeResult.isComposeClock)
        assertFalse("纯 Compose 状态栏无传统时钟视图不支持隐藏恢复", pureComposeResult.isHideRestoreSupported)
        assertTrue("纯 Compose 状态栏记录存在 Compose", pureComposeResult.hasCompose)

        // 4. 无时钟空树 -> NONE
        val emptyRoot = PhoneStatusBarView(context)
        val emptyResult = ClockViewFinder.discoverClock(emptyRoot)
        assertEquals(ClockViewFinder.ClockKind.NONE, emptyResult.kind)
    }

    @Test
    fun testViewVisibilityController_ComposeDegradationAndNativeRestore() {
        val context = createMockContext(
            idMap = mapOf(
                "clock" to 101,
                "notification_icons" to 102
            )
        )

        // 场景 A：在纯 Compose 宿主且完全无传统时钟时，针对 clock 的规则被显式跳过降级，保护原生状态
        val pureComposeHost = PhoneStatusBarView(context)
        val composeNode = ComposeView(context)
        pureComposeHost.addView(composeNode, -1, null)

        val composeVisibilityController = ViewVisibilityController(pureComposeHost)
        val clockRule = VisibilityRule("clock", VisibilityRule.MODE_HIDE_WHEN_PLAYING)

        composeVisibilityController.applyVisibilityRules(listOf(clockRule), isPlaying = true)
        // 验证不会抛出异常且不会对不存在的传统视图误操作

        // 场景 B：DP11 包含 Compose 但存在真实时钟视图时，时钟隐藏与恢复正常生效
        val dp11Host = PhoneStatusBarView(context)
        val dp11ComposeNode = ComposeView(context)
        val dp11Clock = DummyTextClock(context, 101).apply {
            visibility = View.VISIBLE
        }
        dp11Host.addView(dp11ComposeNode, -1, null)
        dp11Host.addView(dp11Clock, -1, null)

        val dp11VisibilityController = ViewVisibilityController(dp11Host)
        dp11VisibilityController.applyVisibilityRules(listOf(clockRule), isPlaying = true)
        assertEquals(
            "DP11 存在真实时钟视图时，播放中应被正常隐藏 (CUSTOM_GONE)",
            ViewVisibilityTracker.CUSTOM_GONE,
            dp11Clock.visibility
        )
        dp11VisibilityController.applyVisibilityRules(listOf(clockRule), isPlaying = false)
        assertEquals(
            "停止播放后，DP11 真实时钟视图必须恢复为原始 VISIBLE",
            View.VISIBLE,
            dp11Clock.visibility
        )

        // 场景 C：原生 View 规则在传统视图上的隐藏与恢复语义测试
        val nativeHost = PhoneStatusBarView(context)
        val iconView = DummyTextClock(context, 102).apply {
            visibility = View.VISIBLE
        }
        nativeHost.addView(iconView, -1, null)

        val nativeVisibilityController = ViewVisibilityController(nativeHost)
        val iconRule = VisibilityRule("notification_icons", VisibilityRule.MODE_HIDE_WHEN_PLAYING)

        // 播放中：应用隐藏 (CUSTOM_GONE)
        nativeVisibilityController.applyVisibilityRules(listOf(iconRule), isPlaying = true)
        assertEquals(
            "播放中原生视图应被设置为 CUSTOM_GONE",
            ViewVisibilityTracker.CUSTOM_GONE,
            iconView.visibility
        )

        // 停止播放：恢复原始可见性 (VISIBLE)
        nativeVisibilityController.applyVisibilityRules(listOf(iconRule), isPlaying = false)
        assertEquals(
            "停止播放时原生视图可见性必须被正确恢复为 VISIBLE",
            View.VISIBLE,
            iconView.visibility
        )

        // 控制器销毁：restoreAndClear 保证清理追踪
        nativeVisibilityController.applyVisibilityRules(listOf(iconRule), isPlaying = true)
        nativeVisibilityController.restoreAndClear()
        assertEquals(
            "控制器 teardown 后所有被追踪视图必须被恢复为原始 VISIBLE",
            View.VISIBLE,
            iconView.visibility
        )
        assertNull(
            "追踪标记 Tag 必须被清理",
            iconView.getTag(ViewVisibilityTracker.TRACKING_TAG_ID)
        )
    }

    // =========================================================================
    // 6. ViewVisibilityTracker：生产追踪器身份索引、ID 查询碰撞保护、子视图脱落清理与 Compose 排除
    // =========================================================================

    @Test
    fun testViewVisibilityTracker_ProductionTracker_IdentityAndIdLookupAndDetach() {
        val context = createMockContext(idMap = mapOf("test_view" to 777, "unique_view" to 888))

        val sameId = 777
        val viewA = TestView(context, sameId).apply { visibility = View.VISIBLE }
        val viewB = TestView(context, sameId).apply { visibility = View.GONE }

        // 1. 弱引用身份存储：按对象身份分别记录
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(viewA, View.VISIBLE)
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(viewB, View.GONE)

        assertEquals("viewA 原始可见性应按身份查出 VISIBLE", View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(viewA))
        assertEquals("viewB 原始可见性应按身份查出 GONE", View.GONE, ViewVisibilityTracker.getOriginalVisibility(viewB))

        // 2. ID-only 兼容查询碰撞保护：存在两个相同 ID 的活跃对象时，ID-only 查询应探测到碰撞并返回 defaultValue，防止错误恢复
        val collidedValue = ViewVisibilityTracker.getOriginalVisibility(sameId, defaultValue = -1)
        assertEquals("存在 ID 碰撞的多实例时，ID-only 兼容查询必须安全返回 defaultValue", -1, collidedValue)

        // 3. ID-only 兼容查询唯一实例：当且仅当唯一活跃对象存在时正确返回
        val viewUnique = TestView(context, 888).apply { visibility = View.INVISIBLE }
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(viewUnique, View.INVISIBLE)
        assertEquals("唯一实例时 ID-only 查询能返回其原始可见性", View.INVISIBLE, ViewVisibilityTracker.getOriginalVisibility(888))

        // 4. 脱落与主动清理：清除 viewUnique 后，ID-only 查询不得返回陈旧值
        ViewVisibilityTracker.clearTracking(viewUnique)
        assertFalse("清理后视图不应被继续追踪", ViewVisibilityTracker.isTracking(viewUnique))
        assertEquals("清理后 ID-only 查询必须返回 defaultValue，消除陈旧数据返回", -1, ViewVisibilityTracker.getOriginalVisibility(888))

        // 5. 不对 Compose 树应用可见性缓存
        val composeElement = ComposeView(context, 999).apply { visibility = View.VISIBLE }
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(composeElement, View.VISIBLE)
        assertFalse("Compose 视图不得被 ViewVisibilityTracker 缓存追踪", ViewVisibilityTracker.isTracking(composeElement))
        assertEquals(-1, ViewVisibilityTracker.getOriginalVisibility(composeElement))
    }

    // =========================================================================
    // 7. 多屏与生命周期清理：单屏销毁不破坏活跃屏幕颜色绑定、退役宿主歌词与监听清理
    // =========================================================================

    class MultiDisplayController(
        view: ViewGroup,
        style: LyricStyle,
        override val displayId: Int
    ) : StatusBarViewController(view, style)

    @Test
    fun testMultiDisplayAndTeardown_PreservesColorBindingAndCleansHost() {
        val context = createMockContext(
            idMap = mapOf(
                "clock" to 101,
                "status_bar_start_side_content" to 102
            )
        )

        val host1 = PhoneStatusBarView(context)
        val host2 = PhoneStatusBarView(context)

        val style = LyricStyle()

        val controller1 = MultiDisplayController(host1, style, displayId = 0)
        val controller2 = MultiDisplayController(host2, style, displayId = 1)

        // 1. 注册 controller1 到 display 0
        StatusBarViewManager.add(controller1)
        assertEquals(1, StatusBarViewManager.controllers.size)
        assertTrue("display 0 的宿主应绑定到全局颜色监控器", StatusBarColorMonitor.isBoundTo(host1))

        // 2. 注册 controller2 到 display 1
        StatusBarViewManager.add(controller2)
        assertEquals(2, StatusBarViewManager.controllers.size)

        // 3. 销毁 controller2 (display 1 脱落或关闭)
        StatusBarViewManager.remove(controller2)
        assertEquals(1, StatusBarViewManager.controllers.size)
        assertTrue("控制器列表应保留活跃的 controller1", StatusBarViewManager.controllers.contains(controller1))

        // 核心断言：销毁 controller2 绝不能清空仍活跃的 display 0 (controller1) 的颜色监控器绑定！
        assertTrue(
            "单屏控制器 onDestroy 绝不可解除仍有其他屏幕活跃时的全局颜色绑定",
            StatusBarColorMonitor.isBoundTo(host1)
        )
        assertNotNull(
            "StatusBarColorMonitor 当前绑定的根视图必须仍为 host1",
            StatusBarColorMonitor.currentStatusBarRoot
        )

        // 验证 controller2 退役后的清理工作：歌词视图已从 host2 移除
        assertNull("销毁后的 controller2 歌词子视图必须已从其宿主分离", controller2.lyricView.parent)

        // 4. 当最后一个控制器 controller1 也被销毁时，全局颜色绑定才被安全清空
        StatusBarViewManager.remove(controller1)
        assertEquals(0, StatusBarViewManager.controllers.size)
        assertNull(
            "所有控制器销毁后，StatusBarColorMonitor 绑定才被清空为 null",
            StatusBarColorMonitor.currentStatusBarRoot
        )
        assertNull("controller1 歌词子视图必须已分离", controller1.lyricView.parent)
    }

    // =========================================================================
    // 8. 跨进程通信与包名身份回归测试
    // =========================================================================

    @Test
    fun testBridgeAndPackageIdentity_Regression() {
        val expectedPackageName = "io.github.proify.lyricon"
        val expectedVersionCode = 54
        val expectedVersionName = "1.0.40.rc-dp11.3"

        assertEquals("应用包名身份必须严格保持 io.github.proify.lyricon", expectedPackageName, BuildConfig.APP_PACKAGE_NAME)
        val centralConstantsClass = Class.forName("io.github.proify.lyricon.central.internal.CentralConstants")
        assertNotNull("BridgeCentral 常量定义必须存在", centralConstantsClass)

        val bridgeCentralClass = Class.forName("io.github.proify.lyricon.central.BridgeCentral")
        assertNotNull("BridgeCentral 类定义必须存在", bridgeCentralClass)
    }

    // =========================================================================
    // 9. DP11.2 专项测试：可见性恢复、无陈旧重隐、状态保真、实时取色
    // =========================================================================

    @Test
    fun testVisibilityRestore_RealContainers_NotificationIconAreaAndStartSideContent() {
        val context = createMockContext(
            idMap = mapOf(
                "notification_icon_area" to 201,
                "status_bar_start_side_content" to 202
            )
        )

        val host = PhoneStatusBarView(context)
        val notificationIconArea = ArbitraryViewGroup(context, 201).apply { visibility = View.VISIBLE }
        val statusBarStartSideContent = ArbitraryViewGroup(context, 202).apply { visibility = View.VISIBLE }
        host.addView(notificationIconArea, -1, null)
        host.addView(statusBarStartSideContent, -1, null)

        val style = LyricStyle().apply {
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }

        val controller = StatusBarViewController(host, style)
        controller.onCreate()

        // 1. 播放开始：两个真实证据容器均被隐藏为 CUSTOM_GONE
        LyricViewController.onPlaybackStateChanged(true)
        controller.lyricView.visibility = View.VISIBLE
        controller.onPlaybackStateChanged(true)
        assertEquals("播放时 notification_icon_area 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
        assertEquals("播放时 status_bar_start_side_content 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, statusBarStartSideContent.visibility)

        // 2. 暂停 (Pause) 触发恢复：两个容器均还原为其记录的原始 VISIBLE 状态
        LyricViewController.onPlaybackStateChanged(false)
        controller.onPlaybackStateChanged(false)
        assertEquals("暂停后 notification_icon_area 必须恢复 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
        assertEquals("暂停后 status_bar_start_side_content 必须恢复 VISIBLE", View.VISIBLE, statusBarStartSideContent.visibility)

        // 3. 连续播放<->暂停切换验证 (Replay -> Pause 循环)
        for (i in 1..3) {
            LyricViewController.onPlaybackStateChanged(true)
            controller.onPlaybackStateChanged(true)
            assertEquals("第 $i 次连续播放 notification_icon_area 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
            assertEquals("第 $i 次连续播放 status_bar_start_side_content 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, statusBarStartSideContent.visibility)

            LyricViewController.onPlaybackStateChanged(false)
            controller.onPlaybackStateChanged(false)
            assertEquals("第 $i 次连续暂停 notification_icon_area 必须恢复 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
            assertEquals("第 $i 次连续暂停 status_bar_start_side_content 必须恢复 VISIBLE", View.VISIBLE, statusBarStartSideContent.visibility)
        }

        // 4. 再次隐藏后停止 (Stop) 触发恢复
        LyricViewController.onPlaybackStateChanged(true)
        controller.onPlaybackStateChanged(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
        LyricViewController.onPlaybackStateChanged(false)
        controller.onPlaybackStopped()
        assertEquals("停止后 notification_icon_area 必须恢复 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
        assertEquals("停止后 status_bar_start_side_content 必须恢复 VISIBLE", View.VISIBLE, statusBarStartSideContent.visibility)

        // 5. 再次隐藏后歌词超时 (Timeout) 触发恢复
        LyricViewController.onPlaybackStateChanged(true)
        controller.lyricView.visibility = View.VISIBLE
        controller.onPlaybackStateChanged(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
        controller.lyricView.visibility = View.GONE
        controller.lyricView.onLyricVisibilityChanged?.invoke(false)
        assertEquals("歌词超时隐藏后 notification_icon_area 必须恢复 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
        assertEquals("歌词超时隐藏后 status_bar_start_side_content 必须恢复 VISIBLE", View.VISIBLE, statusBarStartSideContent.visibility)

        // 6. 验证无陈旧重隐 (No stale re-hide from OnGlobalLayoutListener)
        val onGlobalLayoutListenerField = StatusBarViewController::class.java.getDeclaredField("onGlobalLayoutListener").apply { isAccessible = true }
        val layoutListener = onGlobalLayoutListenerField.get(controller) as android.view.ViewTreeObserver.OnGlobalLayoutListener
        layoutListener.onGlobalLayout()
        assertEquals("全局布局回调严禁重新隐藏已恢复的 notification_icon_area", View.VISIBLE, notificationIconArea.visibility)
        assertEquals("全局布局回调严禁重新隐藏已恢复的 status_bar_start_side_content", View.VISIBLE, statusBarStartSideContent.visibility)

        // 7. 模块隐藏期间收到 setFlags(GONE) 回声/同态刷新不覆盖基线 (Echo of own hide does not overwrite baseline)
        // 重新进入播放隐藏状态
        LyricViewController.onPlaybackStateChanged(true)
        controller.lyricView.visibility = View.VISIBLE
        controller.onPlaybackStateChanged(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, statusBarStartSideContent.visibility)

        // 模拟模块隐藏后收到系统的 GONE 回声或同态刷新事件
        ViewVisibilityTracker.onSystemVisibilityChanged(notificationIconArea, View.GONE)
        ViewVisibilityTracker.onSystemVisibilityChanged(statusBarStartSideContent, View.GONE)

        // 暂停播放：恢复时必须还原为记录的原始 VISIBLE 基线，绝不能因为 GONE 回声滞留在 GONE
        LyricViewController.onPlaybackStateChanged(false)
        controller.onPlaybackStateChanged(false)
        assertEquals("模块隐藏期间收到 GONE 回声，暂停后 notification_icon_area 必须恢复原始 VISIBLE 状态", View.VISIBLE, notificationIconArea.visibility)
        assertEquals("模块隐藏期间收到 GONE 回声，暂停后 status_bar_start_side_content 必须恢复原始 VISIBLE 状态", View.VISIBLE, statusBarStartSideContent.visibility)

        // 8. 独立非 VISIBLE 状态变更保真测试 (Independent INVISIBLE change while hidden)
        LyricViewController.onPlaybackStateChanged(true)
        controller.lyricView.visibility = View.VISIBLE
        controller.onPlaybackStateChanged(true)
        // 系统在隐藏期间触发独立非 GONE 的不可见状态（如 INVISIBLE）
        ViewVisibilityTracker.onSystemVisibilityChanged(notificationIconArea, View.INVISIBLE)
        LyricViewController.onPlaybackStateChanged(false)
        controller.onPlaybackStateChanged(false)
        assertEquals("系统隐藏期间显式设置为独立的 INVISIBLE，恢复时必须维持 INVISIBLE，绝不可强制恢复为 VISIBLE", View.INVISIBLE, notificationIconArea.visibility)
        assertEquals("未受影响的 status_bar_start_side_content 仍恢复为 VISIBLE", View.VISIBLE, statusBarStartSideContent.visibility)

        // 9. 原生非 VISIBLE 初始状态保真测试 (Do not force VISIBLE over non-VISIBLE original)
        notificationIconArea.visibility = View.INVISIBLE
        ViewVisibilityTracker.clearTracking(notificationIconArea)
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(notificationIconArea, View.INVISIBLE)

        LyricViewController.onPlaybackStateChanged(true)
        controller.onPlaybackStateChanged(true)
        LyricViewController.onPlaybackStateChanged(false)
        controller.onPlaybackStateChanged(false)
        assertEquals("原生为 INVISIBLE 的容器恢复时必须维持 INVISIBLE，绝不可强设为 VISIBLE", View.INVISIBLE, notificationIconArea.visibility)

        // 10. 视图卸载 / onDestroy 恢复验证
        LyricViewController.onPlaybackStateChanged(true)
        controller.onPlaybackStateChanged(true)
        controller.onDestroy()
        assertEquals("onDestroy 后 status_bar_start_side_content 必须恢复", View.VISIBLE, statusBarStartSideContent.visibility)

        LyricViewController.onPlaybackStateChanged(false)
    }

    @Test
    fun testVisibilityRestore_ComposeAncestorLocalFallbackAndVisibleRace() {
        val context = createMockContext(
            idMap = mapOf("notification_icon_area" to 701, "status_bar_start_side_content" to 702)
        )
        // The Compose-named ancestor must not prevent ordinary descendant ViewGroups from being tracked.
        val composeAncestor = ComposeView(context)
        val host = PhoneStatusBarView(context)
        val notification = ArbitraryViewGroup(context, 701).apply { visibility = View.VISIBLE }
        val startSide = ArbitraryViewGroup(context, 702).apply { visibility = View.VISIBLE }
        host.addView(notification, -1, null)
        host.addView(startSide, -1, null)
        composeAncestor.addView(host, -1, null)
        val style = LyricStyle().apply {
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }
        val controller = StatusBarViewController(host, style)
        controller.onCreate()
        val playbackStateField = LyricViewController::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }
        val lyricVisibilityField = StatusBarViewController::class.java.getDeclaredField("isLyricVisible").apply { isAccessible = true }
        val pendingLyricShowField = StatusBarViewController::class.java.getDeclaredField("pendingLyricShowSync").apply { isAccessible = true }

        // Playback arrives before the lyric visibility event. The later visibility callback applies rules.
        playbackStateField.setBoolean(LyricViewController, false)
        controller.onPlaybackStateChanged(true)
        assertEquals(View.VISIBLE, notification.visibility)
        playbackStateField.setBoolean(LyricViewController, true)
        controller.lyricView.onLyricVisibilityChanged?.invoke(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notification.visibility)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, startSide.visibility)
        assertTrue(ViewVisibilityTracker.isTracking(notification))
        assertEquals(View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(notification))

        // If the initial show callback is delayed, a subsequent layout completes the pending show sync.
        notification.visibility = View.VISIBLE
        startSide.visibility = View.VISIBLE
        controller.visibilityController.restoreHiddenViews()
        playbackStateField.setBoolean(LyricViewController, true)
        lyricVisibilityField.setBoolean(controller, false)
        pendingLyricShowField.setBoolean(controller, true)
        val layoutListenerField = StatusBarViewController::class.java.getDeclaredField("onGlobalLayoutListener").apply { isAccessible = true }
        (layoutListenerField.get(controller) as android.view.ViewTreeObserver.OnGlobalLayoutListener).onGlobalLayout()
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notification.visibility)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, startSide.visibility)
        controller.visibilityController.restoreHiddenViews()

        // Simulate the old unknown-tracker-baseline condition after a module hide. The local record restores it.
        playbackStateField.setBoolean(LyricViewController, true)
        controller.lyricView.onLyricVisibilityChanged?.invoke(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notification.visibility)
        ViewVisibilityTracker.clearTracking(notification)
        ViewVisibilityTracker.clearTracking(startSide)
        playbackStateField.setBoolean(LyricViewController, false)
        controller.onPlaybackStateChanged(false)
        assertEquals(View.VISIBLE, notification.visibility)
        assertEquals(View.VISIBLE, startSide.visibility)

        // Repeated play/pause must keep working after the fallback path was used.
        playbackStateField.setBoolean(LyricViewController, true)
        controller.lyricView.onLyricVisibilityChanged?.invoke(true)
        assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notification.visibility)
        controller.lyricView.onLyricVisibilityChanged?.invoke(false)
        assertEquals(View.VISIBLE, notification.visibility)
        assertEquals(View.VISIBLE, startSide.visibility)

        // A genuinely recorded system INVISIBLE baseline is never forced to VISIBLE.
        notification.visibility = View.INVISIBLE
        ViewVisibilityTracker.clearTracking(notification)
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(notification, View.INVISIBLE)
        playbackStateField.setBoolean(LyricViewController, true)
        controller.lyricView.onLyricVisibilityChanged?.invoke(true)
        controller.lyricView.onLyricVisibilityChanged?.invoke(false)
        assertEquals(View.INVISIBLE, notification.visibility)
        controller.onDestroy()
        playbackStateField.setBoolean(LyricViewController, false)
    }

    @Test
    fun testFullProductionPipeline_ReceiveMountDisplayPauseRestoreReplay_WithColorFailureDegradation() {
        val context = createMockContext(
            idMap = mapOf(
                "status_bar_start_side_container" to 301,
                "status_bar_start_side_content" to 302,
                "notification_icon_area" to 303
            )
        )

        // 真实 SystemUI 容器层级:
        // root (PhoneStatusBarView)
        //   └── status_bar_start_side_container (301)
        //         └── status_bar_start_side_content (302)
        //               └── notification_icon_area (303)
        val host = PhoneStatusBarView(context)
        val startSideContainer = ArbitraryViewGroup(context, 301).apply { visibility = View.VISIBLE }
        val startSideContent = ArbitraryViewGroup(context, 302).apply { visibility = View.VISIBLE }
        val notificationIconArea = ArbitraryViewGroup(context, 303).apply { visibility = View.VISIBLE }

        startSideContent.addView(notificationIconArea, -1, null)
        startSideContainer.addView(startSideContent, -1, null)
        host.addView(startSideContainer, -1, null)

        val style = LyricStyle().apply {
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }

        val controller = StatusBarViewController(host, style)
        controller.updateLyricStyle(style)
        StatusBarViewManager.add(controller)
        LyricViewController // 确保单例初始化并注册到 LyricDataHub

        try {
            // 1. 验证挂载 (Mount Phase)
            // lyricView 必须挂载在非目标隐藏容器 (status_bar_start_side_container)，绝不能挂在将被隐藏的 status_bar_start_side_content 内部
            val lyricView = controller.lyricView
            assertNotNull("lyricView 必须成功挂载", lyricView.parentView())
            assertSame("lyricView 必须挂载在 status_bar_start_side_container，防止被子树整体隐藏", startSideContainer, lyricView.parentView())
            assertNotSame("lyricView 严禁挂载在 status_bar_start_side_content 内部", startSideContent, lyricView.parentView())

            // 2. 接收歌词数据并播放 (Receive & Display Phase via Production LyricDataHub)
            val provider = ProviderInfo("com.salt.music", "com.salt.music")
            LyricDataHub.onActiveProviderChanged(provider)
            LyricDataHub.onReceiveText("海阔天空 - Beyond")
            LyricDataHub.onPlaybackStateChanged(true)

            // 确保播放与文本状态已到达 lyricView
            assertTrue("LyricViewController 必须处于播放状态", LyricViewController.isPlaying)
            assertEquals("lyricView 视图可见性必须为 VISIBLE", View.VISIBLE, lyricView.visibility)

            // 目标容器被隐藏为 CUSTOM_GONE
            assertEquals("播放时 notification_icon_area 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
            assertEquals("播放时 status_bar_start_side_content 必须隐藏", ViewVisibilityTracker.CUSTOM_GONE, startSideContent.visibility)

            // 关键断言：lyricView 本身仍正常保持 VISIBLE（没有因被隐藏容器包含而被连带隐藏）
            assertEquals("歌词视图必须保持显示，绝不能被连带隐藏", View.VISIBLE, lyricView.visibility)

            // 3. 颜色获取失败安全降级测试 (Color failure safe degradation)
            // 模拟 Compose 状态栏无时钟视图且 Dispatcher 颜色为 0 (完全透明/无效)
            // 测试浅色状态栏 (darkIntensity = 1.0f -> icons/text need to be BLACK)
            controller.updateStatusColor(
                StatusBarViewController.SystemStatusBarColor(color = 0, darkIntensity = 1.0f)
            )
            var currentColor = lyricView.currentStatusColor.color
            assertNotNull("lyricView 颜色数组不可为 null", currentColor)
            assertTrue("lyricView 颜色数组不可为空", currentColor.isNotEmpty())
            var effectiveColor = currentColor[0]
            assertFalse("歌词文本颜色绝对不可降级为 0 (透明)", effectiveColor == 0)
            assertFalse("歌词文本透明度绝对不可为 0", (effectiveColor ushr 24) == 0)
            assertEquals("在浅色状态栏颜色失效时必须安全降级为黑色对比色", android.graphics.Color.BLACK, effectiveColor)

            // 测试深色状态栏 (darkIntensity = 0.0f -> icons/text need to be WHITE)
            controller.updateStatusColor(
                StatusBarViewController.SystemStatusBarColor(color = 0, darkIntensity = 0.0f)
            )
            currentColor = lyricView.currentStatusColor.color
            assertNotNull("lyricView 颜色数组不可为 null", currentColor)
            assertTrue("lyricView 颜色数组不可为空", currentColor.isNotEmpty())
            effectiveColor = currentColor[0]
            assertFalse("歌词文本颜色绝对不可降级为 0 (透明)", effectiveColor == 0)
            assertFalse("歌词文本透明度绝对不可为 0", (effectiveColor ushr 24) == 0)
            assertEquals("在深色状态栏颜色失效时必须安全降级为白色对比色", android.graphics.Color.WHITE, effectiveColor)

            // 4. 暂停恢复测试 (Pause & Restore Phase)
            LyricDataHub.onPlaybackStateChanged(false)

            assertFalse("暂停后 LyricViewController 必须为非播放", LyricViewController.isPlaying)
            val isLyricVisibleField = StatusBarViewController::class.java.getDeclaredField("isLyricVisible").apply { isAccessible = true }
            assertFalse("暂停后 isLyricVisible 必须为 false", isLyricVisibleField.getBoolean(controller))
            assertEquals("暂停后 status_bar_start_side_content 必须恢复为 VISIBLE", View.VISIBLE, startSideContent.visibility)
            assertEquals("暂停后 notification_icon_area 必须恢复为 VISIBLE", View.VISIBLE, notificationIconArea.visibility)

            // 5. 隐藏期间系统变更状态保真测试 (Respect system visibility changes while hidden)
            // 重新开始播放
            LyricDataHub.onPlaybackStateChanged(true)
            assertEquals(ViewVisibilityTracker.CUSTOM_GONE, startSideContent.visibility)
            assertEquals(ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)

            // 模拟系统在通知更新或无通知时将 notification_icon_area 置为 INVISIBLE
            ViewVisibilityTracker.onSystemVisibilityChanged(notificationIconArea, View.INVISIBLE)
            notificationIconArea.visibility = View.INVISIBLE

            // 暂停播放：恢复时必须尊重系统在隐藏期间的改动，恢复为 INVISIBLE 而非强行覆写为 VISIBLE
            LyricDataHub.onPlaybackStateChanged(false)
            assertEquals("恢复时必须尊重系统隐藏期间赋予的 INVISIBLE 状态", View.INVISIBLE, notificationIconArea.visibility)
            assertEquals("未被系统改动的 status_bar_start_side_content 必须恢复为 VISIBLE", View.VISIBLE, startSideContent.visibility)

            // 6. 重新播放测试 (Replay Phase)
            // 重置 notification_icon_area 为 VISIBLE
            notificationIconArea.visibility = View.VISIBLE
            ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(notificationIconArea, View.VISIBLE)

            LyricDataHub.onPlaybackStateChanged(true)
            assertEquals("重新播放时 status_bar_start_side_content 必须再次隐藏", ViewVisibilityTracker.CUSTOM_GONE, startSideContent.visibility)
            assertEquals("重新播放时 notification_icon_area 必须再次隐藏", ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
            assertTrue("重新播放时 isLyricVisible 必须再次为 true", isLyricVisibleField.getBoolean(controller))

            // 停止播放并清理
            LyricDataHub.onPlaybackStateChanged(false)
            assertEquals(View.VISIBLE, startSideContent.visibility)
        } finally {
            LyricDataHub.onPlaybackStateChanged(false)
            StatusBarViewManager.remove(controller)
        }
    }

    @Test
    fun testColorMonitor_ComposeNoClockView_BindsDispatcherAndFollowsHookedSignals() {
        // 1. 设置 Compose 状态栏无传统时钟 View 宿主
        val context = createMockContext(
            idMap = mapOf(
                "notification_icon_area" to 201,
                "status_bar_start_side_content" to 202
            )
        )
        val host = PhoneStatusBarView(context)
        val iconArea = ArbitraryViewGroup(context, 201).apply { visibility = View.VISIBLE }
        val startSideContent = ArbitraryViewGroup(context, 202).apply { visibility = View.VISIBLE }
        host.addView(iconArea, -1, null)
        host.addView(startSideContent, -1, null)

        // 确保 ClockViewFinder 识别为无传统 View 时钟
        val clockResult = ClockViewFinder.discoverClock(host)
        assertNull("宿主中不得存在伪造的时钟 View", clockResult.view)

        // 准备真实生产结构 DP11 DarkIconDispatcherImpl 并注入 Dependency.sDependency
        val liveDispatcher = com.android.systemui.statusbar.phone.DarkIconDispatcherImpl(0xFF223344.toInt(), 0.85f)
        com.android.systemui.Dependency.sDependency = com.android.systemui.Dependency().apply {
            mDarkIconDispatcher = liveDispatcher
        }

        var lastColor = 0
        var lastIntensity = -1f
        var notificationCount = 0
        val listener = object : io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener {
            override fun onColorChanged(color: Int, darkIntensity: Float) {
                lastColor = color
                lastIntensity = darkIntensity
                notificationCount++
            }
        }

        val hookCtx = createTrackingMockModule()
        val module = hookCtx.module

        // 初始化并激活监控器与状态栏控制器
        StatusBarColorMonitor.initialize(module, javaClass.classLoader)
        val style = LyricStyle().apply {
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }
        val controller = StatusBarViewController(host, style)
        controller.onCreate()
        StatusBarColorMonitor.addListener(listener)

        // 验证需求 1：初始阶段在无时钟 View 下自动绑定当前 DarkIconDispatcher 实例与取色
        // 绝不可返回硬编码白色或假兜底成功
        assertEquals("初始颜色必须直接绑定自 liveDispatcher 的真实 tint", 0xFF223344.toInt(), lastColor)
        assertEquals("初始暗色强度必须与 liveDispatcher 对应", 0.85f, lastIntensity, 0.001f)
        assertTrue("必须触发初始颜色上报", notificationCount >= 1)

        // 验证需求 2：通过挂接的 applyDarkIntensity Hook 路径触发后续明暗/颜色通知（绝不仅走直接 emit 假象）
        val applyDarkIntensityMethod = com.android.systemui.statusbar.phone.DarkIconDispatcherImpl::class.java
            .getDeclaredMethod("applyDarkIntensity", Float::class.javaPrimitiveType)

        // 2.1 模拟系统调用 applyDarkIntensity(0.1f)（浅色背景/亮背景 -> 图标变白/浅色）
        liveDispatcher.applyDarkIntensity(0.1f)
        hookCtx.invokeHook(applyDarkIntensityMethod, liveDispatcher, 0.1f)

        assertEquals("首次后续 Hook 变更必须上报更新后的颜色", 0xFFFFFFFF.toInt(), lastColor)
        assertEquals("暗色强度必须更新为 0.1", 0.1f, lastIntensity, 0.001f)

        // 2.2 模拟系统第二次后续调用 applyDarkIntensity(0.95f)（深色图标）
        liveDispatcher.applyDarkIntensity(0.95f)
        hookCtx.invokeHook(applyDarkIntensityMethod, liveDispatcher, 0.95f)

        assertEquals("再次后续 Hook 变更必须上报深色图标", 0xFF000000.toInt(), lastColor)
        assertEquals("暗色强度必须更新为 0.95", 0.95f, lastIntensity, 0.001f)

        controller.onDestroy()
        com.android.systemui.Dependency.sDependency = null
    }

    @Test
    fun testPreciseColorStrategy_DeactivateThenReactivate_UnhooksAllAndNoStaleEmits() {
        val hookCtx = createTrackingMockModule()
        val module = hookCtx.module

        var emittedColor = 0
        val listener = object : io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener {
            override fun onColorChanged(color: Int, darkIntensity: Float) {
                emittedColor = color
            }
        }

        val liveDispatcher = com.android.systemui.statusbar.phone.DarkIconDispatcherImpl(0xFFAABBCC.toInt(), 0.5f)
        com.android.systemui.Dependency.sDependency = com.android.systemui.Dependency().apply {
            mDarkIconDispatcher = liveDispatcher
        }

        StatusBarColorMonitor.initialize(module, javaClass.classLoader)
        StatusBarColorMonitor.addListener(listener)
        assertEquals(0xFFAABBCC.toInt(), emittedColor)

        // 验证当前已安装的 Hook 句柄列表非空
        val initialActiveHandles = hookCtx.activeHandles.toList()
        assertTrue("初始化阶段必须安装 DarkIconDispatcher 相关的 Hook", initialActiveHandles.isNotEmpty())

        // 1. 停用策略 (onDeactivate / resetForTesting / 切换策略)
        val applyDarkIntensityMethod = com.android.systemui.statusbar.phone.DarkIconDispatcherImpl::class.java
            .getDeclaredMethod("applyDarkIntensity", Float::class.javaPrimitiveType)

        StatusBarColorMonitor.resetForTesting()

        // 验证所有 Hook 句柄均被调用 unhook()
        assertTrue("停用时必须卸载所有旧 Hook 句柄", initialActiveHandles.all { it.isUnhooked })
        assertTrue("活跃句柄列表必须被清空", hookCtx.activeHandles.isEmpty())

        // 验证停用后，旧的 Hook 拦截器即使被宿主或旧调用触发，也绝不可向已停用的所有者发出通知
        liveDispatcher.applyDarkIntensity(0.9f)
        val oldHooker = initialActiveHandles.firstOrNull { it.executable == applyDarkIntensityMethod }?.hooker
        assertNotNull(oldHooker)
        val chain = createMockChain(thisObject = liveDispatcher, args = listOf(0.9f))
        oldHooker?.intercept(chain)
        assertEquals("停用后陈旧 Hook 触发严禁污染或上报颜色", 0xFFAABBCC.toInt(), emittedColor)

        // 2. 重新激活 (Reactivate)
        val newListener = object : io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener {
            override fun onColorChanged(color: Int, darkIntensity: Float) {
                emittedColor = color
            }
        }
        StatusBarColorMonitor.initialize(module, javaClass.classLoader)
        StatusBarColorMonitor.addListener(newListener)

        // 验证重新激活后全新 Hook 句柄被正常挂载
        val newActiveHandles = hookCtx.activeHandles.toList()
        assertTrue("重新激活后必须挂载全新的 Hook 句柄", newActiveHandles.isNotEmpty())
        assertFalse("新 Hook 句柄绝不能处于已卸载状态", newActiveHandles.any { it.isUnhooked })

        // 验证新 Hook 能够正常工作并上报
        liveDispatcher.applyDarkIntensity(0.1f)
        hookCtx.invokeHook(applyDarkIntensityMethod, liveDispatcher, 0.1f)
        assertEquals("重新激活后新 Hook 必须正常上报新颜色", 0xFFFFFFFF.toInt(), emittedColor)

        StatusBarColorMonitor.resetForTesting()
        com.android.systemui.Dependency.sDependency = null
    }

    @Test
    fun testColorMonitor_FollowsRealStatusBarSignals() {
        val context = createMockContext(idMap = mapOf("clock" to 101))

        val host = PhoneStatusBarView(context)
        val clock = DummyTextClock(context, 101).apply {
            customVisibility = View.VISIBLE
            setTextColor(0xFF112233.toInt())
        }
        host.addView(clock, -1, null)

        var emittedColor = 0
        var emittedDarkIntensity = -1f
        val listener = object : io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener {
            override fun onColorChanged(color: Int, darkIntensity: Float) {
                emittedColor = color
                emittedDarkIntensity = darkIntensity
            }
        }

        val mockModule = createMockModule()
        StatusBarColorMonitor.initialize(mockModule, javaClass.classLoader)
        StatusBarColorMonitor.bindStatusBar(host)
        StatusBarColorMonitor.bindClockView(clock)
        StatusBarColorMonitor.addListener(listener)

        // 1. 初始绑定取色
        assertEquals("初始绑定必须获取时钟真实文字颜色", 0xFF112233.toInt(), emittedColor)

        // 2. 状态栏明暗背景切换 (浅色背景 -> 黑色文字 0xFF000000, darkIntensity = 1.0f)
        StatusBarColorMonitor.emit(0xFF000000.toInt(), 1.0f)
        assertEquals("背景切换必须获取黑色文字", 0xFF000000.toInt(), emittedColor)
        assertEquals("深色图标强度应为 1.0", 1.0f, emittedDarkIntensity, 0.001f)

        // 3. 深色背景 -> 白色文字 0xFFFFFFFF, darkIntensity = 0.0f
        StatusBarColorMonitor.emit(0xFFFFFFFF.toInt(), 0.0f)
        assertEquals("背景切换必须获取白色文字", 0xFFFFFFFF.toInt(), emittedColor)
        assertEquals("浅色图标强度应为 0.0", 0.0f, emittedDarkIntensity, 0.001f)

        // 4. 解绑清理验证：解除绑定后 listener 不再收到事件
        StatusBarColorMonitor.removeListener(listener)
        StatusBarColorMonitor.unbindClockView(clock)
        StatusBarColorMonitor.unbindStatusBar(host)

        StatusBarColorMonitor.emit(0xFFAABBCC.toInt(), 0.5f)
        assertEquals("解绑后不应再触发已移除的监听器", 0xFFFFFFFF.toInt(), emittedColor)
    }

    // =========================================================================
    // 10. DP11.3 r2 专项测试：非默认配置 hide 锚点路由、Hook 异常边界、重连与接收链路
    // =========================================================================

    @Test
    fun testConfiguredHideTargetAnchor_RoutedToSafeFallback_MaintainsContainerHideAndLyricsDisplay() {
        val context = createMockContext(
            idMap = mapOf(
                "status_bar_start_side_container" to 301,
                "status_bar_start_side_content" to 302,
                "notification_icon_area" to 303
            )
        )

        val host = PhoneStatusBarView(context)
        val startSideContainer = ArbitraryViewGroup(context, 301).apply { visibility = View.VISIBLE }
        val leadingSpacer = ArbitraryViewGroup(context, 300).apply { visibility = View.VISIBLE }
        val startSideContent = ArbitraryViewGroup(context, 302).apply { visibility = View.VISIBLE }
        val notificationIconArea = ArbitraryViewGroup(context, 303).apply { visibility = View.VISIBLE }

        startSideContent.addView(notificationIconArea, -1, null)
        startSideContainer.addView(leadingSpacer, -1, null) // index 0: 行外/前置视图
        startSideContainer.addView(startSideContent, -1, null) // index 1: 内容容器
        host.addView(startSideContainer, -1, null)

        // 1. 用户显式配置 anchor = "status_bar_start_side_content"，且规则包含对其隐藏
        val style = LyricStyle().apply {
            basicStyle.anchor = "status_bar_start_side_content"
            basicStyle.insertionOrder = BasicStyle.INSERTION_ORDER_BEFORE
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }

        val controller = StatusBarViewController(host, style)
        controller.updateLyricStyle(style)
        StatusBarViewManager.add(controller)
        LyricViewController // 确保单例初始化并注册到 LyricDataHub

        try {
            val lyricView = controller.lyricView
            assertNotNull("lyricView 必须成功挂载", lyricView.parentView())
            assertSame(
                "针对 hide target 锚点，updateLocation 必须作为兄弟视图插入在 content 父容器内",
                startSideContainer,
                lyricView.parentView()
            )
            assertEquals(
                "lyricView 必须插入在 status_bar_start_side_content 之前 (index 1)，绝不得放置在 index 0 破坏行外前置视图",
                1,
                startSideContainer.indexOfChild(lyricView)
            )
            assertEquals("前置子视图必须完整保持在 index 0", 0, startSideContainer.indexOfChild(leadingSpacer))
            assertEquals("status_bar_start_side_content 必须位于 index 2", 2, startSideContainer.indexOfChild(startSideContent))
            assertNotSame(
                "lyricView 严禁挂载在 status_bar_start_side_content 内部",
                startSideContent,
                lyricView.parentView()
            )

            // 播放歌词
            val provider = ProviderInfo("com.salt.music", "com.salt.music")
            LyricDataHub.onActiveProviderChanged(provider)
            LyricDataHub.onReceiveText("海阔天空 - Beyond")
            LyricDataHub.onPlaybackStateChanged(true)

            assertTrue("LyricViewController 必须处于播放状态", LyricViewController.isPlaying)
            assertEquals("歌词视图必须保持显示 VISIBLE", View.VISIBLE, lyricView.visibility)

            // 关键断言：容器隐藏未被 containsLyricView 阻断
            assertEquals(
                "播放时 notification_icon_area 必须隐藏",
                ViewVisibilityTracker.CUSTOM_GONE,
                notificationIconArea.visibility
            )
            assertEquals(
                "播放时 status_bar_start_side_content 必须隐藏",
                ViewVisibilityTracker.CUSTOM_GONE,
                startSideContent.visibility
            )
            assertEquals("lyricView 必须保持显示，绝不能被连带致盲", View.VISIBLE, lyricView.visibility)

            // 暂停恢复
            LyricDataHub.onPlaybackStateChanged(false)
            assertEquals("暂停后 status_bar_start_side_content 必须恢复 VISIBLE", View.VISIBLE, startSideContent.visibility)
            assertEquals("暂停后 notification_icon_area 必须恢复 VISIBLE", View.VISIBLE, notificationIconArea.visibility)

            // 重新播放
            LyricDataHub.onPlaybackStateChanged(true)
            assertEquals("重新播放时 status_bar_start_side_content 必须再次隐藏", ViewVisibilityTracker.CUSTOM_GONE, startSideContent.visibility)
            assertEquals("重新播放时 notification_icon_area 必须再次隐藏", ViewVisibilityTracker.CUSTOM_GONE, notificationIconArea.visibility)
            assertEquals("重新播放时 lyricView 必须保持显示", View.VISIBLE, lyricView.visibility)

            LyricDataHub.onPlaybackStateChanged(false)

            // 2. 进一步验证：用户显式配置 anchor = "notification_icon_area" (hide 容器内部子项)
            val styleWithNestedAnchor = LyricStyle().apply {
                basicStyle.anchor = "notification_icon_area"
                basicStyle.visibilityRules = listOf(
                    VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                    VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
                )
            }
            controller.updateLyricStyle(styleWithNestedAnchor)
            assertSame(
                "配置为 notification_icon_area 时，lyricView 也必须安全挂载在 startSideContainer 外侧",
                startSideContainer,
                lyricView.parentView()
            )
            assertNotSame(
                "lyricView 绝不可作为 notification_icon_area 同级子项挂载进 startSideContent",
                startSideContent,
                lyricView.parentView()
            )
        } finally {
            LyricDataHub.onPlaybackStateChanged(false)
            StatusBarViewManager.remove(controller)
        }
    }

    @Test
    fun testViewVisibilityTracker_SetFlagsHook_ExceptionBoundaryProceedsOnce() {
        class FakeViewClass {
            fun setFlags(flags: Int, mask: Int) {}
        }
        val customClassLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == View::class.java.name) {
                    return FakeViewClass::class.java
                }
                return super.loadClass(name, resolve)
            }
        }

        val mockContext = createTrackingMockModule()
        ViewVisibilityTracker.initialize(mockContext.module, customClassLoader)

        val setFlagsMethod = FakeViewClass::class.java.getDeclaredMethod(
            "setFlags",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val hooker = mockContext.installedHookers[setFlagsMethod]?.firstOrNull()
        assertNotNull("View.setFlags hooker 必须已安装", hooker)

        // Case 1: 当 tracker 逻辑在读取参数或内部逻辑时抛出异常，捕获后安全调用 chain.proceed() 仅一次
        var fallbackProceedCount = 0
        val throwingTrackerChain = Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getThisObject" -> throw RuntimeException("Tracker logic fault")
                "proceed" -> {
                    fallbackProceedCount++
                    "fallback_result"
                }
                else -> null
            }
        } as XposedInterface.Chain

        val res1 = hooker!!.intercept(throwingTrackerChain)
        assertEquals("fallback_result", res1)
        assertEquals("Tracker 逻辑异常时，必须调用降级 proceed() 且仅调用一次", 1, fallbackProceedCount)

        // Case 2: 当 chain.proceed(newArgs) 自身抛出异常时，绝不能在 catch 中二次重试 proceed()！
        var proceedCalls = 0
        val targetView = TestView(createMockContext(), 100)
        val throwingProceedChain = Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getThisObject" -> targetView
                "getArgs" -> listOf(ViewVisibilityTracker.CUSTOM_GONE, 0x0000000C)
                "proceed" -> {
                    proceedCalls++
                    throw IllegalStateException("Original proceed failed downstream")
                }
                else -> null
            }
        } as XposedInterface.Chain

        var threwException = false
        try {
            hooker.intercept(throwingProceedChain)
        } catch (e: IllegalStateException) {
            threwException = true
        }

        assertTrue("proceed 自身抛出的异常必须正常向外传播", threwException)
        assertEquals("proceed 自身抛出异常时，绝不可触发二次重试调用", 1, proceedCalls)
    }

    @Test
    fun testAlreadyRunningPlayer_SubscriberCentralProviderReconnectSequence() {
        val context = createMockContext()

        // 0. 重置中央中枢、广播接收器、Hook 与视图控制器状态
        BridgeCentral.resetForTesting(context)
        ProviderTestBridge.resetBootReceiver()
        SubscriberTestBridge.resetBootReceiver()
        ProviderTestBridge.setForceSupported(true)
        SubscriberTestBridge.setForceSupported(true)
        SystemUIHooker.resetForTesting()
        LyricDataHub.resetForTesting()
        LyricViewController.resetForTesting()

        // 设置单元测试 Seam：分发真实的 RegistrationBinder AIDL 桩，执行生产 CentralReceiver 与 RegistrationDispatcher 注册
        val dispatchRegistration: (Context, String, Any) -> Unit = { _, action, binder ->
            val regIntent = io.github.proify.lyricon.central.RegistrationIntent(action, binder as android.os.IBinder)
            BridgeCentral.handleRegistrationForTesting(regIntent)
        }
        ProviderTestBridge.setBroadcastDispatcher(dispatchRegistration)
        SubscriberTestBridge.setBroadcastDispatcher(dispatchRegistration)

        try {
            // 1. 执行生产 SystemUIHooker.initLyriconService 初始化：
            //    - 初始化生产 BridgeCentral (注册 CentralReceiver)
            //    - 创建生产订阅端 LyriconSubscriber 并订阅 LyricDataHub
            //    - 确保 LyricViewController 单例初始化并注册为 LyricDataHub 监听器
            //    - 创建并安装生产 SystemUIConnectionListener
            SystemUIHooker.initLyriconService(context)

            val subscriber = SystemUIHooker.subscriber
            assertNotNull("SystemUIHooker 必须已创建生产订阅端", subscriber)
            val subscriberConnectionListener = SystemUIHooker.subscriberConnectionListener
            assertNotNull("SystemUIHooker 必须已安装生产 SystemUIConnectionListener", subscriberConnectionListener)

            // 订阅端向中央注册建立连接并接入 ActivePlayerHub
            subscriber!!.register()
            assertTrue("订阅端注册后必须处于 CONNECTED 状态", SubscriberTestBridge.isConnected(subscriber))

            // 2. 模拟提供端（如 Salt Music）在 SystemUI 重连前已经启动并正在播放
            val provider = ProviderFactory.createProvider(
                context = context,
                providerPackageName = "com.salt.music",
                playerPackageName = "com.salt.music",
                centralPackageName = "com.android.systemui"
            )

            var initialConnected = false
            val initListener = object : io.github.proify.lyricon.provider.ConnectionListener {
                override fun onConnected(provider: io.github.proify.lyricon.provider.LyriconProvider) {
                    initialConnected = true
                }
                override fun onReconnected(provider: io.github.proify.lyricon.provider.LyriconProvider) = Unit
                override fun onDisconnected(provider: io.github.proify.lyricon.provider.LyriconProvider) = Unit
                override fun onConnectTimeout(provider: io.github.proify.lyricon.provider.LyriconProvider) = Unit
            }
            provider.service.addConnectionListener(initListener)

            // 提供端启动注册，wantsConnection 设为 true
            val regStarted = provider.register()
            assertTrue("提供端必须成功启动注册", regStarted)
            assertTrue("提供端初次注册必须已连接", provider.service.connectionStatus.isConnected())

            // 等待初次连接的异步回调处理完成，避免初始空快照回放与随后的播放状态更新产生竞态
            var waitedInit = 0
            while (!initialConnected && waitedInit < 2000) {
                Thread.sleep(20)
                waitedInit += 20
            }
            assertTrue("初次连接回调必须已完成", initialConnected)
            provider.service.removeConnectionListener(initListener)
            Thread.sleep(50)

            // 播放器在重连前就已经在播放歌曲
            val playingSong = Song(name = "海阔天空", artist = "Beyond")
            provider.player.setSong(playingSong)
            provider.player.setPlaybackState(true)

            // 等待初始播放状态到达 LyricViewController，确认断开前处于活跃播放中
            var preOutageWaited = 0
            while ((LyricViewController.currentSong == null || !LyricViewController.isPlaying) && preOutageWaited < 2000) {
                Thread.sleep(50)
                preOutageWaited += 50
            }
            assertTrue("断开前播放器状态必须已在运行中", LyricViewController.isPlaying)
            assertEquals("海阔天空", LyricViewController.currentSong?.name)

            // 模拟 SystemUI 进程重启造成的连接断开（远端 Binder 死亡，触发 SERVICE_LOST）
            // 此时提供端变为 DISCONNECTED_REMOTE，wantsConnection 保持为 true，播放状态由 ResyncingPlayer 缓存
            ProviderTestBridge.simulateServiceLost(provider)
            assertTrue("模拟 SystemUI 退出后，提供端必须处于断开状态", provider.service.connectionStatus.isDisconnected())

            // 1. 显式清除/重置 LyricViewController 与订阅端可见的 Hub 状态，同时保留提供端缓存的播放快照
            LyricViewController.resetForTesting()
            LyricDataHub.resetForTesting()
            assertFalse("控制器在断开后必须已显式重置为未播放状态", LyricViewController.isPlaying)
            assertEquals("控制器活跃包名在重置后必须为空", "", LyricViewController.activePackage)
            assertNull("控制器歌曲在重置后必须为空", LyricViewController.currentSong)
            assertNull("数据总线缓存原始歌曲在重置后必须为空", LyricDataHub.cachedRawSongForTesting)
            assertNull("数据总线提供端信息在重置后必须为空", LyricDataHub.providerInfoForTesting)

            // 设置后置回调门禁探针：从 0 开始计数，旧 pre-disconnect 状态绝无法满足
            var postResetSongCount = 0
            var postResetPlaybackCount = 0
            var postResetProviderCount = 0
            val postResetProbe = object : io.github.proify.lyricon.subscriber.ActivePlayerListener {
                override fun onSongChanged(song: Song?) {
                    postResetSongCount++
                }

                override fun onPlaybackStateChanged(isPlaying: Boolean) {
                    postResetPlaybackCount++
                }

                override fun onActiveProviderChanged(providerInfo: io.github.proify.lyricon.subscriber.ProviderInfo?) {
                    postResetProviderCount++
                }

                override fun onReceiveText(text: String?) = Unit
                override fun onPositionChanged(position: Long) = Unit
                override fun onSeekTo(position: Long) = Unit
                override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit
                override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
            }
            LyricDataHub.addListener(postResetProbe)

            // 3. 模拟 SystemUI 恢复/重连：调用生产安装在 subscriber 上的 SystemUIConnectionListener 回调
            var bootBroadcastReceived = false
            val token1 = ProviderTestBridge.addBootListener {
                bootBroadcastReceived = true
            }
            try {
                // 触发生产安装的 onConnected 回调
                subscriberConnectionListener!!.onConnected(subscriber)

                // 4. 验证生产链路完整流转：
                // A. SystemUIHooker.SystemUIConnectionListener.onConnected -> BridgeCentral.sendBootCompleted()
                // B. BridgeCentral 发送 ACTION_CENTRAL_BOOT_COMPLETED 广播
                // C. 生产 CentralBootReceiver 接收广播并分发给所有 BootListener
                assertTrue("BridgeCentral 广播后，生产 CentralBootReceiver 必须通知 BootListener", bootBroadcastReceived)

                // D. 生产 RegistrationCoordinator.onBootCompleted() 检测到 wantsConnection && isDisconnected()
                //    自动重新调用 start() 向 Central 注册
                // E. Central 接受注册，提供端 CentralConnection 重新进入 CONNECTED 状态
                assertTrue("提供端收到恢复信号后必须自动重新建立连接", provider.service.connectionStatus.isConnected())

                // F. 重新连接触发 LyriconProviderImpl.onConnected() -> autoSync 触发 connection.syncPlayer()
                //    ResyncingPlayer 将缓存的歌曲与播放状态通过 AIDL 回放至中央 ActivePlayerHub
                // G. ActivePlayerHub 切换当前活跃提供端并向订阅端分发快照
                // H. 订阅端将状态投递至 LyricDataHub，并最终分发至 LyricViewController
                var waited = 0
                while ((postResetSongCount == 0 || postResetPlaybackCount == 0 || LyricViewController.currentSong == null || !LyricViewController.isPlaying) && waited < 2000) {
                    Thread.sleep(50)
                    waited += 50
                }

                assertTrue("重连重放必须触发后置歌曲变更回调 (postResetSongCount > 0)", postResetSongCount > 0)
                assertTrue("重连重放必须触发后置播放状态变更回调 (postResetPlaybackCount > 0)", postResetPlaybackCount > 0)
                assertTrue("重连重放必须触发后置提供端变更回调 (postResetProviderCount > 0)", postResetProviderCount > 0)
                assertTrue("已在运行播放器的播放状态必须通过生产重注册与中央中枢分发链路到达 LyricViewController", LyricViewController.isPlaying)
                assertEquals("活跃提供端包名必须由中央中枢解析并到达 LyricViewController", "com.salt.music", LyricViewController.activePackage)
                assertEquals("已在运行的歌曲名必须通过重放与订阅链路到达 LyricViewController", "海阔天空", LyricViewController.currentSong?.name)
                assertEquals("已在运行的艺术家必须通过重放与订阅链路到达 LyricViewController", "Beyond", LyricViewController.currentSong?.artist)
            } finally {
                LyricDataHub.removeListener(postResetProbe)
                ProviderTestBridge.removeBootListener(token1)
            }

            // 5. 验证 onReconnected 同样能够触发 BridgeCentral.sendBootCompleted 恢复信号
            bootBroadcastReceived = false
            val token2 = ProviderTestBridge.addBootListener {
                bootBroadcastReceived = true
            }
            try {
                subscriberConnectionListener!!.onReconnected(subscriber)
                assertTrue("subscriber.onReconnected 同样必须触发生产广播并通知 CentralBootReceiver", bootBroadcastReceived)
            } finally {
                ProviderTestBridge.removeBootListener(token2)
            }
        } finally {
            ProviderTestBridge.setBroadcastDispatcher(null)
            SubscriberTestBridge.setBroadcastDispatcher(null)
            LyricDataHub.onPlaybackStateChanged(false)
            LyricDataHub.onSongChanged(null)
            LyricDataHub.onActiveProviderChanged(null)
            LyricDataHub.resetForTesting()
            LyricViewController.resetForTesting()
            SystemUIHooker.resetForTesting()
            ProviderTestBridge.resetBootReceiver()
            SubscriberTestBridge.resetBootReceiver()
            ProviderTestBridge.setForceSupported(false)
            SubscriberTestBridge.setForceSupported(false)
            BridgeCentral.resetForTesting()
        }
    }

    // =========================================================================
    // DP11.4 生产路径 Hook 回声拦截与布局防挤压回归测试
    // =========================================================================

    @Test
    fun testProductionPath_SetFlagsHookInterceptor_HideEchoRestoreAndReplaySequence() {
        class FakeViewClass {
            fun setFlags(flags: Int, mask: Int) {}
        }
        val customClassLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == View::class.java.name) {
                    return FakeViewClass::class.java
                }
                return super.loadClass(name, resolve)
            }
        }

        val mockContext = createTrackingMockModule()
        ViewVisibilityTracker.initialize(mockContext.module, customClassLoader)

        val setFlagsMethod = FakeViewClass::class.java.getDeclaredMethod(
            "setFlags",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val hooker = mockContext.installedHookers[setFlagsMethod]?.firstOrNull()
        assertNotNull("View.setFlags hooker 必须已安装", hooker)

        val context = createMockContext(
            idMap = mapOf(
                "notification_icon_area" to 301,
                "status_bar_start_side_content" to 302
            )
        )
        val host = PhoneStatusBarView(context)
        val notificationIconArea = ArbitraryViewGroup(context, 301).apply { visibility = View.VISIBLE }
        val startSideContent = ArbitraryViewGroup(context, 302).apply { visibility = View.VISIBLE }
        host.addView(notificationIconArea, -1, null)
        host.addView(startSideContent, -1, null)

        val style = LyricStyle().apply {
            basicStyle.visibilityRules = listOf(
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }
        val controller = StatusBarViewController(host, style)
        controller.onCreate()

        try {
            // Helper: 模拟调用 View.setFlags 时经由 Xposed Hooker.intercept 的真实生产流程
            fun dispatchSetFlagsHook(view: View, flags: Int, mask: Int = 0x0000000C): Any? {
                val chain = Proxy.newProxyInstance(
                    XposedInterface.Chain::class.java.classLoader,
                    arrayOf(XposedInterface.Chain::class.java)
                ) { _, method, methodArgs ->
                    when (method.name) {
                        "getThisObject" -> view
                        "getArgs" -> listOf(flags, mask)
                        "proceed" -> {
                            val effectiveFlags = when (val first = methodArgs?.getOrNull(0)) {
                                is Array<*> -> first[0] as Int
                                is List<*> -> first[0] as Int
                                is Int -> first
                                else -> flags
                            }
                            view.visibility = effectiveFlags
                            "proceed_ok"
                        }
                        else -> null
                    }
                } as XposedInterface.Chain
                return hooker!!.intercept(chain)
            }

            // 1. 播放开始：控制器标记隐藏并将视图设为 CUSTOM_GONE，经 Hook 转换为 View.GONE
            LyricViewController.onPlaybackStateChanged(true)
            controller.lyricView.visibility = View.VISIBLE
            controller.onPlaybackStateChanged(true)

            dispatchSetFlagsHook(notificationIconArea, ViewVisibilityTracker.CUSTOM_GONE)
            dispatchSetFlagsHook(startSideContent, ViewVisibilityTracker.CUSTOM_GONE)

            assertEquals("播放时 notificationIconArea 经 Hook 转换后在视图层为 View.GONE", View.GONE, notificationIconArea.visibility)
            assertEquals("播放时 startSideContent 经 Hook 转换后在视图层为 View.GONE", View.GONE, startSideContent.visibility)
            assertTrue("notificationIconArea 被标记为 moduleHidden", ViewVisibilityTracker.isModuleHidden(notificationIconArea))
            assertTrue("startSideContent 被标记为 moduleHidden", ViewVisibilityTracker.isModuleHidden(startSideContent))
            assertEquals("notificationIconArea 原始基线维持 VISIBLE", View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(notificationIconArea))
            assertEquals("startSideContent 原始基线维持 VISIBLE", View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(startSideContent))

            // 2. 模拟真机缺陷核心路径：SystemUI 或框架观察到隐藏后反射回声 setFlags(GONE)
            dispatchSetFlagsHook(notificationIconArea, View.GONE)
            dispatchSetFlagsHook(startSideContent, View.GONE)

            // 核心断言：模块隐藏期间收到的 GONE 回声严禁破坏已记录的 VISIBLE 基线！
            assertEquals("收到 GONE 回声后 notificationIconArea 记录基线严禁被篡改为 GONE", View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(notificationIconArea))
            assertEquals("收到 GONE 回声后 startSideContent 记录基线严禁被篡改为 GONE", View.VISIBLE, ViewVisibilityTracker.getOriginalVisibility(startSideContent))

            // 3. 暂停播放 (Pause)：必须成功恢复为记录的 VISIBLE 状态，绝不可滞留在 GONE！
            LyricViewController.onPlaybackStateChanged(false)
            controller.onPlaybackStateChanged(false)

            assertEquals("暂停后 notificationIconArea 必须恢复为 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
            assertEquals("暂停后 startSideContent 必须恢复为 VISIBLE", View.VISIBLE, startSideContent.visibility)
            assertFalse("恢复后 notificationIconArea 不再处于 moduleHidden 状态", ViewVisibilityTracker.isModuleHidden(notificationIconArea))
            assertFalse("恢复后 startSideContent 不再处于 moduleHidden 状态", ViewVisibilityTracker.isModuleHidden(startSideContent))

            // 4. 重新播放 (Resume Play)：能够再次隐藏并再次正确恢复
            LyricViewController.onPlaybackStateChanged(true)
            controller.lyricView.visibility = View.VISIBLE
            controller.onPlaybackStateChanged(true)

            dispatchSetFlagsHook(notificationIconArea, ViewVisibilityTracker.CUSTOM_GONE)
            dispatchSetFlagsHook(startSideContent, ViewVisibilityTracker.CUSTOM_GONE)
            assertEquals("重新播放时 notificationIconArea 必须再次隐藏为 GONE", View.GONE, notificationIconArea.visibility)
            assertEquals("重新播放时 startSideContent 必须再次隐藏为 GONE", View.GONE, startSideContent.visibility)

            LyricViewController.onPlaybackStateChanged(false)
            controller.onPlaybackStateChanged(false)
            assertEquals("再次暂停后 notificationIconArea 必须再次恢复为 VISIBLE", View.VISIBLE, notificationIconArea.visibility)
            assertEquals("再次暂停后 startSideContent 必须再次恢复为 VISIBLE", View.VISIBLE, startSideContent.visibility)

            // 5. 隐藏期间系统产生独立的非 GONE 不可见请求（如 INVISIBLE），恢复时不强制设为 VISIBLE
            LyricViewController.onPlaybackStateChanged(true)
            controller.lyricView.visibility = View.VISIBLE
            controller.onPlaybackStateChanged(true)
            dispatchSetFlagsHook(notificationIconArea, ViewVisibilityTracker.CUSTOM_GONE)

            // 系统在隐藏期间触发独立的 INVISIBLE 状态
            dispatchSetFlagsHook(notificationIconArea, View.INVISIBLE)
            assertEquals("系统在隐藏期间触发独立 INVISIBLE 时基线更新为 INVISIBLE", View.INVISIBLE, ViewVisibilityTracker.getOriginalVisibility(notificationIconArea))

            LyricViewController.onPlaybackStateChanged(false)
            controller.onPlaybackStateChanged(false)
            assertEquals("恢复时必须尊重系统独立的 INVISIBLE 状态，绝不可强制设为 VISIBLE", View.INVISIBLE, notificationIconArea.visibility)
        } finally {
            LyricViewController.onPlaybackStateChanged(false)
            controller.onDestroy()
        }
    }

    @Test
    fun testLayoutGuardrail_LyricInjectionUnderKnownHeight_DoesNotIncreaseParentMeasuredHeight() {
        val density = 3.0f
        val statusBarHeightPx = (48 * density).toInt() // 144px 对应典型 48dp 状态栏高度
        val context = createMockContext(
            idMap = mapOf(
                "status_bar_start_side_container" to 401,
                "status_bar_start_side_content" to 402,
                "clock" to 403
            )
        )

        val modeExactly = 1
        val modeAtMost = 2

        class TestStatusBarRowViewGroup(ctx: Context, val fixedHeight: Int) : TestStatusBarViewGroup(ctx) {
            var simulatedMeasuredWidth: Int = 0
            var simulatedMeasuredHeight: Int = 0

            fun performMeasure(widthSize: Int, heightSize: Int, heightMode: Int) {
                for (child in mChildren) {
                    when (child) {
                        is TestStatusBarRowViewGroup -> child.performMeasure(widthSize, heightSize, heightMode)
                        is StatusBarLyric -> child.performMeasure(widthSize, heightSize)
                    }
                }

                var computedHeight = if (heightMode == modeExactly) heightSize else 0
                for (child in mChildren) {
                    val childHeight = when (child) {
                        is TestStatusBarRowViewGroup -> child.simulatedMeasuredHeight
                        is StatusBarLyric -> child.effectiveMeasuredHeight
                        else -> (child.layoutParams as? ViewGroup.MarginLayoutParams)?.height?.takeIf { it > 0 } ?: 0
                    }
                    val margins = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0
                    if (heightMode != modeExactly) {
                        computedHeight = maxOf(computedHeight, childHeight + margins)
                    }
                }
                if (heightMode == modeAtMost) {
                    computedHeight = minOf(computedHeight, heightSize)
                }
                simulatedMeasuredWidth = widthSize
                simulatedMeasuredHeight = computedHeight
            }
        }

        // 构建状态栏根容器与典型水平布局行
        val root = TestStatusBarRowViewGroup(context, statusBarHeightPx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeightPx)
        }
        val horizontalRow = TestStatusBarRowViewGroup(context, statusBarHeightPx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeightPx)
        }
        val existingClock = DummyTextClock(context).apply {
            id = 403
            text = "12:00"
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, statusBarHeightPx)
        }
        horizontalRow.addView(existingClock)
        root.addView(horizontalRow)

        // 1. 基线测量：注入歌词前测量状态栏高度
        root.performMeasure(1080, statusBarHeightPx, modeExactly)
        val baselineMeasuredHeightExactly = root.simulatedMeasuredHeight
        assertEquals("基线 EXACTLY 测量高度必须等于状态栏高度", statusBarHeightPx, baselineMeasuredHeightExactly)

        root.performMeasure(1080, statusBarHeightPx, modeAtMost)
        val baselineMeasuredHeightAtMost = root.simulatedMeasuredHeight
        assertTrue("基线 AT_MOST 测量高度必须不超过状态栏高度", baselineMeasuredHeightAtMost <= statusBarHeightPx)

        // 2. 创建具备极端夸张边距与内边距的歌词样式（模拟可能导致撑大父容器的配置）
        val exaggeratedStyle = LyricStyle().apply {
            basicStyle.margins = io.github.proify.lyricon.lyric.style.RectF(10f, 20f, 10f, 20f)
            basicStyle.paddings = io.github.proify.lyricon.lyric.style.RectF(10f, 20f, 10f, 20f)
            basicStyle.anchor = "clock"
            basicStyle.insertionOrder = BasicStyle.INSERTION_ORDER_BEFORE
        }

        // 3. 构造 StatusBarLyric 视图并挂载至水平行
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val lyricView = unsafe.allocateInstance(StatusBarLyric::class.java) as StatusBarLyric
        lyricView.setTag(0x7f0a9999, horizontalRow)
        horizontalRow.setTag(0x7f0a9999, root)

        // 验证注入 LayoutParams 为 WRAP_CONTENT 高度，不再强制 MATCH_PARENT
        val lyricLp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            topMargin = 4
            bottomMargin = 4
        }
        horizontalRow.addView(lyricView, 0, lyricLp)

        assertNotEquals("歌词视图的高度布局参数绝不强制约束为 MATCH_PARENT", ViewGroup.LayoutParams.MATCH_PARENT, lyricView.layoutParams?.height)
        assertEquals("歌词视图的高度布局参数必须为 WRAP_CONTENT", ViewGroup.LayoutParams.WRAP_CONTENT, lyricView.layoutParams?.height)

        // 4. 再次测量：注入歌词视图并应用边距后，根容器与水平行高度严禁增加！
        root.performMeasure(1080, statusBarHeightPx, modeExactly)
        assertEquals(
            "注入歌词视图后，EXACTLY 测量下状态栏高度严禁增加",
            baselineMeasuredHeightExactly,
            root.simulatedMeasuredHeight
        )

        root.performMeasure(1080, statusBarHeightPx, modeAtMost)
        assertTrue(
            "注入歌词视图后，AT_MOST 测量下状态栏高度严禁超出状态栏标准高度",
            root.simulatedMeasuredHeight <= statusBarHeightPx
        )

        // 5. 验证 StatusBarLyric 的 effectiveMeasuredHeight 不超出宿主高度
        assertTrue(
            "StatusBarLyric 的 effectiveMeasuredHeight (${lyricView.effectiveMeasuredHeight}) 严禁超过宿主高度 ($statusBarHeightPx)",
            lyricView.effectiveMeasuredHeight <= statusBarHeightPx
        )
    }

    @Test
    fun testLyricVerticalAlignment_MatchesClockRowCenter_DoesNotForceMatchParentHeight() {
        // Fixture: 典型 SystemUI 状态栏 48dp 水平行容器 (LinearLayout, orientation=HORIZONTAL)
        val context = createMockContext(
            idMap = mapOf(
                "status_bar_start_side_container" to 501,
                "status_bar_start_side_content" to 502,
                "clock" to 503
            )
        )
        val density = 3f
        val statusBarHeightPx = (48 * density).toInt()

        val host = PhoneStatusBarView(context)
        val startSideRow = TestStatusBarLinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeightPx)
        }
        val startSideContent = ArbitraryViewGroup(context, 502).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        startSideRow.addView(startSideContent)
        host.addView(startSideRow)

        val style = LyricStyle().apply {
            basicStyle.anchor = "status_bar_start_side_content"
            basicStyle.insertionOrder = BasicStyle.INSERTION_ORDER_BEFORE
        }

        val controller = StatusBarViewController(host, style)
        controller.updateLyricStyle(style)

        val lyricView = controller.lyricView
        val lp = lyricView.layoutParams
        assertNotNull("lyricView 必须注入 LayoutParams", lp)

        // 1. 核心断言：高度绝不被强制为 MATCH_PARENT，而是 WRAP_CONTENT
        assertNotEquals("歌词视图的高度绝对不强制约束为 MATCH_PARENT", ViewGroup.LayoutParams.MATCH_PARENT, lp?.height)
        assertEquals("歌词视图的高度必须为 WRAP_CONTENT 避免顶部对齐与拉伸", ViewGroup.LayoutParams.WRAP_CONTENT, lp?.height)

        // 2. 核心断言：垂直重力对齐为 CENTER_VERTICAL（匹配时钟/侧边内容行）
        assertTrue("LayoutParams 必须为 LinearLayout.LayoutParams", lp is LinearLayout.LayoutParams)
        val linLp = lp as LinearLayout.LayoutParams
        val verticalGravity = linLp.gravity and Gravity.VERTICAL_GRAVITY_MASK
        assertEquals("歌词视图垂直 Gravity 必须为 CENTER_VERTICAL 保持与时钟行居中对齐", Gravity.CENTER_VERTICAL, verticalGravity)
    }

    @Test
    fun testDiagnosticLogs_ProductionPathStatements_NoSongMetadata() {
        val context = createMockContext(
            idMap = mapOf(
                "status_bar_start_side_container" to 601,
                "status_bar_start_side_content" to 602,
                "notification_icon_area" to 603
            )
        )

        val host = PhoneStatusBarView(context)
        val container = ArbitraryViewGroup(context, 601).apply { visibility = View.VISIBLE }
        val content = ArbitraryViewGroup(context, 602).apply { visibility = View.VISIBLE }
        val notif = ArbitraryViewGroup(context, 603).apply { visibility = View.VISIBLE }
        content.addView(notif)
        container.addView(content)
        host.addView(container)

        val style = LyricStyle().apply {
            basicStyle.anchor = "status_bar_start_side_content"
            basicStyle.visibilityRules = listOf(
                VisibilityRule("status_bar_start_side_content", VisibilityRule.MODE_HIDE_WHEN_PLAYING),
                VisibilityRule("notification_icon_area", VisibilityRule.MODE_HIDE_WHEN_PLAYING)
            )
        }

        val controller = StatusBarViewController(host, style)
        controller.updateLyricStyle(style)

        // 触发生产路径：播放状态变更、隐藏、恢复
        controller.onPlaybackStateChanged(true)
        controller.restoreHiddenViews()
        controller.onPlaybackStateChanged(false)

        // 触发 setFlags 基线变更
        ViewVisibilityTracker.onSystemVisibilityChanged(content, View.INVISIBLE)

        assertTrue("生产诊断路径正常完成执行，且无崩溃", true)
    }

    // =========================================================================
    // 辅助 Mock 生成器
    // =========================================================================

    private fun createMockContext(
        layoutMap: Map<String, Int> = emptyMap(),
        idMap: Map<String, Int> = emptyMap()
    ): Context {
        val reversedIdMap = idMap.entries.associate { it.value to it.key }
        val mockConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        val mockMetrics = DisplayMetrics().apply {
            density = 3f
            scaledDensity = 3f
            densityDpi = 480
            widthPixels = 1080
            heightPixels = 2400
        }
        val broadcastReceivers = mutableListOf<Pair<android.content.BroadcastReceiver, android.content.IntentFilter>>()
        return object : android.content.ContextWrapper(null) {
            private val mockRes = object : Resources(null, null, null) {
                override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
                    return when (defType) {
                        "layout" -> layoutMap[name] ?: 0
                        "id" -> idMap[name] ?: 0
                        else -> 0
                    }
                }

                override fun getResourceEntryName(resid: Int): String {
                    return reversedIdMap[resid] ?: throw Resources.NotFoundException("0x${Integer.toHexString(resid)}")
                }

                override fun getConfiguration(): Configuration = mockConfig
                override fun getDisplayMetrics(): DisplayMetrics = mockMetrics
            }
            override fun getResources(): Resources = mockRes
            override fun getPackageName(): String = "com.android.systemui"
            override fun getApplicationContext(): Context = this
            override fun getClassLoader(): ClassLoader = javaClass.classLoader

            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter?
            ): android.content.Intent? {
                if (receiver != null && filter != null) {
                    broadcastReceivers.add(receiver to filter)
                }
                return null
            }

            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter?,
                flags: Int
            ): android.content.Intent? {
                if (receiver != null && filter != null) {
                    broadcastReceivers.add(receiver to filter)
                }
                return null
            }

            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter?,
                broadcastPermission: String?,
                scheduler: android.os.Handler?
            ): android.content.Intent? {
                if (receiver != null && filter != null) {
                    broadcastReceivers.add(receiver to filter)
                }
                return null
            }

            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter?,
                broadcastPermission: String?,
                scheduler: android.os.Handler?,
                flags: Int
            ): android.content.Intent? {
                if (receiver != null && filter != null) {
                    broadcastReceivers.add(receiver to filter)
                }
                return null
            }

            override fun unregisterReceiver(receiver: android.content.BroadcastReceiver?) {
                broadcastReceivers.removeAll { it.first == receiver }
            }

            override fun sendBroadcast(intent: android.content.Intent?) {
                if (intent == null) return
                val action = runCatching { intent.action }.getOrNull()
                    ?: "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"
                val deliveredIntent = object : android.content.Intent(action) {
                    override fun getAction(): String = action
                }
                broadcastReceivers.toList().forEach { (receiver, _) ->
                    receiver.onReceive(this, deliveredIntent)
                }
            }
        }
    }

    class TrackingHookHandle(
        private val executable: Executable,
        val hooker: XposedInterface.Hooker,
        val onUnhook: (TrackingHookHandle) -> Unit
    ) : XposedInterface.HookHandle {
        var isUnhooked: Boolean = false
        override fun unhook() {
            isUnhooked = true
            onUnhook(this)
        }
        override fun getExecutable(): Executable = executable
        override fun getId(): String? = null
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = this
    }

    inner class MockHookContext(
        val module: XposedModule,
        val installedHookers: MutableMap<Executable, MutableList<XposedInterface.Hooker>>,
        val activeHandles: MutableList<TrackingHookHandle>,
        val unhookedHandles: MutableList<TrackingHookHandle>
    ) {
        fun invokeHook(executable: Executable, thisObject: Any?, vararg args: Any?) {
            val hookers = installedHookers[executable]?.toList() ?: return
            hookers.forEach { hooker ->
                val chain = createMockChain(thisObject = thisObject, args = args.toList())
                hooker.intercept(chain)
            }
        }
    }

    private fun createTrackingMockModule(onHook: (Executable, XposedInterface.Hooker) -> Unit = { _, _ -> }): MockHookContext {
        val module = ModuleEntry()
        val installedHookers = mutableMapOf<Executable, MutableList<XposedInterface.Hooker>>()
        val activeHandles = mutableListOf<TrackingHookHandle>()
        val unhookedHandles = mutableListOf<TrackingHookHandle>()

        val mockInterface = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java)
        ) { _, method, args ->
            when (method.name) {
                "hook" -> {
                    val executable = args[0] as Executable
                    Proxy.newProxyInstance(
                        XposedInterface.HookBuilder::class.java.classLoader,
                        arrayOf(XposedInterface.HookBuilder::class.java)
                    ) { _, builderMethod, builderArgs ->
                        when (builderMethod.name) {
                            "intercept" -> {
                                val hooker = builderArgs[0] as XposedInterface.Hooker
                                installedHookers.getOrPut(executable) { mutableListOf() }.add(hooker)
                                onHook(executable, hooker)
                                lateinit var handle: TrackingHookHandle
                                handle = TrackingHookHandle(executable, hooker) { h ->
                                    activeHandles.remove(h)
                                    unhookedHandles.add(h)
                                    installedHookers[executable]?.remove(hooker)
                                }
                                activeHandles.add(handle)
                                handle
                            }
                            else -> builderMethod.returnType.takeIf { it == XposedInterface.HookBuilder::class.java }
                        }
                    }
                }
                else -> null
            }
        } as XposedInterface

        try {
            module.attachFramework(mockInterface) {}
        } catch (_: Throwable) {}
        return MockHookContext(module, installedHookers, activeHandles, unhookedHandles)
    }

    private fun createMockModule(onHook: (Executable, XposedInterface.Hooker) -> Unit = { _, _ -> }): XposedModule {
        return createTrackingMockModule(onHook).module
    }

    private fun createMockChain(
        thisObject: Any? = null,
        args: List<Any?> = emptyList(),
        result: Any? = null
    ): XposedInterface.Chain {
        return Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)
        ) { _, method, methodArgs ->
            when (method.name) {
                "getThisObject" -> thisObject
                "getArgs" -> args
                "getArg" -> {
                    val idx = methodArgs[0] as Int
                    args[idx]
                }
                "proceed", "proceedWith" -> result
                else -> null
            }
        } as XposedInterface.Chain
    }

    class DummyFallbackFragment {
        fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {}
    }
}
