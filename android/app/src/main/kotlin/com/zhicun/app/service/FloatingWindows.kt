package com.zhicun.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView

private const val TAG = "FloatingBubble"

/**
 * 悬浮窗的 WindowManager 工具（从 FloatingBubbleService 拆出，无状态纯工具）。
 *
 * 失败兜底的设计共识：气泡是锦上添花的功能，任何一步失败都不该把进程炸掉。
 */

/** 悬浮窗通用 LayoutParams（气泡与面板共用）。 */
internal fun newFloatingLayoutParams(): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE：不抢输入焦点，否则用户在别的应用打字会莫名其妙失焦。
        // LAYOUT_NO_LIMITS：允许拖到边缘之外一点点，避免贴边时被系统裁掉。
        // LAYOUT_IN_SCREEN：坐标以整块屏幕为准，不随状态栏收缩。
        // WATCH_OUTSIDE_TOUCH：气泡之外的任何触摸**开始**时，窗口会收到一个
        //   ACTION_OUTSIDE（2026-10-24 用户反馈：气泡收起不再靠计时，而是
        //   "别人一摸屏幕就收到旁边"）。面板也靠它实现"点外面就关"。
        //   注意它只投递该手势的第一个 DOWN，滑动过程不会连续通知 —— 收起
        //   是一次性动作，一次就够。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

/**
 * 让悬浮窗感知"窗外触摸"（2026-10-24 用户反馈：气泡收起改为事件驱动）。
 *
 * `ACTION_OUTSIDE` 在 Compose 的事件管道里没有归宿（它不是手势，是窗口通知），
 * 所以在 View 层拦。监听器**返回 false**：事件继续走正常管道交给 Compose，
 * 气泡自身的点击/拖拽不受任何影响（它们本来也不是 OUTSIDE）。
 *
 * `ClickableViewAccessibility` 抑制理由：`ACTION_OUTSIDE` 是窗口通知，没有"点击"语义，
 * 也不存在需要 `performClick()` 补发的点击事件。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun ComposeView.watchOutsideTouch(onOutside: (MotionEvent) -> Unit) {
    setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutside(event)
        false
    }
}

/**
 * 可用视口尺寸（像素）。
 *
 * API 30+ 用 `currentWindowMetrics`（能正确反映分屏/挖孔的实际可用区域）；
 * 更低版本退回 `displayMetrics`。两条分支都显式判断版本，不直接调高版本专有 API。
 */
internal fun Service.floatingViewportSize(): Pair<Int, Int> {
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * `addView` 的失败不能把进程炸掉。
 *
 * 权限刚被撤销、或 ROM 对 `TYPE_APPLICATION_OVERLAY` 有额外限制时，这里会抛
 * `SecurityException`/`BadTokenException`。失败的正确反应是"不显示并停掉服务"，
 * 而不是让用户看到崩溃弹窗，因此致命失败通过 [onFatal]（调用方一般传 `stopSelf`）回调。
 */
internal fun WindowManager.addViewSafely(
    view: View,
    params: WindowManager.LayoutParams,
    onFatal: () -> Unit,
) {
    try {
        addView(view, params)
    } catch (e: Exception) {
        Log.e(TAG, "添加悬浮窗失败", e)
        onFatal()
    }
}

internal fun WindowManager.updateViewSafely(view: View, params: WindowManager.LayoutParams) {
    try {
        updateViewLayout(view, params)
    } catch (e: Exception) {
        Log.w(TAG, "更新悬浮窗位置失败", e)
    }
}

internal fun WindowManager.removeViewSafely(view: View) {
    try {
        removeView(view)
    } catch (e: Exception) {
        // 已经被系统移除（权限撤销）时会抛，忽略即可
        Log.w(TAG, "移除悬浮窗失败", e)
    }
}
