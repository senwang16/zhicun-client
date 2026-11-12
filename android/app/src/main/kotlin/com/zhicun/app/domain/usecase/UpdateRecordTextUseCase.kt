package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.diagnostic.DiagnosticLog
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import javax.inject.Inject

/** 原文编辑的结果。 */
sealed interface UpdateRecordTextResult {

    /** 已写入，并且已经排入摘要 + 向量重算。 */
    data object Updated : UpdateRecordTextResult

    /** 内容去空白后与原来一致，没有写库。 */
    data object Unchanged : UpdateRecordTextResult

    /** 记录不存在或已删除。 */
    data object NotFound : UpdateRecordTextResult

    /** 期间有别的写入推进了内容版本，这次编辑没有落库。 */
    data object Conflict : UpdateRecordTextResult
}

/**
 * 用户手动修改一条记录的原文（2026-10-26 详情页编辑）。
 *
 * ## 为什么要让 AI 结果全部重来
 * 摘要、标签、向量都是对"当时那段原文"的回答。原文改了却保留旧摘要，
 * 详情页会把用户自己改过的内容总结成另一件事 —— 这不是过期数据，是**错误信息**。
 * 因此保存成功后：
 * 1. 删掉被原文推翻的派生内容（摘要 / 标签 / 向量，以及除网页外的 AI 标题）；
 * 2. 摘掉 AI 标签（用户标签保留，那是用户自己的整理，与内容重算无关）；
 * 3. 只重排 [JobType.SUMMARIZE] + [JobType.EMBED]，**不碰** OCR / 转写 / 网页提取 ——
 *    那三样来自附件或源站，与用户改的字无关。
 *
 * ## 敏感判据只升不降
 * 编辑后重新扫描（正文 + 来源标题，与保存链路同一范围）。已经疑似/确认的记录保持原值，
 * 不因为"用户把敏感内容删了"就自动洗白 —— 那会绕过 PRD §6.4 的逐条确认机制；
 * 反过来，新内容命中就升级为 `SUSPECTED`，并随版本号推进作废旧确认。
 *
 * ## 云端被挡时不做假动作
 * 重排返回 false（云端被挡、没有可跑任务）时把状态写成 `LOCAL_ONLY`：
 * 旧摘要已经删了，状态若仍是 `DONE`，界面会停在"正在生成摘要…"且永远不结束。
 */
class UpdateRecordTextUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    private val sensitiveDetector: SensitiveContentDetector,
    private val retryProcessing: RetryProcessingUseCase,
    private val diagnosticLog: DiagnosticLog,
) {

    suspend operator fun invoke(
        recordId: String,
        text: String,
        now: Long = System.currentTimeMillis(),
    ): UpdateRecordTextResult {
        if (recordId.isBlank()) return UpdateRecordTextResult.NotFound
        val record = recordRepository.getById(recordId) ?: return UpdateRecordTextResult.NotFound
        if (record.isDeleted) return UpdateRecordTextResult.NotFound

        val nextText = text.trim().takeIf { it.isNotEmpty() }
        if (nextText == record.rawText?.trim()?.takeIf { it.isNotEmpty() }) {
            return UpdateRecordTextResult.Unchanged
        }

        // 扫描范围与 SaveRecordUseCase 对齐：正文 + 来源标题都会进入摘要输入
        val scan = sensitiveDetector.scan(
            listOfNotNull(nextText, record.sourceTitle).joinToString("\n"),
        )
        val scannedStatus = sensitiveDetector.toSensitivityStatus(scan)
        val status = if (record.type == RecordType.COLLECTION) {
            // 合集的 status 是"说明 + 成员"的并集。只按说明重算会把成员的敏感状态洗掉，
            // 而合集摘要要发的正是成员正文 —— 改成并集，且保持只升不降。
            CollectionSensitivity.union(listOf(record.sensitivityStatus, scannedStatus))
        } else {
            mergeSensitivity(current = record.sensitivityStatus, scanned = scannedStatus)
        }

        val updated = recordRepository.updateRawText(
            id = record.id,
            rawText = nextText,
            sensitivityStatus = status,
            expectedRevision = record.recordRevision,
            updatedAt = now,
        )
        if (!updated) return UpdateRecordTextResult.Conflict

        // 这条记录可能是某个合集的成员（M20）：成员内容变了，合集的敏感并集与云端确认
        // 都必须立刻跟上，否则"用户确认的是旧成员内容"会被用来放行新内容。
        // 放在派生内容清理之前：即使后面的清理/重排出错，合并确认也已经失效（方向是安全）。
        collectionRepository.refreshForMemberChange(record.id, now)

        derivedContentRepository.deleteByRecordAndTypes(record.id, staleDerivedTypes(record.type))
        tagRepository.removeAiTags(record.id)

        val requeued = retryProcessing(
            recordId = record.id,
            now = now,
            only = RERUN_JOBS,
        )
        if (!requeued) {
            // 旧摘要在上面已经删掉，状态必须如实说明"这些内容不会自动生成"
            recordRepository.updateProcessingStatus(record.id, ProcessingStatus.LOCAL_ONLY, now)
            diagnosticLog.w(TAG, "原文已保存，但摘要/向量未能排队 recordId=${record.id}")
        }
        return UpdateRecordTextResult.Updated
    }

    /**
     * 原文变化后必须失效的派生内容。
     *
     * 网页的 `TITLE` 是站点自己的标题（M8 网页提取的产物），与用户改的正文无关，
     * 保留它比删掉更准确；其余类型的 `TITLE` 是摘要模型生成的短标题，必须重算。
     */
    private fun staleDerivedTypes(type: RecordType): List<DerivedType> = buildList {
        add(DerivedType.SUMMARY)
        add(DerivedType.TAGS)
        add(DerivedType.EMBEDDING)
        if (type != RecordType.WEB_LINK) add(DerivedType.TITLE)
    }

    /** 已疑似/确认的保持原值；新内容命中则升级；其余落到 CLEAR（刚扫过，比 UNKNOWN 准确）。 */
    private fun mergeSensitivity(
        current: SensitivityStatus,
        scanned: SensitivityStatus,
    ): SensitivityStatus = when {
        current.isSensitive -> current
        scanned == SensitivityStatus.SUSPECTED -> SensitivityStatus.SUSPECTED
        else -> SensitivityStatus.CLEAR
    }

    private companion object {
        const val TAG = "UpdateRecordText"

        /** 原文编辑只需要重算"对内容的理解"，不重跑取正文的任务。 */
        val RERUN_JOBS: Set<JobType> = setOf(JobType.SUMMARIZE, JobType.EMBED)
    }
}
