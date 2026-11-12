package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.CollectionMemberInput
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.domain.worker.ProcessingScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * 云端开关由关变开时，把停在 `LOCAL_ONLY` 的记录重新送上云端（2026-10-23 审查 R-3）。
 *
 * ## 为什么必须有这一步
 * 整改前开关只写偏好：关掉时保存的记录不会排云端任务、状态落成 `LOCAL_ONLY`；
 * 用户再把开关拨回来，**没有任何东西会重排那些任务** —— `RecoverPendingJobsUseCase`
 * 只复活 `RUNNING` / 补投 `QUEUED`，`CANCELLED` 与"压根没建过任务"的行它都不碰。
 * 于是这些记录永远没有摘要，而重试按钮当时只挂在 `FAILED` 上，界面上一条出路都没有。
 *
 * ## 为什么不用 `RetryProcessingUseCase` 循环一遍
 * 那个会把该类型的**全部**任务复位（含已经成功的 OCR），并且会改写记录状态。
 * 这里只补"云端那一半"，本地成果一律不动：用户要的是摘要，不是重新识别一遍图片。
 *
 * ## 敏感记录故意跳过
 * 疑似敏感且未确认的记录即使通路打开了也过不了闸门（[CloudUploadGate.reasonOf]），
 * 硬排只会立刻被取消、把状态又刷回 `LOCAL_ONLY`。这类记录的正确入口是详情页的
 * "确认上传"横幅 —— 逐条确认是 PRD §6.4 的要求，不能拿一个全局开关代办。
 */
class ResumeCloudJobsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val jobRepository: ProcessingJobRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val assetRepository: AssetRepository,
    private val collectionRepository: CollectionRepository,
    private val scheduler: ProcessingScheduler,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
) {

    /**
     * @return 本次真正补投的记录数（仅用于日志；调用方无需处理 0）
     */
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): Int {
        // 通路本身还不通（网关未配置）就不要扫库：扫出来也一条都投不出去
        if (cloudPathBlocked() != null) return 0

        var resumed = 0
        records@ for (id in recordRepository.idsByProcessingStatus(ProcessingStatus.LOCAL_ONLY)) {
            val record = recordRepository.getById(id) ?: continue@records
            if (record.isDeleted) continue@records
            // 敏感未确认 → 交给详情页逐条确认，不在这里代办。
            //
            // 判据必须带上"这一次会用到哪些内容块"（2026-10-23 复核 F-9）：
            // 只判版本号的话，确认过原文、之后才落地转写的记录会被排上，
            // 然后立刻被 Worker 按范围判据取消 —— 白排一轮，状态还要抖一次。
            // 范围不全的正确入口是详情页横幅（用户看过新内容再点确认）。
            // 合集按成员的实时并集判闸（M20）；成员内容块也算进确认范围，
            // 否则补投会把一个"确认过、但成员后来变了"的合集重新排上去，再被 Worker 取消。
            val gated = recordRepository.gateRecord(record)
            val blocked = CloudUploadGate.reasonOf(
                cloudAiEnabled = aiSettings.cloudAiEnabled,
                gatewayConfigured = gatewaySettings.isConfigured,
                record = gated,
                requiredParts = presentParts(record, gated),
            )
            if (blocked != null) continue@records

            val cloudJobs = ProcessingJobTargets.of(record.type)
                .filter { it in ProcessingJobTargets.CLOUD_JOBS }
            if (cloudJobs.isEmpty()) continue@records

            var queued = 0
            cloudJobs.forEach { jobType ->
                val existing = jobRepository.getByRecordAndType(id, jobType)
                if (existing?.status == JobStatus.DONE) return@forEach
                if (existing == null) {
                    jobRepository.insert(newJob(id, jobType))
                } else {
                    jobRepository.updateStatus(
                        existing.copy(
                            status = JobStatus.QUEUED,
                            attempts = 0,
                            // 用户手动开开关等于重新给一次完整机会（2026-10-23 复核 F-2）
                            waitRounds = 0,
                            nextRetryAt = null,
                            errorCategory = null,
                            startedAt = null,
                            finishedAt = null,
                        ),
                    )
                }
                scheduler.reenqueue(id, jobType)
                queued++
            }

            if (queued > 0) {
                // 用聚合而不是直接写 PENDING：本地任务可能还在跑，状态应由任务集合决定
                recordRepository.recomputeProcessingStatus(id, cloudActive = true, now)
                resumed++
            }
        }
        return resumed
    }

    /** 与上传闸门同一份判据，只是这里不关心具体记录（见 [CloudUploadGate.cloudPathBlockedReason]）。 */
    private fun cloudPathBlocked(): String? = CloudUploadGate.cloudPathBlockedReason(
        cloudAiEnabled = aiSettings.cloudAiEnabled,
        gatewayConfigured = gatewaySettings.isConfigured,
    )?.message

    /**
     * 这条记录"现在有哪些内容块"，供确认范围判据使用。
     *
     * 只对敏感记录算：干净记录根本不受范围判据约束，为它们多打两次库纯属浪费
     * （补投会扫过全部 `LOCAL_ONLY` 记录，批量时这个差别不小）。
     */
    private suspend fun presentParts(record: Record, gated: Record): Set<ContentPart> {
        if (!gated.sensitivityStatus.isSensitive) return emptySet()
        val derived = derivedContentRepository.getByRecordId(record.id)
        if (record.type != RecordType.COLLECTION) {
            return ProcessingInputText.presentParts(
                record = record,
                derived = derived,
                attachmentUploadable = assetRepository.getByRecordId(record.id).any { it.isUploadable },
            )
        }

        val members = collectionRepository.getMembersWithRecords(record.id).map { member ->
            CollectionMemberInput(
                record = member.record,
                derived = derivedContentRepository.getByRecordId(member.record.id),
            )
        }
        return ProcessingInputText.collectionPresentParts(
            collection = record,
            descriptionDerived = derived,
            members = members,
        )
    }

    private fun newJob(recordId: String, jobType: JobType) = ProcessingJob(
        id = UUID.randomUUID().toString(),
        recordId = recordId,
        jobType = jobType,
        status = JobStatus.QUEUED,
        attempts = 0,
        maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
    )
}
