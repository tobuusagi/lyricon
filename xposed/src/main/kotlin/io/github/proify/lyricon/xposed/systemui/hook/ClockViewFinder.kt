/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import io.github.proify.lyricon.xposed.logger.YLog

/**
 * 时钟视图发现工具
 *
 * 具有对传统 View 时钟与 DP11 Compose 时钟的能力区分：
 * 1. 针对传统 View-based 状态栏，通过候选资源 ID、TextClock 子类及类名关键词进行匹配；
 * 2. 针对 DP11 Compose 状态栏，明确识别出 Compose 时钟形态，并声明 View-based 隐藏/恢复不受支持
 *    （执行本地降级，避免误操作不可见的传统 Clock View）。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object ClockViewFinder {

    private const val TAG = "ClockViewFinder"

    /** 候选时钟资源 ID 名称（按优先级排序），覆盖主流 ROM 的命名差异 */
    val CLOCK_ID_NAMES = arrayOf(
        "clock",            // AOSP 及绝大多数 ROM（MIUI/HyperOS、ColorOS、OneUI、EMUI 等）
        "status_bar_clock", // 部分定制 ROM
        "clock_view",       // 部分定制 ROM
        "time",             // 部分 ROM
        "status_bar_time",  // 部分 ROM
    )

    /** 时钟类名关键词（不区分大小写），用于资源 ID 解析失败时的兜底匹配 */
    private val CLOCK_CLASS_KEYWORDS = arrayOf("clock", "time")

    /** 厂商 SystemUI 的包名候选 */
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 已解析出的时钟候选 ID 集合 */
    @Volatile
    private var resolvedClockIds: IntArray? = null

    /**
     * 时钟形态与控制能力分类
     */
    enum class ClockKind {
        NONE,
        LEGACY_VIEW,
        COMPOSE
    }

    /**
     * 时钟发现与能力描述结果
     */
    data class ClockDiscoveryResult(
        val view: View?,
        val kind: ClockKind,
        val hasCompose: Boolean = false,
        val isComposeClock: Boolean = (kind == ClockKind.COMPOSE),
        val isHideRestoreSupported: Boolean = (view != null)
    )

    /**
     * 在状态栏视图层级中发现时钟控件（传统 View 语义，保持向后兼容）
     * 注意：在 DP11 等 Compose 状态栏上，该 View 可能是占位或隐藏视图，并不代表屏幕呈现的时钟。
     *
     * @param root 状态栏根视图
     * @return 时钟控件，未找到返回 null
     */
    @SuppressLint("DiscouragedApi")
    fun find(root: ViewGroup): View? {
        val ctx = (root.getTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY) as? Context) ?: root.context
        ctx?.let { resolveClockIds(it) }

        // 1. 候选 ID 匹配
        resolvedClockIds?.forEach { id ->
            val v = root.findViewById<View>(id) ?: findFirst(root) { it.id == id }
            if (v != null) return v
        }

        // 2. TextClock 实例匹配（包含所有自定义子类）
        findFirst(root) { it is TextClock }?.let { return it }

        // 3. 类名关键词兜底匹配（覆盖非 TextClock 的自定义时钟实现）
        return findFirst(root) { matchClockByClassName(it) }
    }

    /**
     * 执行带能力区分的时钟发现
     *
     * @param root 状态栏根视图
     * @return 时钟发现与能力分类结果
     */
    fun discoverClock(root: ViewGroup): ClockDiscoveryResult {
        val legacyView = find(root)
        val hasCompose = isComposeHierarchy(root)

        val kind = when {
            legacyView != null -> ClockKind.LEGACY_VIEW
            hasCompose -> {
                YLog.info(TAG, "Compose status bar with no legacy clock view detected; clock hide/restore is unsupported (degrading locally)")
                ClockKind.COMPOSE
            }
            else -> ClockKind.NONE
        }

        return ClockDiscoveryResult(
            view = legacyView,
            kind = kind,
            hasCompose = hasCompose,
            isComposeClock = (kind == ClockKind.COMPOSE),
            isHideRestoreSupported = (legacyView != null)
        )
    }

    /**
     * 检测视图树中是否包含 ComposeView（DP11 状态栏采用 Compose 渲染时钟及系统区域）
     */
    fun isComposeHierarchy(root: ViewGroup): Boolean {
        return findFirst(root) {
            val name = it.javaClass.name
            name.contains("ComposeView") || name.contains("androidx.compose")
        } != null
    }

    /**
     * 解析候选时钟资源 ID
     */
    @SuppressLint("DiscouragedApi")
    private fun resolveClockIds(context: Context) {
        resolvedClockIds?.let { return }

        val packages = LinkedHashSet<String>()
        runCatching { context.packageName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { packages.add(it) }
        packages.add(SYSTEM_UI_PACKAGE)

        val ids = packages.flatMap { pkg ->
            CLOCK_ID_NAMES.mapNotNull { name ->
                runCatching { context.resources.getIdentifier(name, "id", pkg) }
                    .getOrDefault(0)
                    .takeIf { it != 0 }
            }
        }

        if (ids.isEmpty()) {
            YLog.warning(
                TAG,
                "No clock resource id found among ${CLOCK_ID_NAMES.contentToString()}"
            )
        }

        resolvedClockIds = ids.toIntArray()
    }

    /**
     * 深度优先遍历视图树，返回第一个满足条件的视图
     */
    fun findFirst(root: ViewGroup, predicate: (View) -> Boolean): View? {
        val count = root.childCount
        for (i in 0 until count) {
            val child = root.getChildAt(i) ?: continue
            if (predicate(child)) return child
            if (child is ViewGroup) {
                findFirst(child, predicate)?.let { return it }
            }
        }
        return null
    }

    /**
     * 按类名关键词匹配时钟视图
     */
    private fun matchClockByClassName(view: View): Boolean {
        if (view !is TextView) return false

        val className = view.javaClass.name
        return CLOCK_CLASS_KEYWORDS.any { keyword ->
            className.contains(keyword, ignoreCase = true)
        }
    }

    fun resetForTesting() {
        resolvedClockIds = null
    }
}
