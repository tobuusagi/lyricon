/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.forEach
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarViewResolver
import io.github.proify.lyricon.xposed.systemui.hook.ViewVisibilityTracker

/**
 * 视图可见性控制器
 * 根据规则管理 ViewGroup 中子视图的可见性
 *
 * @author Proify, Tomakino
 * @since 2026
 */
class ViewVisibilityController(private val rootViewGroup: ViewGroup) {
    companion object {
        private const val TAG = "ViewVisibilityController"
        private const val TRACKED_MARKER = "tracked"
        private const val VISIBILITY_UNKNOWN = -1
        private const val DEBUG = false
    }

    private val clockDiscovery by lazy {
        ClockViewFinder.discoverClock(rootViewGroup)
    }

    /** 记录由本模块显式隐藏的视图集合，防止多余或陈旧恢复破坏系统原生非 VISIBLE 状态 */
    private val hiddenViewsByModule = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    /** Pre-hide visibility owned by this controller, including when the shared tracker cannot provide one. */
    private val localBaselineByView = java.util.WeakHashMap<View, Int>()

    /** 是否存在被本模块显式隐藏但尚未恢复的视图 */
    fun hasHiddenViews(): Boolean {
        synchronized(hiddenViewsByModule) {
            if (hiddenViewsByModule.isNotEmpty()) return true
        }
        return hasModuleHiddenInHierarchy(rootViewGroup)
    }

