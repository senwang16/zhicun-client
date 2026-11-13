package com.zhicun.app.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.zhicun.app.domain.clipboard.ClipboardReadResult
import com.zhicun.app.domain.clipboard.ClipboardReader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ClipboardReader] 的 Android 实现（M13）。
 *
 * ## 为什么切到 IO 线程
 * `getPrimaryClip()` 要跨进程向系统剪贴板服务取数据；如果剪贴板里是一大段 HTML
 * （浏览器"复制整页"），序列化 + 传输会占用可观时间。它本身不是磁盘 IO，
 * 但"不该在主线程做"这一点是一样的，因此统一丢到 [Dispatchers.IO]。
 *
 * ## 为什么用 `hasPrimaryClip()` 先判一次
 * 某些 ROM（以及 API 29 之后对后台应用）在无内容时不是返回 `null` 而是返回一个
 * **空 ClipData**。直接取第 0 项会 `IndexOutOfBounds`，看起来像崩溃而不是"没内容"。
 *
 * ## 为什么拒绝 URI 型内容（2026-10-27）
 * `coerceToText` 会把复制图片/文件产生的 `content://` URI 转成字符串。直接保存会得到
 * 一条"看起来像乱码"的文字记录，而用户以为收藏了那张图。PRD 也明确不要求支持图片/文件
 * URI，因此这里先识别出 URI 型条目，返回 [ClipboardReadResult.Unsupported]。
 * 普通网页链接（`https://…`）仍是文本，照常保存。
 */
@Singleton
class AndroidClipboardReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ClipboardReader {

    override suspend fun read(): ClipboardReadResult = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Log.w(TAG, "剪贴板服务不可用")
            return@withContext ClipboardReadResult.Unavailable
        }

        try {
            if (!manager.hasPrimaryClip()) return@withContext ClipboardReadResult.Empty
            val clip = manager.primaryClip ?: return@withContext ClipboardReadResult.Empty
            if (clip.itemCount == 0) return@withContext ClipboardReadResult.Empty

            val item = clip.getItemAt(0)
                ?: return@withContext ClipboardReadResult.Empty

            if (!isTextual(item, clip.description)) {
                return@withContext ClipboardReadResult.Unsupported
            }

            // coerceToText 会处理 Html 类型：浏览器复制的富文本，getText() 常返回 null，
            // 而 coerceToText 能拿到纯文本
            val text = item.coerceToText(context)?.toString()
                ?: return@withContext ClipboardReadResult.Unsupported
            if (text.isBlank()) {
                ClipboardReadResult.Empty
            } else {
                ClipboardReadResult.Text(text)
            }
        } catch (e: Exception) {
            // 安全策略 / 剪贴板服务异常：区别于"空"，界面要提示"读不到"
            Log.w(TAG, "读取剪贴板失败", e)
            ClipboardReadResult.Unavailable
        }
    }

    /**
     * 是否是可以当正文保存的文字。
     *
     * 判据刻意不看 MIME 的 `text/uri-list`：它承载的是文件/图片地址，
     * 保存成文字没有意义。含有真实文本（`text` / `htmlText`）时优先认文本，
     * 只有条目本身就是 URI 且没有文本时才算非文本。
     */
    private fun isTextual(item: ClipData.Item, description: ClipDescription): Boolean {
        // 图片 / 文件型条目：即使 item.text 里带着 URI，也不能当成用户正文保存
        val resourceMime = listOf("image/*", "audio/*", "video/*", "application/*")
            .any { description.hasMimeType(it) }
        if (resourceMime) return false

        val rawText = item.text?.toString()
        val pointsToResource = rawText?.startsWith("content://") == true ||
            rawText?.startsWith("file://") == true
        if (pointsToResource) return false
        if (!rawText.isNullOrBlank() || !item.htmlText.isNullOrBlank()) return true

        // 某些输入法只给 MIME 不给文本：此时 coerceToText 仍可能产出可读内容
        return description.hasMimeType("text/plain") || description.hasMimeType("text/html")
    }

    private companion object {
        const val TAG = "ClipboardReader"
    }
}
