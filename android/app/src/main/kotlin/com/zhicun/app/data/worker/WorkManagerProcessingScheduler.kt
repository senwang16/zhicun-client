package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.worker.ProcessingScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ProcessingScheduler] 的 WorkManager 实现（M6）。
 *
 * 三个关键配置：
 * - **唯一任务名** + `ExistingWorkPolicy.KEEP`：同一 `recordId + jobType` 只会有一个任务在排，
 *   重复提交直接命中已有任务（ARCHITECTURE §6.4 幂等）；
 * - **指数退避**：与 Executor 里的 `nextRetryAt` 是两层保险，这里决定"多久后再叫醒 Worker"；
 * - **网络约束按任务类型区分**：网页提取必须联网，OCR 是本地模型不该被网络约束卡住 ——
 *   把 OCR 也要求网络的话，飞行模式下截图就永远处理不了。
 */
@Singleton
class WorkManagerProcessingScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProcessingScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun enqueue(recordId: String, jobType: JobType) {
        submit(recordId, jobType, ExistingWorkPolicy.KEEP)
    }

    /**
     * 用户显式重试：`REPLACE` 会取消同名任务（包括正在运行或正在退避等待的那个）后重新投递。
     *
     * 为什么不能复用 `KEEP`：`KEEP` 对"还在队列里"的任务一律保留，包括正卡在
     * WorkManager 指数退避里的任务 —— 用户点了重试，队列里那个旧的还在，新请求被丢弃，
     * 界面只能干等旧任务自己到点（实测出现过半小时）。见 [ProcessingScheduler.reenqueue]。
     */
    override fun reenqueue(recordId: String, jobType: JobType) {
        submit(recordId, jobType, ExistingWorkPolicy.REPLACE)
    }

    /**
     * 延迟重排（429 `Retry-After`）：用 `APPEND_OR_REPLACE` + `initialDelay`。
     *
     * 为什么不是 `REPLACE`：本方法由**正在运行**的 Worker 调用，`REPLACE` 会取消当前
     * 这个任务，`doWork()` 随后的成功回执可能被丢弃。`APPEND_OR_REPLACE` 把新任务挂在
     * 当前链尾，等本次结束后再按 initialDelay 起跑；用户点重试时 `REPLACE` 仍会整链取消。
     */
    override fun reenqueueAfter(recordId: String, jobType: JobType, delayMs: Long) {
        submit(
            recordId = recordId,
            jobType = jobType,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            initialDelayMs = delayMs.coerceAtLeast(0L),
        )
    }

    private fun submit(
        recordId: String,
        jobType: JobType,
        policy: ExistingWorkPolicy,
        initialDelayMs: Long = 0L,
    ) {
        try {
            val request = OneTimeWorkRequest.Builder(workerClassFor(jobType))
                .setInputData(
                    workDataOf(
                        ProcessingWorkKeys.RECORD_ID to recordId,
                        ProcessingWorkKeys.JOB_TYPE to jobType.name,
                    ),
                )
                .setConstraints(constraintsFor(jobType))
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(ProcessingWorkKeys.WORK_TAG)
                .build()

            workManager.enqueueUniqueWork(
                ProcessingWorkKeys.uniqueWorkName(recordId, jobType),
                policy,
                request,
            )
            Log.i(
                TAG,
                "已提交任务 recordId=$recordId type=$jobType policy=$policy delayMs=$initialDelayMs",
            )
        } catch (e: Exception) {
            // 调度失败不能向上抛：调用方（SaveRecordUseCase）此刻已经把记录写进库了，
            // 抛出去会让用户看到"保存失败"，而内容其实在。这里记录原因，任务表里的
            // QUEUED 状态会在下次保存（ScheduleProcessingUseCase 的补投分支）或
            // 下次冷启动（RecoverPendingJobsUseCase）时被补投。
            Log.e(TAG, "提交任务失败 recordId=$recordId type=$jobType policy=$policy", e)
        }
    }

    override fun cancel(recordId: String, jobType: JobType) {
        workManager.cancelUniqueWork(ProcessingWorkKeys.uniqueWorkName(recordId, jobType))
    }

    /**
     * 任务类型 → Worker 类。
     *
     * 已接入：OCR → [OcrWorker]（M7）、WEBPAGE_EXTRACT → [WebExtractWorker]（M8）、
     * TRANSCRIPTION → [TranscribeWorker]（M9）、SUMMARIZE → [SummarizeWorker]（M11）、
     * EMBED → [EmbedWorker]（M12）。五种任务类型至此全部落地，[MockProcessingWorker]
     * 只作为"将来新增类型但忘了接线"时的兜底。
     */
    internal fun workerClassFor(jobType: JobType): Class<out ListenableWorker> = when (jobType) {
        JobType.OCR -> OcrWorker::class.java
        JobType.WEBPAGE_EXTRACT -> WebExtractWorker::class.java
        JobType.TRANSCRIPTION -> TranscribeWorker::class.java
        JobType.SUMMARIZE -> SummarizeWorker::class.java
        JobType.EMBED -> EmbedWorker::class.java
    }

    /**
     * 只有需要访问网络的任务才加网络约束。
     *
     * OCR 是本地能力（T-005 捆绑模型），加网络约束会让离线场景永久卡住；
     * 语音转写自 M9 起走云端网关（T-006），因此**必须**要求网络 ——
     * 否则飞行模式下任务会被立刻执行并立刻失败，白白消耗三次重试机会。
     */
    private fun constraintsFor(jobType: JobType): Constraints {
        val needsNetwork = when (jobType) {
            JobType.WEBPAGE_EXTRACT,
            JobType.SUMMARIZE,
            JobType.EMBED,
            JobType.TRANSCRIPTION,
            -> true

            JobType.OCR -> false
        }
        return Constraints.Builder()
            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
    }

    private companion object {
        const val TAG = "WorkScheduler"
        const val BACKOFF_SECONDS = 30L
    }
}
