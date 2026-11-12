package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.diagnostic.DiagnosticLog
import com.zhicun.app.domain.maintenance.LocalFileStore
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import javax.inject.Inject

/** 删除单个附件的结果；界面据此决定提示语与是否引导"删除整条记录"。 */
sealed interface DeleteAssetResult {

    /** 文件、附件行与该附件的派生结果都已清掉，摘要/向量已重排或如实标记。 */
    data object Deleted : DeleteAssetResult

    /** 记录已删除，或附件不属于这条记录（并发删除后重入）。 */
    data object NotFound : DeleteAssetResult

    /**
     * 这是记录的最后一项内容（没有其它附件、也没有原文），不执行删除。
     * 界面应引导用户改用"删除整条记录"——删完会留下一条什么都没有的空记录（PRD §11）。
     */
    data object LastContent : DeleteAssetResult

    /** 磁盘文件删不掉（被占用 / 只读挂载），数据库一行没动，可稍后重试。 */
    data object FileDeleteFailed : DeleteAssetResult
}

/**
 * 删除记录里的单个附件（2026-10-29 多附件）。
 *
 * ## 顺序：先删文件，再删数据库
 * 与 [CleanupDeletedRecordsUseCase] 同一条纪律：反过来一旦文件删不掉，
 * 数据库里就再没有线索指向那个文件。因此文件删除失败时**直接中止**，
 * 返回 [DeleteAssetResult.FileDeleteFailed]，不留"行没了文件还在"的半删状态。
 *
 * 文件删掉之后：
 * 1. 删附件行；
 * 2. 删该附件的派生结果（`asset_id = assetId` 的 OCR / 转写）；
 * 3. 失效记录级 AI 结果（摘要 / 标签 / 向量，以及非网页的 AI 标题）并摘掉 AI 标签；
 * 4. 按剩余内容重算记录类型（一张图删完可能只剩文字 → 变成文本记录）；
 * 5. 如果还剩可读内容，重排摘要 / 向量；排不进去就把状态如实写成"仅本地"。
 *
 * ## 敏感标记只升不降
 * 删掉的附件可能正是敏感内容的来源。这里**不**重扫、不降级 —— 降级会让一份
 * 旧的云端确认被重新用来放行未知内容（与原文编辑的"只升不降"同一理由）。
 *
 * ## 合集
 * 被删附件所在记录可能是某个合集的成员：`refreshForMemberChange` 必须调用，
 * 否则合集仍拿旧的成员内容/敏感并集做确认。
 *
 * ## 没有互斥锁
 * 删除时后台任务可能正在跑；它可能以 `Permanent` 结束（记录里已没有那个附件）。
 * 重新处理即可恢复一致，为此加锁反而会把"用户删个文件"卡在 OCR 后面。
 */
class DeleteAssetUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val assetRepository: AssetRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    private val retryProcessing: RetryProcessingUseCase,
    private val fileStore: LocalFileStore,
    private val diagnosticLog: DiagnosticLog,
) {

    suspend operator fun invoke(
        recordId: String,
        assetId: String,
        now: Long = System.currentTimeMillis(),
    ): DeleteAssetResult {
        if (recordId.isBlank() || assetId.isBlank()) return DeleteAssetResult.NotFound

        val record = recordRepository.getById(recordId) ?: return DeleteAssetResult.NotFound
        if (record.isDeleted) return DeleteAssetResult.NotFound

        val assets = assetRepository.getByRecordId(recordId)
        val target = assets.firstOrNull { it.id == assetId } ?: return DeleteAssetResult.NotFound
        val remaining = assets.filterNot { it.id == assetId }
        // 网页快照 / og 图是抓取链路写的系统资产，不算"用户的最后一项内容"
        val remainingUserAssets = remaining.filterNot { it.isSystemAsset() }

        if (remainingUserAssets.isEmpty() && record.rawText.isNullOrBlank()) {
            return DeleteAssetResult.LastContent
        }

        if (!fileStore.delete(target.localPath)) {
            // 文删不掉就一行都不动：保留附件行是后续重试的唯一依据
            diagnosticLog.w(TAG, "附件文件删除失败 recordId=$recordId assetId=$assetId")
            return DeleteAssetResult.FileDeleteFailed
        }

        assetRepository.deleteById(target.id)
        derivedContentRepository.deleteByAssetId(target.id)

        // 成员内容变了：合集的敏感并集与云端确认必须立刻失效（与原文编辑同一处理）
        collectionRepository.refreshForMemberChange(record.id, now)

        derivedContentRepository.deleteByRecordAndTypes(record.id, staleDerivedTypes(record.type))
        tagRepository.removeAiTags(record.id)
        recomputeType(record, remainingUserAssets, now)

        if (!record.rawText.isNullOrBlank() || remainingUserAssets.isNotEmpty()) {
            val requeued = retryProcessing(record.id, now, only = RERUN_JOBS)
            if (!requeued) {
                // 旧摘要已经删了；状态若仍是 DONE，界面会停在"正在生成摘要…"且永远不结束
                recordRepository.updateProcessingStatus(record.id, ProcessingStatus.LOCAL_ONLY, now)
                diagnosticLog.w(TAG, "附件已删除，但摘要/向量未能排队 recordId=${record.id}")
            }
        }

        return DeleteAssetResult.Deleted
    }

    /**
     * 按剩余内容重算类型。
     *
     * 用 [SharedContentClassifier] 而不是自己写 `when`：保存时就是它算的类型，
     * 删除后也必须用同一张优先级表，否则会出现"保存时判成图片、删一张图后判成别的"。
     * 合集不参与：它的内容来自成员表，不由自己的附件决定。
     */
    private suspend fun recomputeType(
        record: Record,
        remainingUserAssets: List<Asset>,
        now: Long,
    ) {
        if (record.type == RecordType.COLLECTION) return

        val input = RecordInput(
            text = record.rawText,
            supportedUris = remainingUserAssets.map { InputUri(uri = it.id, mimeType = it.mimeType) },
        )
        val nextType = SharedContentClassifier.classify(
            input = input,
            captureSource = if (record.captureSource == CaptureSource.MANUAL) {
                CaptureSource.MANUAL
            } else {
                // 分类器只区分"手动新建"与"其余来源"；这里保留手动语义即可
                CaptureSource.SYSTEM_SHARE
            },
        )
        if (nextType != record.type) {
            recordRepository.updateType(record.id, nextType, now)
        }
    }

    /** 网页快照 / og 图：id 约定见 `WebSnapshotStoreImpl`，与 v8 补跑查询同一判据。 */
    private fun Asset.isSystemAsset(): Boolean =
        id.endsWith(SNAPSHOT_SUFFIX) || id.endsWith(OG_IMAGE_SUFFIX)

    /**
     * 附件内容变化后必须失效的派生内容（与原文编辑同一判据）。
     *
     * 网页的 `TITLE` 是站点自己的标题，与附件无关，保留；其余 `TITLE` 是摘要模型生成的，
     * 必须重算。OCR / 转写属于附件本身，删除时已按 `assetId` 单独清理，这里不碰。
     */
    private fun staleDerivedTypes(type: RecordType): List<DerivedType> = buildList {
        add(DerivedType.SUMMARY)
        add(DerivedType.TAGS)
        add(DerivedType.EMBEDDING)
        if (type != RecordType.WEB_LINK) add(DerivedType.TITLE)
    }

    private companion object {
        const val TAG = "DeleteAsset"
        const val SNAPSHOT_SUFFIX = ":snapshot"
        const val OG_IMAGE_SUFFIX = ":og_image"

        /** 附件变化只需要重算"对内容的理解"，不重跑 OCR / 转写 / 网页提取。 */
        val RERUN_JOBS: Set<JobType> = setOf(JobType.SUMMARIZE, JobType.EMBED)
    }
}
