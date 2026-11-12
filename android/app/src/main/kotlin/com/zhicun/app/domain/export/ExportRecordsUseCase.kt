package com.zhicun.app.domain.export

import com.zhicun.app.domain.export.ExportPhase.COLLECTING
import com.zhicun.app.domain.export.ExportPhase.FINALIZING
import com.zhicun.app.domain.maintenance.LocalFileStore
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * 导出全部未删除记录（M15）。
 *
 * ## 包结构
 * ```
 * zhicun-export-<时间戳>.zip
 *   manifest.json                       记录原文、来源、时间、标签、附件索引
 *   assets/<recordId>/<assetId>.<ext>   原始附件
 * ```
 * 清单是**唯一入口**：读取方先读它、再按 `entryPath` 找文件。这样将来调整目录结构
 * 不会破坏已有文件的解析，也不必让读取方去猜命名规则。
 *
 * ## 为什么进度分两段
 * 附件写入才是耗时的大头（一段 20MB 的录音），而记录装配几乎瞬间完成。
 * 只报一个笼统的百分比，用户会看到"卡在 5% 一动不动"。
 *
 * ## 失败策略
 * 单个附件文件不在磁盘上**不让整个导出失败**：清单里标 `filePresent=false`、
 * 计数带回界面。理由是"导出 20 条、其中 2 个附件已丢失"远比
 * "导出失败，一条都拿不到"有用 —— 原文才是最难重建的东西。
 */
class ExportRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val assetRepository: AssetRepository,
    private val tagRepository: TagRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val collectionRepository: CollectionRepository,
    private val fileStore: LocalFileStore,
    private val writer: ExportArchiveWriter,
) {

    /**
     * @param appVersion 写进清单，便于日后判断"这份导出是哪个版本生成的"
     * @param now 导出时间，显式传入便于测试固定
     */
    suspend operator fun invoke(
        options: ExportOptions = ExportOptions(),
        appVersion: String,
        now: Long = System.currentTimeMillis(),
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportedArchive {
        // observeAll 已过滤软删除；这里再滤一次是防御 —— 若某处实现改了过滤条件，
        // "导出已删除的记录"会是用户完全无法理解的行为
        val records = recordRepository.observeAll().first().filterNot { it.isDeleted }
        // 成员必须能在包里解析到：已删除或已被物理清理的成员不写引用
        val exportedIds = records.mapTo(HashSet()) { it.id }

        onProgress(ExportProgress(COLLECTING, done = 0, total = records.size))

        val entries = mutableListOf<ArchiveAssetEntry>()
        val exportRecords = ArrayList<ExportRecord>(records.size)
        var assetCount = 0
        var missingAssetCount = 0

        records.forEachIndexed { index, record ->
            val assets = assetRepository.getByRecordId(record.id)
            // 标签与派生内容受**同一个**开关控制：用户取消勾选"同时导出 AI 摘要、标签与识别出的文字"，
            // 意思是"别把机器算出来的东西带出去"。只过滤派生内容而漏掉 AI 标签，
            // 会让模型生成的分类随包流出去 —— 那是用户明确选择排除的信息。
            // 用户自己打的标签永远导出：那是他的输入，不是 AI 的产物。
            val tags = tagRepository.getByRecordId(record.id)
                .filter { options.includeDerivedContent || it.source == TagSource.USER }
            val derived = if (options.includeDerivedContent) {
                derivedContentRepository.getByRecordId(record.id)
            } else {
                emptyList()
            }
            // 合集成员只存引用（schema v3）：成员内容由各自记录承载，不复制
            val collectionMembers = if (record.type == RecordType.COLLECTION) {
                collectionRepository.getMembers(record.id)
                    .filter { it.recordId in exportedIds }
                    .map {
                        ExportCollectionMember(
                            recordId = it.recordId,
                            position = it.position,
                            addedAt = it.addedAt,
                        )
                    }
            } else {
                emptyList()
            }

            // 先装配清单条目，再按它的 `filePresent` 决定往包里写哪些文件。
            // 反过来（各自判一次磁盘）会出现"清单说缺失、包里却有"的矛盾包：
            // 判定规则只有 `isAssetAvailable` 一处，两边都从它派生。
            val exportRecord = buildExportRecord(
                record = record,
                tags = tags,
                assets = assets,
                derived = derived,
                filePresent = { asset: Asset -> fileStore.exists(asset.localPath) },
                collectionMembers = collectionMembers,
            )
            exportRecords += exportRecord

            // `ExportRecord.assets` 与 `assets` 一一对应（map 保序），
            // 借它拿到 entryPath，避免在这里重算一遍命名规则
            exportRecord.assets.zip(assets).forEach { (exported, source) ->
                assetCount++
                if (exported.filePresent) {
                    entries += ArchiveAssetEntry(
                        entryPath = exported.entryPath,
                        sourcePath = source.localPath,
                    )
                } else {
                    missingAssetCount++
                }
            }

            onProgress(ExportProgress(COLLECTING, done = index + 1, total = records.size))
        }

        val manifest = ExportManifest(
            exportedAt = now,
            appVersion = appVersion,
            includeDerivedContent = options.includeDerivedContent,
            recordCount = exportRecords.size,
            assetCount = assetCount,
            missingAssetCount = missingAssetCount,
            records = exportRecords,
        )

        onProgress(ExportProgress(FINALIZING, done = 0, total = 1))
        val written = writer.write(
            manifestJson = EXPORT_JSON.encodeToString(ExportManifest.serializer(), manifest),
            assets = entries,
            onProgress = onProgress,
        )
        onProgress(ExportProgress(FINALIZING, done = 1, total = 1))

        return ExportedArchive(
            file = written.file,
            sizeBytes = written.sizeBytes,
            recordCount = exportRecords.size,
            assetCount = assetCount,
            missingAssetCount = missingAssetCount,
        )
    }
}
