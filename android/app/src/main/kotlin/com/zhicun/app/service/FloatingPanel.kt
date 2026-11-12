package com.zhicun.app.service

import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.zhicun.app.domain.floating.PanelGeometry
import com.zhicun.app.presentation.floating.QuickCapturePanel
import com.zhicun.app.presentation.floating.px
import com.zhicun.app.presentation.theme.AppTheme

// 本文件承接从 FloatingBubbleService.kt 原样移出的「快捷面板窗口」区块
// （AGENTS.md §2.1 单文件 400 行上限）。纯移动、零行为变更。
//
// 与 FloatingBubbleWindow.kt 同样采用扩展函数：面板的定位要读气泡窗口的
// LayoutParams 与形态状态，拆独立类需要额外注入四五个依赖，改动面过大。

/** 面板与气泡之间的间隙（dp）。原为 Service 伴生常量，随本区块一并搬来。 */
private const val PANEL_GAP_DP = 8

/**
 * 面板关闭后暂不接受「点气泡」的宽限期（毫秒）。
 *
 * 面板开着时点气泡，两个窗口会同时收到这次 DOWN：面板拿 `ACTION_OUTSIDE`，
 * 气泡拿点击。若 OUTSIDE 先到并关掉面板，气泡的点击随后执行 `togglePanel()`，
 * 此时 `expanded` 已是 false，会走成"读剪贴板 → 为空 → 又把面板弹开"——
 * 用户看到的就是面板关了又开、点不掉。这个宽限期让紧随其后的点击变成空操作。
 */
internal const val PANEL_DISMISS_GRACE_MS = 350L

internal fun FloatingBubbleService.expandPanel() {
    if (panelView != null) return

    val view = ComposeView(this).apply {
        // 点面板外 = 关面板。但**点在气泡上不算"外面"**（2026-11-08 用户反馈：
        // 面板开着时点气泡关不掉）：那一击应交给气泡自己的点击去 toggle，
        // 这里直接放行，避免两个窗口抢同一次手势。
        watchOutsideTouch { event ->
            if (!isOnBubble(event)) {
                panelDismissedAt = SystemClock.elapsedRealtime()
                collapsePanel()
            }
        }
    }
    composeHost.installOn(view)
    view.setContent {
        AppTheme {
            QuickCapturePanel(onAction = { action -> onAction(action) })
        }
    }

    val params = newFloatingLayoutParams().apply {
        // 面板要能接收点击，但不能抢焦点（否则输入法/返回键行为会被它截走）
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    windowManager.addViewSafely(view, params) { stopSelf() }
    panelView = view
    updateBubbleForm { it.copy(expanded = true) }

    // 面板尺寸要等首次布局完成才知道，因此延后一帧再按气泡位置贴上去
    view.post { positionPanel() }
}

internal fun FloatingBubbleService.collapsePanel() {
    removePanel()
    updateBubbleForm { it.copy(expanded = false) }
}

internal fun FloatingBubbleService.removePanel() {
    panelView?.let { windowManager.removeViewSafely(it) }
    panelView = null
}

/**
 * 面板紧贴气泡、靠屏幕内侧展开；水平放不下时改为垂直错开（见 [PanelGeometry]）。
 *
 * 2026-11-08：原实现只有"水平内侧 + 夹进屏幕"，窄屏分屏/横屏时夹完会压住气泡，
 * 用户点气泡实际点到了面板，菜单既关不掉也不响应。定位规则已抽成纯函数并单测。
 */
internal fun FloatingBubbleService.positionPanel() {
    val panel = panelView ?: return
    val params = panel.layoutParams as? WindowManager.LayoutParams ?: return
    if (panel.width <= 0 || panel.height <= 0) return

    val (screenWidth, screenHeight) = floatingViewportSize()
    val gap = PANEL_GAP_DP.dp.px(this)
    // 用内存里的形态状态而不是 prefs：两者应当一致，但只有前者是"此刻"的真值
    val placement = PanelGeometry.place(
        edge = bubbleForm.value.edge,
        bubbleX = bubbleParams.x,
        bubbleY = bubbleParams.y,
        bubbleWidth = bubbleParams.width,
        bubbleHeight = bubbleParams.height,
        panelWidth = panel.width,
        panelHeight = panel.height,
        viewportWidth = screenWidth,
        viewportHeight = screenHeight,
        gap = gap,
    )

    params.x = placement.x
    params.y = placement.y
    windowManager.updateViewSafely(panel, params)
}

/**
 * 这次窗外触摸是不是点在气泡上。
 *
 * `ACTION_OUTSIDE` 的坐标在个别 ROM 上会退化为 (0,0)：那种情况下按原行为关闭面板，
 * 只是多出一次"关了又开"的风险，已由 [PANEL_DISMISS_GRACE_MS] 兜住。
 */
private fun FloatingBubbleService.isOnBubble(event: MotionEvent): Boolean {
    if (bubbleView == null) return false
    val padding = PANEL_GAP_DP.dp.px(this) / 2
    return PanelGeometry.isOnBubble(
        bubbleX = bubbleParams.x,
        bubbleY = bubbleParams.y,
        bubbleWidth = bubbleParams.width,
        bubbleHeight = bubbleParams.height,
        x = event.rawX.toInt(),
        y = event.rawY.toInt(),
        padding = padding,
    )
}