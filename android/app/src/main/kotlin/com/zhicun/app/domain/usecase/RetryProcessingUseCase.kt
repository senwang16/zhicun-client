package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.settings.DefaultLanguagePreference
import com.zhicun.app.domain.settings.LanguagePreference
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.domain.worker.ProcessingScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * 用户主动要求"重新处理"一条记录（M7 详情页的重试按钮）。
 *
 * 与 `ScheduleProcessingUseCase` 的关键差别：**那个不会重投 FAILED 的任务**
 * （重试耗尽的终态必须由用户显式触发才解除，否则会退化成无限重试）；
 * 这个就是那个"显式触发"，把任务复位成全新的一次：
 * `attempts = 0`（重新给满 3 次机会）、清空错误与时间戳、状态回 QUEUED。
 *
 * 记录侧同步回到 `PENDING`，让界面立刻显示"处理中"，而不是继续挂着红色失败态。
 *
 * ## M14：重试同样受云端闸门约束
 * "用户点了重试"不等于"用户同意上传"。云端开关关着、或记录被判为敏感时，
 * 这里只排本地任务（OCR / 网页提取），并把记录标记为 `LOCAL_ONLY`；
 * 一个可跑的都没有时返回 false 且**不改状态** —— 界面上的失败态原样保留，
 * 比悄悄把状态刷成"处理中"再也没动静要诚实。
 */
class RetryProcessingUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val jobRepository: ProcessingJobRepository,
    private val scheduler: ProcessingScheduler,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
    private val languagePreference: LanguagePreference = DefaultLanguagePreference,
) {

    /**
     * @param only 只重排这些任务（2026-10-26 原文编辑用）；`null` = 该类型全部可跑任务。
     *   编辑原文只该重跑摘要与向量，不能顺手把 OCR / 转写再跑一遍 —— 那既浪费算力，
     *   也会让用户看到"改了几个字，图片又被重新识别了一次"。
     * @return true = 至少排入了一个任务；false = 记录不存在、被闸门全部挡下，或该类型没有可跑的任务
     */
    suspend operator fun invoke(
        recordId: String,
        now: Long = System.currentTimeMillis(),
        only: Set<JobType>? = null,
    ): Boolean {
        if (recordId.isBlank()) return false
        val record = recordRepository.getById(recordId) ?: return false

        // 合集按成员的实时并集判闸（M20）；其他类型 gateRecord 原样返回
        val cloudBlocked = CloudUploadGate.blockReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
            record = recordRepository.gateRecord(record),
        ) != null
        // 与详情页"是否显示重试按钮"共用同一份判定（2026-10-23 整改 F-4）
        val allJobs = ProcessingJobTargets.of(record.type)
        val jobTypes = ProcessingJobTargets.runnable(record.type, cloudBlocked)
            .filter { only == null || it in only }
        if (jobTypes.isEmpty()) return false

        // 手动重试 = 用户此刻要的语言（P11-07 / R-I18N-06）：不沿用旧快照。
        val language = languagePreference.uiLanguage().code

        jobTypes.forEach { jobType ->
            val existing = jobRepository.getByRecordAndType(recordId, jobType)
            if (existing == null) {
                jobRepository.insert(newJob(recordId, jobType, language))
            } else {
                jobRepository.updateStatus(
                    existing.copy(
                        status = JobStatus.QUEUED,
                        attempts = 0,
                        // 等待轮次要一起清零（2026-10-23 复核 F-2）：不清的话，
                        // "等待上游超时"失败后点重试，第一轮就 rounds+1 超限、立刻再失败，
                        // 用户看到的是"重试没用"。
                        waitRounds = 0,
                        nextRetryAt = null,
                        errorCategory = null,
                        startedAt = null,
                        finishedAt = null,
                        outputLanguage = language,
                    ),
                )
            }
            // 调度放在状态复位之后：万一 enqueue 抛异常，任务表里也已经是"待执行"，
            // 下次启动补投还能救回来（反过来则会出现"跑完了但库里的失败标记没清"）。
            //
            // 用 reenqueue（REPLACE）而不是 enqueue（KEEP）：用户点重试时，同名任务可能
            // 正卡在 WorkManager 的指数退避里，KEEP 会把这次请求丢掉，界面就一直"处理中"。
            scheduler.reenqueue(recordId, jobType)
        }

        // 有云端任务被闸门挡下 ⇒ 这条记录最多只能得到本地结果，状态要如实反映。
        // 判据用类型的**全量**任务（[allJobs]）而不是本次排入的任务：图片记录在云端关闭时
        // 只排得进 OCR，但它还有摘要/向量没做，写成 PENDING 会让界面一直等一个不会来的结果。
        val nextStatus = if (cloudBlocked && allJobs.any { it in ProcessingJobTargets.CLOUD_JOBS }) {
            ProcessingStatus.LOCAL_ONLY
        } else {
            ProcessingStatus.PENDING
        }
        recordRepository.updateProcessingStatus(recordId, nextStatus, now)
        return true
    }

    private fun newJob(recordId: String, jobType: JobType, language: String) = ProcessingJob(
        id = UUID.randomUUID().toString(),
        recordId = recordId,
        jobType = jobType,
        status = JobStatus.QUEUED,
        attempts = 0,
        maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
        outputLanguage = language,
    )
}
