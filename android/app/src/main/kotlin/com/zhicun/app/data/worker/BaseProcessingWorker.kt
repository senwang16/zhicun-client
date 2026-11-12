package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.worker.ProcessorResult

/**
 * 所有处理 Worker 的基类（M6）。
 *
 * 只做三件事：从 `inputData` 读参数、交给 [ProcessingJobExecutor] 执行、把结论翻译成
 * WorkManager 的 `Result`。**幂等、重试、状态同步都不在这里** —— 那些规则在 Executor 里，
 * 因为 `CoroutineWorker` 的构造依赖 `WorkerParameters`，规则写在里面就只能在
 * WorkManager 测试环境里验，而它们本质上是纯业务逻辑。
 *
 * 子类只需要实现 [perform]。
 */
abstract class BaseProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    /** `protected` 而不是 `private`：子类外发前要用 [ProcessingJobExecutor.recheckBeforeUpload] 做二次判闸。 */
    protected val executor: ProcessingJobExecutor,
) : CoroutineWorker(appContext, params) {

    /**
     * 具体处理逻辑（OCR / 网页提取…）。
     *
     * 允许抛异常：Executor 会按异常类型区分"可重试"与"确定性失败"，实现方不必自己 try/catch。
     */
    protected abstract suspend fun perform(record: Record): ProcessorResult

    final override suspend fun doWork(): Result {
        val recordId = inputData.getString(ProcessingWorkKeys.RECORD_ID)
        val jobTypeName = inputData.getString(ProcessingWorkKeys.JOB_TYPE)
        if (recordId.isNullOrBlank() || jobTypeName.isNullOrBlank()) {
            // 参数缺失说明调度侧写错了，重试一万次也不会出现参数 —— 直接失败并留痕
            Log.e(TAG, "Worker 缺少必要参数，放弃执行 workId=$id")
            return Result.failure()
        }

        val jobType = runCatching { JobType.valueOf(jobTypeName) }.getOrNull()
        if (jobType == null) {
            Log.e(TAG, "未知的任务类型 jobType=$jobTypeName，放弃执行 workId=$id")
            return Result.failure()
        }

        val outcome = executor.execute(recordId = recordId, jobType = jobType) { record ->
            perform(record)
        }

        return when (outcome) {
            // Done（完成）/ Skipped（已完成）/ NoJob（任务被删）/ Cancelled（策略性不上传）
            // 都不是失败：没有工作可做而已
            ExecutionOutcome.Done,
            ExecutionOutcome.Skipped,
            ExecutionOutcome.NoJob,
            is ExecutionOutcome.Cancelled,
            -> Result.success()

            is ExecutionOutcome.Retry -> Result.retry()

            // 限流延迟重排：执行器已经排好带 initialDelay 的新任务，本次直接结束。
            // 这里**不能**返回 Result.retry()，否则 WorkManager 会再叠一层 30s 退避，
            // 把 Retry-After 的等待时间冲掉。
            is ExecutionOutcome.Deferred -> Result.success()

            is ExecutionOutcome.Failed -> Result.failure()
        }
    }

    private companion object {
        const val TAG = "BaseWorker"
    }
}
