package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import javax.inject.Inject

/**
 * 批量重建"向量与当前模型/供应商不兼容"的记录（开源改造 P1）。
 *
 * ## 为什么不另写一条向量管线
 * 《技术落地方案》§5.4 的原方案是 `ReEmbedAllWorker` 自己读原文、自己请求向量。
 * 那等于把 `EmbedWorker` 的整套规则复制一份：正文拼接（`ProcessingInputText`）、
 * 上游任务等待、云端开关与敏感内容闸门、合集成员展开、幂等主键……
 * 两份实现迟早漂移，而漂移的后果是"手动重建绕过了敏感确认"这种隐私级 bug。
 *
 * 这里只做一件事：**把需要重建的记录重新排进既有的 EMBED 任务队列**，
 * 由 `EmbedWorker` 按它本来的规则执行。代价是需要等后台调度，收益是只有一条链路。
 *
 * ## 三条边界
 * 1. 只重排 `EMBED`，不碰 OCR / 转写 / 网页提取 —— 用户点的是"重建向量"，不是"全部重做"；
 * 2. 云端被挡（开关关着 / 记录敏感未确认）时 [RetryProcessingUseCase] 返回 false，这里跳过并计数，
 *    **不把它们悄悄放行**；
 * 3. 已经在排队或正在跑的 EMBED 任务不重复提交：重复会把正在跑的任务取消重来，
 *    在几百条记录时表现为"永远重建不完"。
 *
 * ## 一次取多少
 * [MAX_PER_RUN] 条：个人知识库量级下一次几百条是合理的；不设上限的话，
 * 用户第一次换模型可能一次性排起上万条任务，WorkManager 队列与电量都不答应。
 * 剩余的仍然显示在设置页，再点一次即可继续。
 */
class ReEmbedAllUseCase @Inject constructor(
    private val embeddingRepository: EmbeddingRepository,
    private val jobRepository: ProcessingJobRepository,
    private val retryProcessing: RetryProcessingUseCase,
) {

    /**
     * @return 实际重新排队的记录数（被闸门挡下或已在排队的都不计入）
     */
    suspend operator fun invoke(model: String, providerKey: String): Int {
        if (model.isBlank()) return 0

        val candidates = embeddingRepository.findIncompatibleRecordIds(
            model = model,
            providerKey = providerKey,
            limit = MAX_PER_RUN,
        )
        if (candidates.isEmpty()) return 0

        var submitted = 0
        candidates.forEach { recordId ->
            val existing = jobRepository.getByRecordAndType(recordId, JobType.EMBED)
            val alreadyActive = existing != null &&
                (existing.status == JobStatus.QUEUED || existing.status == JobStatus.RUNNING)
            if (alreadyActive) return@forEach

            if (retryProcessing(recordId, only = setOf(JobType.EMBED))) submitted++
        }
        return submitted
    }

    companion object {
        /** 单次最多重排多少条；其余留在设置页的待重建计数里，由用户再点一次。 */
        const val MAX_PER_RUN: Int = 500
    }
}