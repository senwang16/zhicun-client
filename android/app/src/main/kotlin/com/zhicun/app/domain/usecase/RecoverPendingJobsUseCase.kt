package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.worker.ProcessingScheduler
import javax.inject.Inject

/**
 * 启动恢复：把"应该跑但没在跑"的后台任务重新投给调度器（M6 审核整改）。
 *
 * ## 为什么需要它
 * `WorkManagerProcessingScheduler.enqueue` 的注释一直写着"调度失败的任务会在下次保存/启动时补投"，
 * 但整改前**只实现了"下次保存"这一半**：如果首次调度失败（WorkManager 未就绪、进程被杀），
 * 用户之后恰好不再保存任何东西，这条任务就会永远停在 `QUEUED`，后台处理再也不会发生，
 * 而界面上只看到一条永远"待处理"的记录。审核指出的是真问题。
 *
 * ## 两类任务都要救，但方式不同
 * - `QUEUED`：任务表说"等着跑"，但队列里可能根本没有它 —— 直接补投。
 *   `ExistingWorkPolicy.KEEP` 保证真有在排的任务时不会重复执行。
 * - `RUNNING`：进程被杀时任务会停在这个状态（WorkManager 认为任务已交付）。
 *   不主动复位就永远不会再被投递，属于同一个"永久卡住"家族的 bug，
 *   因此这里一并复位成 `QUEUED` 再补投。`attempts` 保持不变 —— 它是"已经试过几次"的事实，
 *   复位不该白送新的重试次数，否则反复崩溃会变成无限重试。
 *
 * ## 幂等
 * 本用例可以被反复调用（每次冷启动/进程重建都会调）。重复调用的后果只是
 * 对同一批任务多调几次 `enqueue`，而 `KEEP` 会把它们合并成一个。
 */
class RecoverPendingJobsUseCase @Inject constructor(
    private val jobRepository: ProcessingJobRepository,
    private val scheduler: ProcessingScheduler,
) {

    /**
     * @return 本次补投的任务数（仅用于日志/排查，调用方无需处理）
     */
    suspend operator fun invoke(): Int {
        // 先把 RUNNING 复位成 QUEUED，再统一按 QUEUED 查一次投递 ——
        // 分两轮各自投递会让复位出来的任务被投两次，没必要（虽然 KEEP 能兜住）。
        jobRepository.getByStatuses(listOf(JobStatus.RUNNING)).forEach { job ->
            jobRepository.updateStatus(
                job.copy(
                    status = JobStatus.QUEUED,
                    // startedAt 清掉：它表示"这一次执行的开始时刻"，重新排队后旧值会误导排查
                    startedAt = null,
                ),
            )
        }

        val pending = jobRepository.getByStatuses(listOf(JobStatus.QUEUED))
        pending.forEach { job -> scheduler.enqueue(job.recordId, job.jobType) }
        return pending.size
    }
}
