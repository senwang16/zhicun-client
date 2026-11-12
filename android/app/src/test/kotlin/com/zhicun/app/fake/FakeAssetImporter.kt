package com.zhicun.app.fake

import com.zhicun.app.domain.file.AssetImportException
import com.zhicun.app.domain.file.ExternalAssetImporter
import com.zhicun.app.domain.file.ImportedAsset
import com.zhicun.app.domain.model.InputUri

/**
 * 手写的附件导入器假实现。
 *
 * - `failNext = true` 时下一次导入抛 [AssetImportException]（模拟 URI 授权失效 / 磁盘满）；
 * - `resolvedMime` 模拟"导入器问到了真实类型"：解析阶段拿不到类型时，
 *   导入器仍可能在复制时查出来（`CopyAssetToPrivateDirUseCase` 以它为准）；
 * - `beforeImport` 钩子让测试能在"复制发生的那一刻"断言记录是否已落库，
 *   这是验证"先落记录后复制附件"顺序的唯一办法。
 */
class FakeAssetImporter : ExternalAssetImporter {

    var failNext: Boolean = false
    var resolvedMime: String? = null
    var resolvedDisplayName: String? = null
    var beforeImport: ((recordId: String) -> Unit)? = null
    val imported: MutableList<Pair<String, InputUri>> = mutableListOf()

    override suspend fun import(recordId: String, source: InputUri): ImportedAsset {
        beforeImport?.invoke(recordId)
        imported += recordId to source
        if (failNext) throw AssetImportException("模拟复制失败 uri=${source.uri}")
        return ImportedAsset(
            localPath = "/data/user/0/com.zhicun.app/files/assets/$recordId/file.bin",
            mimeType = resolvedMime ?: source.mimeType ?: "application/octet-stream",
            sizeBytes = 2048L,
            sha256 = "deadbeef",
            displayName = resolvedDisplayName,
        )
    }
}
