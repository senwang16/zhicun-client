package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.file.AssetImportException
import com.zhicun.app.domain.file.ExternalAssetImporter
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.repository.AssetRepository
import java.util.UUID
import javax.inject.Inject

/**
 * 把一个外部 URI 复制进私有目录，并落一条 [Asset] 记录。
 *
 * 关键语义（PRD §5.1 第 3 条 / §11"分享 URI 权限失效"）：
 * **复制失败不抛异常、不删记录**，而是落一条 `saveStatus = FAILED` 的 Asset。
 * 因为记录本身（文字、URL、来源）已经存住了，用户最坏的情况是"少了一张图"，
 * 而不是"整条记录没了"。
 *
 * 日志约定：本类在 domain 层，**不允许**引用 `android.util.Log`（AGENTS.md §2.3）。
 * 失败原因由 data 层实现（[ExternalAssetImporter]）在抛异常前记录，这里只做业务翻译。
 */
class CopyAssetToPrivateDirUseCase @Inject constructor(
    private val importer: ExternalAssetImporter,
    private val assetRepository: AssetRepository,
) {

    /**
     * @param orderIndex 附件在本条记录里的选择顺序（0 起），决定详情页与识别结果的排列
     */
    suspend operator fun invoke(recordId: String, source: InputUri, orderIndex: Int): Asset {
        val asset = try {
            val imported = importer.import(recordId, source)
            Asset(
                id = UUID.randomUUID().toString(),
                recordId = recordId,
                // 以导入器实测到的类型为准：解析阶段拿不到类型时它是唯一的补救机会，
                // 否则会把一张 png 记成 application/octet-stream（M4 审核发现的问题）
                mimeType = imported.mimeType.ifBlank { FALLBACK_MIME },
                localPath = imported.localPath,
                fileSize = imported.sizeBytes,
                fileHash = imported.sha256,
                originalUri = source.uri,
                saveStatus = AssetSaveStatus.SAVED,
                // 导入器读到的原名是权威值；解析阶段的名字作为兜底（例如导入器没拿到）
                fileName = imported.displayName ?: source.displayName,
                orderIndex = orderIndex,
            )
        } catch (_: AssetImportException) {
            // 失败原因已由 data 层在抛异常前记录；这里只做业务翻译，不重复打日志
            failedAsset(recordId, source, orderIndex)
        }

        assetRepository.save(asset)
        return asset
    }

    /**
     * 失败态 Asset。
     *
     * `localPath` 留空串而不是编一个"预期路径"：文件并不存在，
     * 写一个看起来合法的路径会让后续"文件是否存在"的判断被绕过。
     * `originalUri` 保留，方便 M6 之后重试时重新申请读取。
     */
    private fun failedAsset(recordId: String, source: InputUri, orderIndex: Int): Asset = Asset(
        id = UUID.randomUUID().toString(),
        recordId = recordId,
        mimeType = source.mimeType?.takeIf { !it.contains('*') } ?: FALLBACK_MIME,
        localPath = "",
        fileSize = 0L,
        fileHash = null,
        originalUri = source.uri,
        saveStatus = AssetSaveStatus.FAILED,
        fileName = source.displayName,
        orderIndex = orderIndex,
    )

    private companion object {
        const val FALLBACK_MIME = "application/octet-stream"
    }
}
