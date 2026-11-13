package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.zhicun.app.domain.worker.BubbleRestoreScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BubbleRestoreScheduler] 的 WorkManager 实现（2026-11-10 Bug 2）。
 *
 * ## 为什么用 expedited
 * 恢复气泡是"开机后尽早做"的小动作，普通任务可能被排到很久以后（甚至等充电/待机窗口）。
 * expedited 让它尽快拿到一次执行机会；配额不足时由 `RUN_AS_NON_EXPEDITED_WORK_REQUEST`
 * 退回普通任务，不会因为配额把恢复丢掉。
 *
 * ## 为什么没有 initial delay
 * `setExpedited` 与 `setInitialDelay` 不能同时用（WorkManager 会直接拒绝该请求）。
 * 间隔由 `Result.retry()` 的退避承担：首次立刻试、之后 30s、60s ——
 * 正好覆盖"开机后进程状态尚未落定"的那一小段时间。
 *
 * ## 幂等
 * `enqueueUniqueWork(..., KEEP, ...)`：`BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`
 * 在极少数场景会先后到达，`KEEP` 保证只留一条重试链。
 */
@Singleton
class WorkManagerBubbleRestoreScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BubbleRestoreScheduler {

    override fun scheduleRetry() {
        try {
            val request = OneTimeWorkRequestBuilder<BubbleRestoreWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            // 与处理任务同一取向：调度失败不上抛。用户下次打开 App 时仍会 sync 一次。
            Log.e(TAG, "气泡恢复重试提交失败", e)
        }
    }

    private companion object {
        const val TAG = "BubbleRestoreScheduler"
        const val UNIQUE_WORK_NAME = "floating-bubble-boot-restore"
        const val WORK_TAG = "floating-bubble-restore"

        /** WorkManager 要求退避至少 10 秒；30 秒能覆盖开机后最常见的状态切换。 */
        const val BACKOFF_SECONDS = 30L
    }
}