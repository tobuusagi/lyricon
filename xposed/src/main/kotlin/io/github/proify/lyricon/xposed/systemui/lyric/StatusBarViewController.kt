/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.isLandScape
import io.github.proify.android.extensions.setColorAlpha
import io.github.proify.android.extensions.toBitmap
import io.github.proify.lyricon.app.bridge.AppBridge.LyricGesturePrefs
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarViewResolver
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController.isPlaying
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import java.io.File

/**
 * 状态栏歌词视图控制器：负责歌词视图的注入、位置锚定及显隐逻辑
 *
 * 严格绑定到真实的 PhoneStatusBarView 宿主，而非 window decor / rootView。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
@SuppressLint("DiscouragedApi")
open class StatusBarViewController(
    val statusBarView: ViewGroup,
    var currentLyricStyle: LyricStyle
) : ScreenStateMonitor.ScreenStateListener {
    companion object {
        const val TAG = "StatusBarViewController"
    }

    val context: Context = (statusBarView.getTag(StatusBarViewResolver.TEST_CONTEXT_TAG_KEY) as? Context)
        ?: runCatching { statusBarView.context?.applicationContext ?: statusBarView.context }.getOrNull()
        ?: statusBarView.context
        ?: object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.android.systemui"
        }
    val visibilityController: ViewVisibilityController by lazy { ViewVisibilityController(statusBarView) }
    val lyricView: StatusBarLyric by lazy { createLyricView(currentLyricStyle) }

    open val displayId: Int
        get() = statusBarView.display?.displayId ?: 0

    // --- 手势控制状态 (随偏好热更新) ---
    private var gestureEnabled: Boolean = LyricGesturePrefs.DEFAULT_ENABLED
    private var swipeLeftAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_LEFT
    private var swipeRightAction: Int = LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
    private var tapAction: Int = LyricGesturePrefs.DEFAULT_TAP
    private var longPressAction: Int = LyricGesturePrefs.DEFAULT_LONG_PRESS

    private var lastAnchor = ""
    private var lastInsertionOrder = -1
    private var internalRemoveLyricViewFlag = false
    private var lastHighlightView: View? = null
    var colorMonitorView: View? = null
        internal set
    private var coverColorPaletteResult: ColorPaletteResult? = null
    private var systemStatusBarColor: SystemStatusBarColor? = null

    private val colorChangeListener = object : OnColorChangeListener {
        private var colorFingerprint: String? = null
        override fun onColorChanged(color: Int, darkIntensity: Float) {
            val colorFingerprint = color.toString() + darkIntensity
            if (colorFingerprint == this.colorFingerprint) return
            this.colorFingerprint = colorFingerprint

            updateStatusColor(SystemStatusBarColor(color, darkIntensity))
        }
    }

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        // Playback can arrive before the lyric View's visibility/layout update. Retry only that
        // pending initial show; an explicit lyric-hidden callback must not be undone by layout.
        if (pendingLyricShowSync && lyricView.isVisible) {
            isLyricVisible = true
            pendingLyricShowSync = false
        }
        applyVisibilityRulesNow()
    }

    // --- 生命周期与初始化 ---
    open fun onCreate() {
        statusBarView.addOnAttachStateChangeListener(statusBarAttachListener)
        try {
            statusBarView.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        } catch (_: Throwable) {}
        lyricView.addOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.addListener(this)
        lyricView.onPlayingChanged = { isPlaying ->
            onPlaybackStateChanged(isPlaying)
        }
        lyricView.onLyricVisibilityChanged = { isVisible ->
            isLyricVisible = isVisible
            pendingLyricShowSync = false
            if (!isVisible) {
                restoreHiddenViews()
            } else {
                applyVisibilityRulesNow()
            }
        }

        // 手势控制:读取偏好并绑定回调,手势动作可配置
        runCatching { refreshGestureConfig() }
        lyricView.gestureListener = { gesture -> onLyricGesture(gesture) }

        StatusBarColorMonitor.bindStatusBar(statusBarView)
        val clockResult = ClockViewFinder.discoverClock(statusBarView)
        colorMonitorView = clockResult.view
        if (colorMonitorView != null) {
            StatusBarColorMonitor.bindClockView(colorMonitorView)
        } else {
            StatusBarColorMonitor.bindClockView(null)
            YLog.info(TAG, "No legacy clock view found on host for color sampling; falling back to status-bar icon dispatcher / palette")
        }
        StatusBarColorMonitor.addListener(colorChangeListener)

        statusBarView.doOnAttach { checkLyricViewExists() }
        YLog.info(tag = TAG, "Lyric view created for $statusBarView on display $displayId")
    }

    private fun getParentView(view: View): ViewParent? =
        StatusBarViewResolver.testParentMap[view]
            ?: (view.getTag(StatusBarViewResolver.TEST_PARENT_TAG_KEY) as? ViewParent)
            ?: view.parent

    open fun onDestroy() {
        statusBarView.removeOnAttachStateChangeListener(statusBarAttachListener)
        try {
            if (statusBarView.viewTreeObserver.isAlive) {
                statusBarView.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
            }
        } catch (_: Throwable) {}
        lyricView.removeOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.removeListener(this)
        lyricView.onPlayingChanged = null
        lyricView.onLyricVisibilityChanged = null
        lyricView.gestureListener = null
        lyricView.setOnClickListener(null)
        LyricControlPopup.dismissIfOwnedBy(lyricView)

        // 移除歌词视图，防止旧宿主保留子视图
        (getParentView(lyricView) as? ViewGroup)?.removeView(lyricView)

        StatusBarColorMonitor.removeListener(colorChangeListener)
        colorMonitorView?.let { StatusBarColorMonitor.unbindClockView(it) }
        colorMonitorView = null

        // 多屏安全解绑：若系统中仍有其他活跃显示屏的控制器，不可直接清空全局单例绑定
        val remainingController = StatusBarViewManager.controllers.firstOrNull {
            it !== this && it.statusBarView !== this.statusBarView
        }
        if (remainingController != null) {
            if (StatusBarColorMonitor.isBoundTo(statusBarView)) {
                StatusBarColorMonitor.bindStatusBar(remainingController.statusBarView)
                remainingController.colorMonitorView?.let { StatusBarColorMonitor.bindClockView(it) }
            }
        } else {
            StatusBarColorMonitor.unbindStatusBar(statusBarView)
        }

        // 恢复被追踪视图并清理可见性缓存
        visibilityController.restoreHiddenViews()
        visibilityController.restoreAndClear()

        YLog.info(tag = TAG, "Lyric view destroyed for $statusBarView on display $displayId")
    }

    // --- 核心业务逻辑 ---

    /**
     * 更新状态栏颜色，内部决定最终颜色
     */
    internal fun updateStatusColor(systemStatusBarColor: SystemStatusBarColor) {
        this.systemStatusBarColor = systemStatusBarColor

        val textStyle = currentLyricStyle.packageStyle.text
        lyricView.apply {
            val statusColor = currentStatusColor
            statusColor.apply {
                this.darkIntensity = systemStatusBarColor.darkIntensity

                // 确保基准状态栏颜色合法且非透明；若为 0 或完全透明，按暗色强度安全降级为黑/白，绝不留空白
                val safeColor = if (systemStatusBarColor.color != 0 && (systemStatusBarColor.color ushr 24) != 0) {
                    systemStatusBarColor.color
                } else {
                    if (systemStatusBarColor.darkIntensity > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }

                val coverColorPaletteResult = coverColorPaletteResult
                when {
                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor
                            && textStyle.enableExtractCoverTextGradient -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val gradient = themeColors.swatches

                        this.color = gradient
                        this.translucentColor = gradient.map {
                            it.setColorAlpha(0.75f)
                        }.toIntArray()
                    }

                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val primary = themeColors.primary

                        this.color = intArrayOf(primary)
                        this.translucentColor = intArrayOf(primary.setColorAlpha(0.75f))
                    }

                    else -> {
                        this.color = intArrayOf(safeColor)
                        this.translucentColor =
                            intArrayOf(safeColor.setColorAlpha(0.5f))
                    }
                }
            }
            runCatching { setStatusBarColor(statusColor) }
        }
    }

    /**
     * 更新歌词样式及位置，若锚点或顺序变化则重新注入视图
     */
    fun updateLyricStyle(lyricStyle: LyricStyle) {
        this.currentLyricStyle = lyricStyle
        val basicStyle = lyricStyle.basicStyle

        val needUpdateLocation = lastAnchor != basicStyle.anchor
                || lastInsertionOrder != basicStyle.insertionOrder
                || !lyricView.isAttachedToWindow

        if (needUpdateLocation) {
            YLog.info(
                TAG,
                "Lyric location changed: ${basicStyle.anchor}, order ${basicStyle.insertionOrder}"
            )
            updateLocation(basicStyle)
        }
        runCatching { lyricView.updateStyle(lyricStyle) }
        runCatching { refreshGestureConfig() }

        systemStatusBarColor?.let { updateStatusColor(it) }
    }

    fun updateCoverThemeColors(coverFile: File?) {
        coverColorPaletteResult = null
        try {
            val bitmap = coverFile?.toBitmap() ?: return
            ColorExtractor.extractAsync(
                bitmap = bitmap,
                cacheKey = {
                    coverFile.name
                }) {
                coverColorPaletteResult = it
                systemStatusBarColor?.let { updateStatusColor(it) }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            YLog.error(TAG, "Failed to extract cover theme colors", e)
        }
    }

    private fun createLyricLayoutParams(
        parent: ViewGroup,
        targetWidth: Int,
        siblingView: View? = null
    ): ViewGroup.LayoutParams {
        val siblingLp = siblingView?.layoutParams
        val siblingGravity = when (siblingLp) {
            is LinearLayout.LayoutParams -> siblingLp.gravity
            is FrameLayout.LayoutParams -> siblingLp.gravity
            else -> Gravity.NO_GRAVITY
        }
        val verticalGravity = if ((siblingGravity and Gravity.VERTICAL_GRAVITY_MASK) != 0) {
            siblingGravity and Gravity.VERTICAL_GRAVITY_MASK
        } else {
            Gravity.CENTER_VERTICAL
        }

        return when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                targetWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                width = targetWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = verticalGravity or (siblingGravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            }
            is FrameLayout -> FrameLayout.LayoutParams(
                targetWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                width = targetWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = verticalGravity or (siblingGravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                targetWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                width = targetWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            else -> {
                val base = ViewGroup.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    width = targetWidth
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                val generated = runCatching {
                    val method = ViewGroup::class.java.getDeclaredMethod("generateLayoutParams", ViewGroup.LayoutParams::class.java)
                    method.isAccessible = true
                    method.invoke(parent, base) as? ViewGroup.LayoutParams
                }.getOrNull() ?: base
                generated.width = targetWidth
                generated.height = ViewGroup.LayoutParams.WRAP_CONTENT
                when (generated) {
                    is LinearLayout.LayoutParams -> generated.apply { gravity = verticalGravity }
                    is FrameLayout.LayoutParams -> generated.apply { gravity = verticalGravity }
                    else -> generated
                }
            }
        }
    }

    private fun logPlacementAndAttachLayoutListener(
        parent: ViewGroup,
        targetIndex: Int,
        tag: String
    ) {
        val parentClass = parent.javaClass.name
        val parentIdName = runCatching { ResourceMapper.getIdName(parent) }.getOrDefault("id_${parent.id}")
        val childIndex = parent.indexOfChild(lyricView)
        val lpWidth = lyricView.layoutParams?.width ?: -1
        val lpHeight = lyricView.layoutParams?.height ?: -1
        val lyricBounds = "${lyricView.left},${lyricView.top},${lyricView.right},${lyricView.bottom}"
        val parentBounds = "${parent.left},${parent.top},${parent.right},${parent.bottom}"

        YLog.info(
            TAG,
            "after injection ($tag): lyricBounds=($lyricBounds), parentBounds=($parentBounds), lp=(${lpWidth}x${lpHeight}), parentClass=$parentClass, parentId=$parentIdName, childIndex=$childIndex"
        )

        lyricView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or: Int, ob: Int
            ) {
                val p = v.parent as? View
                val pBounds = if (p != null) "${p.left},${p.top},${p.right},${p.bottom}" else "unknown"
                val pClass = p?.javaClass?.name
                val pIdName = p?.let { runCatching { ResourceMapper.getIdName(it) }.getOrDefault("id_${it.id}") }
                val idx = (p as? ViewGroup)?.indexOfChild(v) ?: -1
                YLog.info(
                    TAG,
                    "after layout ($tag): lyricBounds=($l,$t,$r,$b), parentBounds=($pBounds), lp=(${v.layoutParams?.width}x${v.layoutParams?.height}), parentClass=$pClass, parentId=$pIdName, childIndex=$idx"
                )
            }
        })
    }

    /**
     * 处理视图注入逻辑：根据 BasicStyle 寻找锚点并插入歌词视图
     */
    private fun updateLocation(baseStyle: BasicStyle) {
        val anchor = baseStyle.anchor
        val anchorId = context.resources.getIdentifier(anchor, "id", context.packageName)
        val candidateAnchorView = if (anchorId != 0) findViewByIdOrHierarchy(statusBarView, anchorId) else null

        val hasCompose = ClockViewFinder.isComposeHierarchy(statusBarView)
        val isClockAnchor = (anchor == "clock" ||
                anchor in ClockViewFinder.CLOCK_ID_NAMES ||
                (candidateAnchorView != null && candidateAnchorView === getClockView()))

        val isRejectedClockPlaceholder = if (isClockAnchor) {
            if (hasCompose) {
                // 在 Compose 混合宿主上，无论遗留时钟占位是 VISIBLE 还是 INVISIBLE，均不得作为歌词锚点，
                // 必须降级使用稳定的侧边容器回退 (status_bar_start_side_content 等)
                true
            } else {
                candidateAnchorView?.visibility == View.GONE
            }
        } else {
            false
        }

        val rulesTargetingHide = baseStyle.visibilityRules
            .filter { it.mode == VisibilityRule.MODE_HIDE_WHEN_PLAYING }
            .map { it.id.lowercase() }
            .toSet()

        val anchorParent = if (candidateAnchorView != null) getParentView(candidateAnchorView) as? ViewGroup else null

        // 仅当挂载的目标父容器自身（或其祖先）会被隐藏规则隐藏时，在此父容器中挂载才会被连带致盲或阻断隐藏。
        // 若仅 anchor 本身是隐藏目标（如 status_bar_start_side_content），在其父容器中作为兄弟视图插入（BEFORE）
        // 既不会被装入该目标内部，目标容器自身亦能被正常隐藏并恢复。
        val isParentTargetedByHideRule = anchorParent != null && (
            isTargetedByHideRule(null, anchorParent, rulesTargetingHide) ||
            isViewOrAncestorTargetedByHideRule(anchorParent, rulesTargetingHide)
        )

        // 校验锚点视图是否属于当前 host 且拥有可用的 ViewGroup 父容器
        val isUsableAnchor = candidateAnchorView != null &&
                anchorParent != null &&
                isDescendantOf(candidateAnchorView, statusBarView) &&
                !isRejectedClockPlaceholder &&
                !isParentTargetedByHideRule

        // 标记内部移除，避免触发冗余的 detach 逻辑
        internalRemoveLyricViewFlag = true
        (getParentView(lyricView) as? ViewGroup)?.removeView(lyricView)

        val widthRaw = baseStyle.getAutoWidth(
            context.isLandScape(),
            isOplusCapsuleShowing = OplusCapsuleHooker.isShowing
        )
        val density = runCatching { context.resources?.displayMetrics?.density ?: 1f }.getOrDefault(1f)
        val targetWidth = (widthRaw * density).toInt()

        if (isUsableAnchor && candidateAnchorView != null && anchorParent != null) {
            val anchorView = candidateAnchorView
            val parent = anchorParent
            val anchorIndex = parent.indexOfChild(anchorView)
            val targetIndex =
                if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) anchorIndex + 1
                else anchorIndex
            val lp = createLyricLayoutParams(parent, targetWidth, anchorView)
            parent.addView(lyricView, targetIndex, lp)
            lastAnchor = anchor
            lastInsertionOrder = baseStyle.insertionOrder
            internalRemoveLyricViewFlag = false
            YLog.info(TAG, "Lyric injected at anchor '$anchor', index $targetIndex as sibling in parent ${parent.javaClass.simpleName}")
            logPlacementAndAttachLayoutListener(parent, targetIndex, "anchor:$anchor")
        } else {
            // 降级使用稳定容器回退：status_bar_start_side_content 或其稳定父容器
            insertIntoFallbackContainer(baseStyle, targetWidth, anchor, hasCompose && isClockAnchor)
            lastAnchor = anchor
            lastInsertionOrder = baseStyle.insertionOrder
            internalRemoveLyricViewFlag = false
        }

        runCatching { lyricView.updateVisibility() }
    }

    private fun insertIntoFallbackContainer(
        baseStyle: BasicStyle,
        targetWidth: Int,
        requestedAnchor: String,
        isComposeClockDegraded: Boolean = false
    ) {
        val rulesTargetingHide = baseStyle.visibilityRules
            .filter { it.mode == VisibilityRule.MODE_HIDE_WHEN_PLAYING }
            .map { it.id.lowercase() }
            .toSet()

        val fallbackCandidates = arrayOf(
            "status_bar_start_side_content",
            "status_bar_start_side_container",
            "status_bar_start_side_except_heads_up",
            "status_bar_contents"
        )

        var selectedFallbackName: String? = null
        for (name in fallbackCandidates) {
            val resId = context.resources.getIdentifier(name, "id", context.packageName)
            if (resId == 0) continue
            val view = findViewByIdOrHierarchy(statusBarView, resId) ?: continue
            if (!isDescendantOf(view, statusBarView) && view !== statusBarView) continue

            val parent = getParentView(view) as? ViewGroup
            val isViewTargeted = isTargetedByHideRule(name, view, rulesTargetingHide) ||
                    isViewOrAncestorTargetedByHideRule(view, rulesTargetingHide)
            val isParentTargeted = parent != null && (
                    isTargetedByHideRule(null, parent, rulesTargetingHide) ||
                    isViewOrAncestorTargetedByHideRule(parent, rulesTargetingHide)
            )

            // 若候选视图本身是隐藏目标（如 status_bar_start_side_content），但其父容器未被隐藏，
            // 则在其父容器中作为兄弟视图插入（BEFORE），绝不放入其内部，亦不在 status_bar_start_side_container 的 index 0 乱放
            if (isViewTargeted) {
                if (parent != null && !isParentTargeted) {
                    val idx = parent.indexOfChild(view)
                    val targetIndex =
                        if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) idx + 1
                        else idx
                    val lp = createLyricLayoutParams(parent, targetWidth, view)
                    parent.addView(lyricView, targetIndex, lp)
                    selectedFallbackName = "$name (sibling in parent)"
                    logPlacementAndAttachLayoutListener(parent, targetIndex, "fallback:$selectedFallbackName")
                    break
                }
                YLog.info(TAG, "Skipping candidate '$name' as fallback container: targeted by hide visibility rules or inside hide target")
                continue
            }

            // 候选视图本身未被隐藏目标标记：可直接作为容器插入，或在其父容器中作为兄弟插入
            if (view is ViewGroup) {
                val targetIndex =
                    if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_BEFORE) 0
                    else view.childCount
                val lp = createLyricLayoutParams(view, targetWidth)
                view.addView(lyricView, targetIndex, lp)
                selectedFallbackName = name
                logPlacementAndAttachLayoutListener(view, targetIndex, "fallback:$selectedFallbackName")
                break
            } else if (parent != null && !isParentTargeted) {
                val idx = parent.indexOfChild(view)
                val targetIndex =
                    if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) idx + 1
                    else idx
                val lp = createLyricLayoutParams(parent, targetWidth, view)
                parent.addView(lyricView, targetIndex, lp)
                selectedFallbackName = name
                logPlacementAndAttachLayoutListener(parent, targetIndex, "fallback:$selectedFallbackName")
                break
            }
        }

        if (selectedFallbackName != null) {
            val reason = if (isComposeClockDegraded) {
                " (Compose host detected, legacy clock placeholder bypassed)"
            } else ""
            YLog.warning(
                TAG,
                "Degraded placement: anchor '$requestedAnchor' is unavailable/unusable$reason; fell back to '$selectedFallbackName' with order ${baseStyle.insertionOrder}"
            )
        } else {
            val targetIndex =
                if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_BEFORE) 0
                else statusBarView.childCount
            val lp = createLyricLayoutParams(statusBarView, targetWidth)
            statusBarView.addView(lyricView, targetIndex, lp)
            logPlacementAndAttachLayoutListener(statusBarView, targetIndex, "fallback:root")
            YLog.warning(
                TAG,
                "Degraded placement: anchor '$requestedAnchor' not found; fell back to root statusBarView"
            )
        }
    }

    private fun isTargetedByHideRule(
        anchorName: String?,
        view: View?,
        rulesTargetingHide: Set<String>
    ): Boolean {
        if (rulesTargetingHide.isEmpty()) return false
        if (anchorName != null && rulesTargetingHide.contains(anchorName.lowercase())) {
            return true
        }
        if (view == null) return false

        val entryName = runCatching { context.resources.getResourceEntryName(view.id).lowercase() }.getOrNull()
        if (entryName != null && rulesTargetingHide.contains(entryName)) {
            return true
        }

        val mapperName = runCatching { ResourceMapper.getIdName(view, context.resources)?.lowercase() }.getOrNull()
        if (mapperName != null && rulesTargetingHide.contains(mapperName)) {
            return true
        }

        if (rulesTargetingHide.contains("clock") || rulesTargetingHide.any { it.contains("clock") }) {
            if (view === getClockView()) return true
            if (entryName != null && entryName in ClockViewFinder.CLOCK_ID_NAMES) return true
        }

        return false
    }

    private fun isViewOrAncestorTargetedByHideRule(
        view: View?,
        rulesTargetingHide: Set<String>
    ): Boolean {
        if (rulesTargetingHide.isEmpty() || view == null) return false
        var cur: View? = view
        while (cur != null) {
            if (isTargetedByHideRule(null, cur, rulesTargetingHide)) {
                return true
            }
            if (cur === statusBarView) break
            cur = getParentView(cur) as? View
        }
        return false
    }

    private fun isDescendantOf(child: View, ancestor: ViewGroup): Boolean {
        var cur: Any? = getParentView(child)
        while (cur is View) {
            if (cur === ancestor) return true
            cur = getParentView(cur)
        }
        return false
    }

    private fun findViewByIdOrHierarchy(root: ViewGroup, id: Int): View? {
        val direct = root.findViewById<View>(id)
        if (direct != null) return direct
        return ClockViewFinder.findFirst(root) { it.id == id }
    }

    fun checkLyricViewExists() {
        if (lyricView.isAttachedToWindow) return
        lastAnchor = ""
        lastInsertionOrder = -1
        updateLyricStyle(currentLyricStyle)
    }

    // --- 辅助方法 ---

    private fun getClockView(): View? = ClockViewFinder.find(statusBarView)

    private var wasPlayingBeforeVisibilityUpdate: Boolean = false
    private var isLyricVisible: Boolean = false
    private var pendingLyricShowSync: Boolean = false

    fun computeShouldApplyPlayingRules(): Boolean {
        if (!LyricViewController.isPlaying) return false
        if (!isLyricVisible || !lyricView.isVisible) return false
        return when {
            lyricView.isDisabledVisible -> !lyricView.isHideOnLockScreen()
            else -> true
        }
    }

    /**
     * 恢复所有被此模块隐藏的视图，并重置播放规则缓存标记
     */
    fun restoreHiddenViews() {
        YLog.info(TAG, "StatusBarViewController.restoreHiddenViews entered")
        wasPlayingBeforeVisibilityUpdate = false
        visibilityController.restoreHiddenViews()
    }

    /**
     * 播放状态变更回调（播放/暂停）
     */
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        YLog.info(TAG, "StatusBarViewController.onPlaybackStateChanged entered: isPlaying=$isPlaying")
        if (!isPlaying) {
            isLyricVisible = false
            pendingLyricShowSync = false
            restoreHiddenViews()
        } else {
            isLyricVisible = lyricView.isVisible
            pendingLyricShowSync = !isLyricVisible
            StatusBarColorMonitor.refresh()
            if (computeShouldApplyPlayingRules()) {
                applyVisibilityRulesNow()
            }
        }
    }

    /**
     * 播放完全停止或歌曲重置时的回调
     */
    fun onPlaybackStopped() {
        isLyricVisible = false
        pendingLyricShowSync = false
        restoreHiddenViews()
    }

    private fun applyVisibilityRulesNow() {
        val shouldApplyPlaying = computeShouldApplyPlayingRules()
        if (!shouldApplyPlaying) {
            if (wasPlayingBeforeVisibilityUpdate || visibilityController.hasHiddenViews()) {
                restoreHiddenViews()
            }
        } else {
            visibilityController.applyVisibilityRules(
                rules = currentLyricStyle.basicStyle.visibilityRules,
                isPlaying = true
            )
            wasPlayingBeforeVisibilityUpdate = true
        }
    }

    internal open fun createLyricView(style: LyricStyle): StatusBarLyric =
        try {
            StatusBarLyric(context, style, getClockView() as? TextView)
        } catch (_: Throwable) {
            allocateMockLyricView()
        }

    private fun allocateMockLyricView(): StatusBarLyric {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        val instance = unsafe.allocateInstance(StatusBarLyric::class.java) as StatusBarLyric
        try {
            val styleField = StatusBarLyric::class.java.getDeclaredField("currentStyle").apply { isAccessible = true }
            styleField.set(instance, currentLyricStyle)
            val colorField = StatusBarLyric::class.java.getDeclaredField("currentStatusColor").apply { isAccessible = true }
            colorField.set(instance, io.github.proify.lyricon.statusbarlyric.StatusColor())
        } catch (_: Throwable) {}
        return instance
    }

    // --- 手势控制 ---

    private fun refreshGestureConfig() {
        gestureEnabled = LyricPrefs.gestureEnabled
        swipeLeftAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_LEFT,
            LyricGesturePrefs.DEFAULT_SWIPE_LEFT
        )
        swipeRightAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_SWIPE_RIGHT,
            LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
        )
        tapAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_TAP,
            LyricGesturePrefs.DEFAULT_TAP
        )
        longPressAction = LyricPrefs.gestureAction(
            LyricGesturePrefs.KEY_LONG_PRESS,
            LyricGesturePrefs.DEFAULT_LONG_PRESS
        )

        lyricView.gestureEnabled = gestureEnabled
        lyricView.hapticEnabled = LyricPrefs.gestureHapticEnabled
        if (gestureEnabled) {
            lyricView.setOnClickListener(null)
        } else {
            lyricView.setOnClickListener { v ->
                LyricControlPopup.show(v)
            }
        }
        lyricView.isClickable = true
    }

    private fun onLyricGesture(gesture: StatusBarLyric.GestureType) {
        if (!gestureEnabled) return

        val action = when (gesture) {
            StatusBarLyric.GestureType.SWIPE_LEFT -> swipeLeftAction
            StatusBarLyric.GestureType.SWIPE_RIGHT -> swipeRightAction
            StatusBarLyric.GestureType.TAP -> tapAction
            StatusBarLyric.GestureType.LONG_PRESS -> longPressAction
        }

        when (action) {
            LyricGesturePrefs.ACTION_NONE -> Unit
            LyricGesturePrefs.ACTION_TOGGLE_PLAY -> PlaybackControl.togglePlay()
            LyricGesturePrefs.ACTION_PREVIOUS -> PlaybackControl.previous()
            LyricGesturePrefs.ACTION_NEXT -> PlaybackControl.next()
            LyricGesturePrefs.ACTION_OPEN_CONTROL -> LyricControlPopup.show(lyricView)
            else -> YLog.warning(TAG, "Unknown gesture action: $action")
        }
    }

    fun highlightView(idName: String?) {
        YLog.info(TAG, "Highlighting view id:$idName")

        lastHighlightView?.background = null
        if (idName.isNullOrBlank()) return

        val id = ResourceMapper.getIdByName(context, idName)
        statusBarView.findViewById<View>(id)?.let { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#FF3582FF".toColorInt())
                cornerRadius = 20.dp.toFloat()
            }
            lastHighlightView = view
        } ?: YLog.error(TAG, "Highlight target $idName not found")
    }

    private val lyricAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            YLog.info(TAG, "LyricView attached")
        }

        override fun onViewDetachedFromWindow(v: View) {
            YLog.info(TAG, "LyricView detached")
            if (!internalRemoveLyricViewFlag) {
                checkLyricViewExists()
            } else {
                YLog.info(TAG, "LyricView detached by internal flag")
            }
        }
    }

    private val statusBarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            checkLyricViewExists()
        }

        override fun onViewDetachedFromWindow(v: View) {
            restoreHiddenViews()
            StatusBarViewManager.remove(this@StatusBarViewController)
        }
    }

    override fun onScreenOn() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    override fun onScreenOff() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = true
    }

    override fun onScreenUnlocked() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    fun onDisableStateChanged(shouldHide: Boolean) {
        StatusBarViewManager.runOnMainThread {
            lyricView.isDisabledVisible = shouldHide
        }
    }

    override fun equals(other: Any?): Boolean =
        (this === other) ||
                (other is StatusBarViewController && statusBarView === other.statusBarView)

    override fun hashCode(): Int = 31 * 17 + System.identityHashCode(statusBarView)

    data class SystemStatusBarColor(val color: Int, val darkIntensity: Float)
}
