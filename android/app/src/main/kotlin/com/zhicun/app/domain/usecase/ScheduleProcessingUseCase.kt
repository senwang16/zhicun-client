package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.domain.worker.ProcessingScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * 根据记录内容决定要跑哪些后台任务，并把它们放进队列（M6 建立，M11 加敏感门禁，M14 加云端开关）。
 *
 * 幂等约定（ARCHITECTURE §6.4）：`recordId + jobType` 唯一确定一个任务。
 * 因此这里**先查再插**，并只在"没有任务"或"任务丢了（仍是 QUEUED 但没在跑）"时提交调度，
 * 重复保存/重试都不会让同一件事跑两遍。
 *
 * ## M11：疑似敏感内容不上传 / M14：用户关掉云端 AI 时不上传
 * PRD §6.4 要求"疑似敏感时暂停自动上传，用户确认后才继续"。落实在**调度侧**而不是
 * 执行侧，是因为一旦排进队列，补投/重试等路径都可能绕过执行侧的判断；不排任务就没有
 * 任何一条路径能把它悄悄传上去。M14 的云端开关用同一条判据、同一个落点。
 *
 * 被挡下的记录标记为 [ProcessingStatus.LOCAL_ONLY]：界面据此显示"仅本地"，
 * 而不是显示成"处理失败"（内容确实好好地存在本地）。
 *
 * 注意"被挡下"与"没有可跑任务"不是一回事：前者是**有云端任务但被策略拒绝**（要标记
 * LOCAL_ONLY 让用户知道为什么没有摘要），后者是这张映射表里本来就没任务。
 */
class ScheduleProcessingUseCase @Inject constructor(
    private val jobRepository: ProcessingJobRepository,
    private val scheduler: ProcessingScheduler,
    private val recordRepository: RecordRepository,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
) {

    /**
     * 提交该记录需要的全部后台任务。
     *
     * 没有 `now` 参数：`ProcessingJob` 只有"开始/结束/下次重试"三个时间，
     * 入队时刻不需要落库（`QUEUED` 本身已经表达了"还没开始"）。
     */
    suspend operator fun invoke(record: Record) {
        // 拒绝理由对用户来说不同（关了的开关 / 未配置 / 内容敏感），但对"排不排任务"
        // 是同一个决定。判据统一走 CloudUploadGate，避免与 Worker 侧各写一份后逐渐走样。
        // 合集要按成员的实时并集判闸（M20），gateRecord 对其他类型原样返回
        val cloudBlocked = CloudUploadGate.blockReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
            record = recordRepository.gateRecord(record),
        ) != null
        var blockedCloudJob = false

        jobsFor(record.type).forEach { jobType ->
            if (jobType in ProcessingJobTargets.CLOUD_JOBS && cloudBlocked) {
                blockedCloudJob = true
                return@forEach
            }

            val existing = jobRepository.getByRecordAndType(record.id, jobType)
            when {
                existing == null -> {
                    jobRepository.insert(newJob(record.id, jobType))
                    scheduler.enqueue(record.id, jobType)
                }

                // 任务存在但状态还是 QUEUED：说明调度可能丢了（进程被杀 / 系统清理），补投一次。
                // ExistingWorkPolicy.KEEP 保证真有在排的任务时不会重复。
                existing.status == JobStatus.QUEUED -> scheduler.enqueue(record.id, jobType)

                // RUNNING/DONE/CANCELLED/FAILED 都不再投递：
                // FAILED 是重试耗尽后的终态，自动重投会变成无限重试；要重试必须用户显式操作。
                else -> Unit
            }
        }

        if (blockedCloudJob) {
            // 只改状态，不抛异常：保存本身是成功的，用户不该看到"保存失败"
            recordRepository.updateProcessingStatus(
                record.id,
                ProcessingStatus.LOCAL_ONLY,
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * 记录类型 → 任务类型。
     *
     * 映射表在 [ProcessingJobTargets] 里，与"重新识别"共用同一份，
     * 避免自动调度与手动重试两处各写一份 `when` 而逐渐错位。
     */
    internal fun jobsFor(type: RecordType): List<JobType> = ProcessingJobTargets.of(type)

    private fun newJob(recordId: String, jobType: JobType) = ProcessingJob(
        id = UUID.randomUUID().toString(),
        recordId = recordId,
        jobType = jobType,
        status = JobStatus.QUEUED,
        attempts = 0,
        maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
        nextRetryAt = null,
        errorCategory = null,
        startedAt = null,
        finishedAt = null,
    )
}