    private fun hasModuleHiddenInHierarchy(view: View): Boolean {
        if (view.getTag(ViewVisibilityTracker.TRACKING_TAG_ID) != null && ViewVisibilityTracker.isModuleHidden(view)) {
            return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasModuleHiddenInHierarchy(view.getChildAt(i))) return true
            }
        }
        return false
    }

    /**
     * 根据规则更新视图可见性
     * @param rules 可见性规则列表
     * @param isPlaying 是否正在播放
     */
    fun applyVisibilityRules(rules: List<VisibilityRule>, isPlaying: Boolean) {
        if (DEBUG) YLog.debug(TAG, "Applying visibility rules... isPlaying=$isPlaying, $rules")
        if (rules.isEmpty()) return

        rules.forEach { rule ->
            applyRuleToView(rule, isPlaying)
        }
    }

    private fun applyRuleToView(rule: VisibilityRule, isPlaying: Boolean) {
        val viewId = rule.id
        if (viewId.isBlank()) return

        // 识别时钟规则
        val isClockRule = (viewId == "clock" || viewId.contains("clock", ignoreCase = true))

        if (isClockRule && clockDiscovery.isComposeClock && clockDiscovery.view == null) {
            // 在纯 Compose 状态栏且完全不存在传统 View 时钟时，操作传统 View 并不能隐藏时钟。
            // 遵守规范，不向用户虚报生效，记录明确的本地降级日志并保持系统原生可见性。
            YLog.warning(
                TAG,
                "Clock visibility rule '$viewId' ignored: host uses Compose clock without legacy View (local degradation)"
            )
            return
        }

        val targetView = (if (isClockRule) clockDiscovery.view else null)
            ?: findViewByResourceName(rootViewGroup, viewId)
            ?: return

        // 严禁隐藏包含歌词视图的容器或歌词视图本身，否则会导致歌词完全不可见（致盲）
        if (containsLyricView(targetView)) {
            YLog.warning(
                TAG,
                "Refusing to hide container '$viewId': it contains StatusBarLyric, which would blank lyrics"
            )
            return
        }

        // 标记视图以便追踪并保存初始可见性
        targetView.setTag(ViewVisibilityTracker.TRACKING_TAG_ID, TRACKED_MARKER)
        ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(targetView, targetView.visibility)

        when (rule.mode) {
            VisibilityRule.MODE_NORMAL -> restoreOriginalVisibility(targetView)
            VisibilityRule.MODE_HIDE_WHEN_PLAYING -> applyPlaybackRule(targetView, isPlaying)
            else -> restoreOriginalVisibility(targetView)
        }
    }

    private fun restoreOriginalVisibility(view: View) {
        val resName = runCatching { ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
        val isHiddenBefore = ViewVisibilityTracker.isModuleHidden(view)
        val visBefore = view.visibility
        val trackerBaseline = ViewVisibilityTracker.getOriginalVisibility(view)
        val localBaseline = synchronized(localBaselineByView) { localBaselineByView[view] }
        val baseline = if (trackerBaseline != VISIBILITY_UNKNOWN) trackerBaseline else localBaseline ?: VISIBILITY_UNKNOWN

        // 优先解除模块隐藏占有标记，确保后续设置可见性时 setFlags 拦截器不会将其视作模块占有
        ViewVisibilityTracker.setModuleHidden(view, false)
        val isHiddenAfter = ViewVisibilityTracker.isModuleHidden(view)

        if (baseline != VISIBILITY_UNKNOWN) {
            view.visibility = baseline
        }
        val visAfter = view.visibility

        // Keep the local fallback only if restoration could not apply a known baseline.
        if (baseline != VISIBILITY_UNKNOWN && visAfter == baseline) {
            synchronized(localBaselineByView) { localBaselineByView.remove(view) }
        }

        synchronized(hiddenViewsByModule) {
            hiddenViewsByModule.remove(view)
        }

        YLog.info(
            TAG,
            "restoreHiddenViews: view=$resName, baseline=$baseline, trackerBaseline=$trackerBaseline, localBaselineUsed=${trackerBaseline == VISIBILITY_UNKNOWN && localBaseline != null}, isComposeView=${ViewVisibilityTracker.isComposeView(view)}, visBefore=$visBefore, visAfter=$visAfter, isModuleHiddenBefore=$isHiddenBefore, isModuleHiddenAfter=$isHiddenAfter"
        )
    }

    @Suppress("SameParameterValue")
    private fun setVisibility(view: View, visibility: Int) {
        view.visibility = when (visibility) {
            View.VISIBLE -> ViewVisibilityTracker.CUSTOM_VISIBLE
            View.GONE -> ViewVisibilityTracker.CUSTOM_GONE
            else -> visibility
        }
    }

    private fun applyPlaybackRule(view: View, isPlaying: Boolean) {
        if (isPlaying) {
            val resName = runCatching { ResourceMapper.getIdName(view) }.getOrDefault("id_${view.id}")
            val visBeforeHide = view.visibility
            val alreadyHiddenByModule = synchronized(hiddenViewsByModule) { hiddenViewsByModule.contains(view) } ||
                    ViewVisibilityTracker.isModuleHidden(view)
            val isComposeView = ViewVisibilityTracker.isComposeView(view)
            if (visBeforeHide != ViewVisibilityTracker.CUSTOM_GONE && visBeforeHide != ViewVisibilityTracker.CUSTOM_VISIBLE) {
                ViewVisibilityTracker.saveOriginalVisibilityIfNeeded(view, visBeforeHide)
                synchronized(localBaselineByView) {
                    if (!localBaselineByView.containsKey(view)) localBaselineByView[view] = visBeforeHide
                }
            } else if (alreadyHiddenByModule) {
                // A prior hide may already have been reflected as GONE after a skipped restore.
                // Preserve the original controller baseline and keep this target in the restore set.
                synchronized(hiddenViewsByModule) { hiddenViewsByModule.add(view) }
            }
            val savedBaseline = ViewVisibilityTracker.getOriginalVisibility(view)
            val localBaseline = synchronized(localBaselineByView) { localBaselineByView[view] }
            if (view.visibility != View.GONE || alreadyHiddenByModule || localBaseline != null) {
                synchronized(hiddenViewsByModule) {
                    hiddenViewsByModule.add(view)
                }
                ViewVisibilityTracker.setModuleHidden(view, true)
                if (view.visibility != View.GONE) setVisibility(view, View.GONE)
                YLog.info(
                    TAG,
                    "hiding for play: view=$resName, visBeforeHide=$visBeforeHide, savedBaseline=$savedBaseline, localBaseline=${localBaseline ?: VISIBILITY_UNKNOWN}, isComposeView=$isComposeView, alreadyHiddenByModule=$alreadyHiddenByModule"
                )
            }
        } else {
            restoreOriginalVisibility(view)
        }
    }

    /**
     * 恢复所有被此模块隐藏的视图至其记录的原始可见性
     */
    fun restoreHiddenViews() {
        val toRestore = synchronized(hiddenViewsByModule) { hiddenViewsByModule.toList() }
        toRestore.forEach { restoreOriginalVisibility(it) }
        restoreTrackedHierarchy(rootViewGroup)
    }

    private fun restoreTrackedHierarchy(view: View) {
        val isHidden = synchronized(hiddenViewsByModule) { hiddenViewsByModule.contains(view) } ||
                ViewVisibilityTracker.isModuleHidden(view)
        if (view.getTag(ViewVisibilityTracker.TRACKING_TAG_ID) != null && isHidden) {
            restoreOriginalVisibility(view)
        }
        if (view is ViewGroup) {
            view.forEach { restoreTrackedHierarchy(it) }
        }
    }

    /**
     * 恢复所有被此控制器追踪的视图的原始可见性，并清理追踪记录
     */
    fun restoreAndClear() {
        restoreHiddenViews()
        clearHierarchy(rootViewGroup)
    }

    private fun clearHierarchy(view: View) {
        if (view.getTag(ViewVisibilityTracker.TRACKING_TAG_ID) != null) {
            restoreOriginalVisibility(view)
            view.setTag(ViewVisibilityTracker.TRACKING_TAG_ID, null)
            ViewVisibilityTracker.clearTracking(view)
            synchronized(localBaselineByView) { localBaselineByView.remove(view) }
        }
        if (view is ViewGroup) {
            view.forEach { clearHierarchy(it) }
        }
    }

    /**
     * 根据资源名称递归查找视图
     */
    private fun findViewByResourceName(view: View, targetResourceName: String): View? {
        val res = (view.getTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY) as? android.content.Context)?.resources
            ?: runCatching { view.context?.resources }.getOrNull()
        val idName = if (res != null) {
            ResourceMapper.getIdName(view, res)
        } else {
            runCatching { ResourceMapper.getIdName(view) }.getOrNull()
        }
        if (idName == targetResourceName) {
            return view
        }

        if (view is ViewGroup) {
            view.forEach { child ->
                findViewByResourceName(child, targetResourceName)?.let { return it }
            }
        }
        return null
    }

    /**
     * 判断视图是否包含 StatusBarLyric 视图（或其自身就是 StatusBarLyric）
     */
    private fun containsLyricView(view: View): Boolean {
        if (view.javaClass.name.contains("StatusBarLyric")) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsLyricView(view.getChildAt(i))) return true
            }
        }
        return false
    }
}
