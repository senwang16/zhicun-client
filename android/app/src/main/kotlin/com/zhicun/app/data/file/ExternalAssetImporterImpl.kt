package com.zhicun.app.data.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.zhicun.app.domain.file.AssetImportException
import com.zhicun.app.domain.file.ExternalAssetImporter
import com.zhicun.app.domain.file.ImportedAsset
import com.zhicun.app.domain.model.InputUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ExternalAssetImporter] 的 Android 实现：把分享进来的 `content://` 内容落到私有目录。
 *
 * 目录约定：`filesDir/assets/<recordId>/<安全文件名>`。
 * 用 `filesDir` 而不是 `cacheDir`：缓存目录会被系统随时清空，
 * 而 PRD 要求"强制关闭 App 再重开，附件仍在"。
 *
 * 关于权限：通过系统分享拿到的 URI 自带临时读授权（`FLAG_GRANT_READ_URI_PERMISSION`），
 * 所以这里**不需要** `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`。
 * 但授权随接收 Activity 结束而失效，这也是"尽快复制"这个要求的技术原因（PRD §5.1 第 3 条）。
 */
@Singleton
class ExternalAssetImporterImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ExternalAssetImporter {

    override suspend fun import(recordId: String, source: InputUri): ImportedAsset =
        withContext(Dispatchers.IO) {
            val uri = source.uri.toUri()
            try {
                copyIntoPrivateDir(recordId, source, uri)
            } catch (e: AssetImportException) {
                Log.w(TAG, "附件导入失败 uri=${LogSanitizer.uri(source.uri)}", e)
                throw e
            } catch (e: Exception) {
                // SecurityException（授权已失效）、IOException（源被清理）、OOM 等统一收口，
                // 保证调用方只需要认识 AssetImportException 一种异常
                Log.w(TAG, "附件导入异常 uri=${LogSanitizer.uri(source.uri)}", e)
                throw AssetImportException("附件导入失败：${e.javaClass.simpleName}", e)
            }
        }

    private fun copyIntoPrivateDir(recordId: String, source: InputUri, uri: Uri): ImportedAsset {
        val resolver = context.contentResolver
        // 通配符不算类型：即使上游漏了过滤，也不能把 "image" 前缀写进 Asset 表
        val mimeType = source.mimeType?.takeIf { !it.contains('*') }
            ?: safeContentType(resolver, uri)
            ?: FALLBACK_MIME

        val targetDir = File(context.filesDir, "$ASSET_DIR/$recordId")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw AssetImportException("无法创建附件目录：${targetDir.absolutePath}")
        }

        val displayName = queryDisplayName(resolver, uri)
        val targetFile = uniqueTarget(targetDir, safeFileName(displayName, mimeType))

        val input = resolver.openInputStream(uri)
            ?: throw AssetImportException("无法打开输入流（URI 可能已失去授权）")
        input.use { stream ->
            val outcome = FileCopy.copy(stream, targetFile, MAX_ASSET_BYTES)
            return ImportedAsset(
                localPath = targetFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = outcome.sizeBytes,
                sha256 = outcome.sha256,
                displayName = displayName,
            )
        }
    }

    /**
     * 查真实 MIME 类型。
     *
     * 单独包一层 try/catch：个别 provider 在没有授权时会抛 `SecurityException`，
     * 而"问不到类型"只应导致落 `octet-stream` 兜底，不应该让整次附件导入失败 ——
     * 文件本身可能依然读得出来。
     */
    private fun safeContentType(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (e: Exception) {
        Log.d(TAG, "查询 MIME 类型失败，按未知类型处理 uri=${LogSanitizer.uri(uri)}", e)
        null
    }

    /** 读取原始文件名（拿不到返回 null，例如部分 App 只给 URI 不给名字）。 */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        // 某些 provider 不支持 query：不该因此让整次分享失败，落回默认文件名即可
        Log.d(TAG, "读取文件名失败，使用默认名 uri=${LogSanitizer.uri(uri)}", e)
        null
    }

    /** 同名文件不覆盖：同一记录里放两张 `IMG_001.jpg` 时保留两份。 */
    private fun uniqueTarget(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate

        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isEmpty()) "_$index" else "_$index.$extension"
            candidate = File(dir, "$base$suffix")
            index++
        }
        return candidate
    }

    companion object {
        private const val TAG = "AssetImporter"

        /** 附件根目录（位于 `filesDir` 下）。 */
        const val ASSET_DIR = "assets"

        /** 单文件体积上限：超过直接拒绝并提示，不做"悄悄截断"（PRD §11）。 */
        const val MAX_ASSET_BYTES = 50L * 1024 * 1024

        private const val FALLBACK_MIME = "application/octet-stream"
        private const val MAX_BASE_LENGTH = 40

        /**
         * MIME → 扩展名。
         *
         * 不用 `android.webkit.MimeTypeMap`：它依赖系统数据库，
         * 在纯 JVM 单测里不可用，且不同 ROM 的映射表不一致（同一 MIME 可能给 md 或 markdown）。
         * 这里只覆盖分享场景真正会遇到的类型，其余落 `bin` —— 扩展名不影响后续读取（MIME 已单独存库）。
         */
        private val EXTENSION_BY_MIME = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "application/pdf" to "pdf",
            "text/plain" to "txt",
            "text/html" to "html",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/x-wav" to "wav",
            "audio/wav" to "wav",
        )

        /**
         * 生成落盘用的安全文件名。
         *
         * 外部文件名不可信：可能含 `/`（路径穿越）、`..`、控制字符、超长 emoji 序列。
         * 策略是"白名单 + 强制补扩展名"：非法字符一律换成 `_`，
         * 扩展名优先取原文件名，其次由 MIME 推断，最后兜底 `bin`。
         */
        internal fun safeFileName(displayName: String?, mimeType: String): String {
            val rawName = displayName?.trim().orEmpty()
            val rawBase = rawName.substringBeforeLast('.', rawName)
            val rawExtension = rawName.substringAfterLast('.', "")

            val base = rawBase
                .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString("")
                .trim('_')
                .take(MAX_BASE_LENGTH)
                .ifEmpty { "asset" }

            val extension = rawExtension
                .takeIf { it.isNotEmpty() && it.length <= 8 && it.all { char -> char.isLetterOrDigit() } }
                ?: EXTENSION_BY_MIME[mimeType.lowercase()]
                ?: "bin"

            return "$base.${extension.lowercase()}"
        }
    }
}
