package com.zhicun.app.data.web

import android.content.Context
import com.zhicun.app.data.file.FileCopy
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.web.WebSnapshotStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [WebSnapshotStore] 的实现（C）。
 *
 * 文件落在 `filesDir/assets/<recordId>/`，与用户附件同一个目录约定——
 * 导出（M15）按 Asset 行打包、清理按 Asset 行删文件，快照跟着走零额外代码。
 *
 * ## 幂等（先查后写）
 * 任务重跑（补投/手动重试）不能堆积副本：附件 id 是确定性的
 * （`<recordId>:snapshot` / `<recordId>:og_image`），
 * 已有该行时只覆写文件、复用行（[AssetDao.insert][com.zhicun.app.data.local.dao.AssetDao]
 * 是 ABORT 冲突策略，直接 insert 会撞主键崩任务）。覆写文件是安全的：
 * 两次抓取之间内容变了，后一次是更新的快照。
 */
@Singleton
class WebSnapshotStoreImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetRepository: AssetRepository,
) : WebSnapshotStore {

    override suspend fun saveHtmlSnapshot(recordId: String, htmlBytes: ByteArray): Asset =
        withContext(Dispatchers.IO) {
            save(recordId = recordId, bytes = htmlBytes, mimeType = HTML_MIME, assetId = htmlAssetId(recordId))
        }

    override suspend fun saveOgImage(
        recordId: String,
        imageBytes: ByteArray,
        mimeType: String,
    ): Asset = withContext(Dispatchers.IO) {
        save(recordId = recordId, bytes = imageBytes, mimeType = mimeType, assetId = imageAssetId(recordId))
    }

    private suspend fun save(
        recordId: String,
        bytes: ByteArray,
        mimeType: String,
        assetId: String,
    ): Asset {
        val targetDir = File(context.filesDir, "$ASSET_DIR/$recordId")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("无法创建快照目录：${targetDir.absolutePath}")
        }

        // 固定文件名（按 id 命名）：重跑覆盖，不产生 snapshot_1.html 这类副本
        val targetFile = File(targetDir, fileNameOf(assetId, mimeType))
        val outcome = FileCopy.copy(ByteArrayInputStream(bytes), targetFile, MAX_SNAPSHOT_BYTES)

        val asset = Asset(
            id = assetId,
            recordId = recordId,
            mimeType = mimeType,
            localPath = targetFile.absolutePath,
            fileSize = outcome.sizeBytes,
            fileHash = outcome.sha256,
            originalUri = null,
            saveStatus = AssetSaveStatus.SAVED,
        )
        // AssetDao.insert 是 REPLACE：同 id 覆盖整行（mime/size/hash 一起翻新），重跑不会撞主键
        assetRepository.save(asset)
        return asset
    }

    /**
     * 固定文件名：路径永不变化，重跑永远覆盖同一个文件。
     * 扩展名不跟 mime 走——mime 变了（站点换图格式）若扩展名跟着变，旧行还指着旧路径，
     * 而行只能改状态不能改路径（仓储没有整行更新），会留下孤儿文件。
     * 真实的类型以附件行的 mime 为准（导出/展示都用它，不看本地文件名）。
     */
    private fun fileNameOf(assetId: String, mimeType: String): String =
        when (assetId.substringAfterLast(':')) {
            "snapshot" -> "snapshot.html"
            else -> "og_image.img"
        }

    companion object {
        private const val TAG = "WebSnapshotStore"

        /** 与附件导入同一个根目录（`filesDir/assets/`），导出/清理按 Asset 行统一处理。 */
        const val ASSET_DIR = "assets"

        /**
         * 快照上限 6MB：页面本身 ≤1MB（`OkHttpWebPageLoader` 上限），加内嵌正文图
         * （[SnapshotImageInliner] 原始字节 ≤3MB，base64 后约 4MB）再留余量。
         * 超限整个快照放弃（[FileCopy] 抛异常 → Worker 降级只留日志）。
         */
        const val MAX_SNAPSHOT_BYTES = 6L * 1024 * 1024

        const val HTML_MIME = "text/html"

        internal const val SNAPSHOT_ID_SUFFIX = "snapshot"
        internal const val OG_IMAGE_ID_SUFFIX = "og_image"

        fun htmlAssetId(recordId: String) = "$recordId:$SNAPSHOT_ID_SUFFIX"

        fun imageAssetId(recordId: String) = "$recordId:$OG_IMAGE_ID_SUFFIX"
    }
}
