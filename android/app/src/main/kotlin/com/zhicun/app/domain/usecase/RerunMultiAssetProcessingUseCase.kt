package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.settings.MultiAssetRerunSettings
import javax.inject.Inject

/**
 * DB v8 升级后，为"多图片 / 多音频"的老记录补跑一次逐附件处理（D1 决策）。
 *
 * ## 为什么要重跑
 * v8 之前一条记录只认第一个附件，其余图片/音频从未被识别。迁移本身不改写老数据
 * （无法知道旧结果属于哪个附件），只能由处理链路重新产出一份"每附件一条"的结果。
 *
 * ## 只重排 OCR / 转写，不动摘要
 * 老摘要仍是对老内容的有效总结，重算既没必要也消耗网关额度。逐附件的原始结果落库后，
 * 用户下次主动"重新处理"时摘要自然跟着更新。
 *
 * ## 一次性标记在**全部入队之后**才写
 * 进程中途被杀 → 下次冷启动再扫一遍；任务复用 `ExistingWorkPolicy.KEEP`、
 * 派生行用确定性 id REPLACE，重跑只是覆盖，不会产生重复结果。
 *
 * 多音频记录仍受云端闸门约束（`RetryProcessingUseCase` 内部判闸）：被拦的直接跳过，
 * 不提示用户 —— 静默补跑不该把"云端已关闭"变成一次打扰。
 */
class RerunMultiAssetProcessingUseCase @Inject constructor(
    private val assetRepository: AssetRepository,
    private val retryProcessing: RetryProcessingUseCase,
    private val settings: MultiAssetRerunSettings,
) {

    /**
     * @return 实际排入任务的记录数；已补跑过时返回 0
     */
    suspend operator fun invoke(): Int {
        if (settings.completed) return 0

        val recordIds = assetRepository.getRecordIdsWithMultipleMedia()
        var enqueued = 0
        recordIds.forEach { recordId ->
            // 只传这两种：RetryProcessingUseCase 还会按记录类型过滤，
            // 图片记录不会因此多排一个转写任务，反之亦然。
            if (retryProcessing(recordId, only = MEDIA_JOB_TYPES)) {
                enqueued++
            }
        }

        settings.markCompleted()
        return enqueued
    }

    private companion object {
        val MEDIA_JOB_TYPES = setOf(JobType.OCR, JobType.TRANSCRIPTION)
    }
}
