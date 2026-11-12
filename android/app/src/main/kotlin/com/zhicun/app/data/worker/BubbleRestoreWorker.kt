package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.service.BootRestoreResult
import com.zhicun.app.service.FloatingBubbleController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 开机恢复悬浮气泡的**延迟重试**（2026-11-10 Bug 2）。
 *
 * ## 为什么需要它
 * `BOOT_COMPLETED` 里直接重启前台服务在 Android 12+ / API 36 上会被系统拒绝
 * （`ForegroundServiceStartNotAllowedException: mAllowStartForeground false`），
 * 原实现把异常吞掉后什么都不做，用户重启手机后气泡永久消失，直到手动打开一次 App。
 *
 * 广播里拒一次不代表之后永远拒：系统刚完成启动时进程状态还没落定，隔几十秒再试
 * 有机会成功；厂商 ROM 对后台启动的判定也常带时间窗。因此把"再试几次"交给
 * WorkManager（它比广播更晚、可在 Doze 之外执行）。
 *
 * ## 为什么不无限重试
 * 三次之后仍被拒，说明这台设备就是不允许后台拉起 —— 继续排等于无意义地消耗电量，
 * 也会让 WorkManager 的失败统计里堆满同一件事。此时留一条明确日志，
 * 由「用户下次打开 App 时 `MainActivity.onCreate` 的 sync」兜底（那条路径处于前台，必成功）。
 */
@HiltWorker
class BubbleRestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: FloatingBubbleSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when (val outcome = FloatingBubbleController.restoreOnBoot(applicationContext, settings)) {
            BootRestoreResult.STARTED -> {
                Log.i(TAG, "第 ${runAttemptCount + 1} 次尝试已恢复悬浮气泡")
                Result.success()
            }

            // 用户在这期间关掉了气泡或开机恢复：重试没有意义，静默收工
            BootRestoreResult.SKIPPED -> {
                Log.i(TAG, "开关已关闭，不再重试恢复气泡")
                Result.success()
            }

            BootRestoreResult.BLOCKED -> {
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    Log.e(
                        TAG,
                        "连续 $MAX_ATTEMPTS 次后台启动前台服务都被系统拒绝，" +
                            "停止重试；气泡将在用户下次打开智存时恢复",
                    )
                    Result.failure()
                } else {
                    Log.w(TAG, "第 ${runAttemptCount + 1} 次仍被系统拒绝，退避后重试")
                    Result.retry()
                }
            }
        }

    private companion object {
        const val TAG = "BubbleRestoreWorker"

        /** 与 `WorkManagerBubbleRestoreScheduler` 的次数口径一致（含首次执行）。 */
        const val MAX_ATTEMPTS = 3
    }
}