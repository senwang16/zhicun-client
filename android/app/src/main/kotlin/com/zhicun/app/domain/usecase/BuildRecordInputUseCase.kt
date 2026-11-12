package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.file.MimeTypeResolver
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.model.RecordInput
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * 把手动新建页的「输入框文字 + 用户选的 URI」组装成 [RecordInput]（M5）。
 *
 * 存在的唯一理由是查 MIME：分类器靠类型区分图片与文件，而选择器只给 URI 不给类型。
 * 这一步必须发生在**保存之前**，否则 `Record.type` 会先被写错，事后再改就要多一次写库
 * 并让 UI 闪一下类型。
 *
 * 放在 domain 而不是 ViewModel 里直接做：`ContentResolver` 是 Android 类型，
 * ViewModel 直接碰它会破坏分层（AGENTS.md §2.3），也无法在纯 JVM 下测。
 */
class BuildRecordInputUseCase @Inject constructor(
    private val mimeTypeResolver: MimeTypeResolver,
) {

    /**
     * @param text 输入框原文（空白会被归一为 `null`）
     * @param picked 用户选中的附件，顺序即界面顺序；只需带 `uri`（和可能查到的 `displayName`），
     *   `mimeType` 由本用例补齐
     */
    suspend operator fun invoke(text: String, picked: List<InputUri>): RecordInput {
        val uris = picked.map { source ->
            // 查不到类型时保留调用方已知的类型，而不是一律置空
            source.copy(mimeType = resolveQuietly(source.uri) ?: source.mimeType)
        }
        return RecordInput(
            text = text.trim().takeIf { it.isNotEmpty() },
            sourceApp = null,
            supportedUris = uris,
            unsupportedMimeTypes = emptyList(),
        )
    }

    /**
     * 查类型失败**不能**让整次保存失败。
     *
     * 附件读不出来时，用户写的文字照样值得存下来（PRD §11：失败也要保留内容）；
     * 类型未知的后果只是记录被归为 `FILE`、Asset 落 `octet-stream`，都是可接受的降级。
     *
     * 异常原因由 data 层实现（[com.zhicun.app.data.file.ContentResolverMimeTypeResolver]）记录，
     * 本类在 domain 层，不引 `android.util.Log`（AGENTS.md §2.3）。
     *
     * 但**取消不是失败**：协程在查 MIME 期间被取消（用户离开页面）时，
     * `CancellationException` 必须原样抛出，否则调用方会把取消当成"类型未知"继续保存。
     */
    private suspend fun resolveQuietly(uri: String): String? = try {
        mimeTypeResolver.resolve(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
