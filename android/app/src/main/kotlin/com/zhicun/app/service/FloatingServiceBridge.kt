package com.zhicun.app.service

import android.util.Log

private const val TAG = "FloatingBridge"

/**
 * 悬浮服务与两个透明中转页 / 气泡手势之间的静态入口（2026-10-27）。
 *
 * 从 `FloatingBubbleService.Companion` 原样搬出，仅为守住 AGENTS.md §2.1 的
 * 单文件 400 行上限；不改变任何行为。扩展函数挂在伴生对象上，
 * 调用方仍写作 `FloatingBubbleService.xxx()`。
 */

/**
 * 截图链路：把宿主前台服务升级为 `mediaProjection` 类型。
 *
 * API 34+ 要求 `getMediaProjection` 之前服务已带该类型，否则抛 SecurityException。
 * 返回 false 表示服务不在（气泡本来就没开）或系统拒绝升级 —— 调用方不得继续截屏。
 */
internal fun FloatingBubbleService.Companion.raiseMediaProjectionForeground(): Boolean {
    val service = instance ?: return false
    return try {
        service.startFloatingForeground(
            withMicrophone = service.recording.recording.value,
            withMediaProjection = true,
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "前台服务升级 mediaProjection 失败", e)
        false
    }
}

/** 截图结束：降回 specialUse（保留录音中的 microphone 类型），不降会一直占用截屏类型。 */
internal fun FloatingBubbleService.Companion.downgradeMediaProjectionForeground() {
    val service = instance ?: return
    try {
        service.startFloatingForeground(withMicrophone = service.recording.recording.value)
    } catch (e: Exception) {
        Log.e(TAG, "前台服务类型降级失败", e)
    }
}

/** 长按判定成立：开始悬浮录音（服务没在跑时忽略；开关为关由控制器拦下）。 */
internal fun FloatingBubbleService.Companion.startRecording() {
    instance?.recording?.start()
}

/** 录音态抬手：停止并保存。 */
internal fun FloatingBubbleService.Companion.stopRecording() {
    instance?.recording?.stop()
}

/** 麦克风权限中转页返回结果（2026-10-26）。授权后由用户再次长按，**不**自动续录。 */
internal fun FloatingBubbleService.Companion.onMicPermissionResult(granted: Boolean) {
    Log.i(TAG, "麦克风权限结果 granted=$granted")
}
