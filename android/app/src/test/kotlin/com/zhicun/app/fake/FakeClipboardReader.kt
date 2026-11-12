package com.zhicun.app.fake

import com.zhicun.app.domain.clipboard.ClipboardReadResult
import com.zhicun.app.domain.clipboard.ClipboardReader

/**
 * 可控剪贴板（M13）。
 *
 * 优先返回 [result]；未显式设置时按 [text] 推导（null/空白 → Empty），
 * 让老测试的 `text = ...` 写法继续可用。非文本与读取失败分别用
 * [ClipboardReadResult.Unsupported] / [ClipboardReadResult.Unavailable] 表达。
 */
class FakeClipboardReader(
    var text: String? = null,
) : ClipboardReader {

    var result: ClipboardReadResult? = null

    var readCount: Int = 0

    override suspend fun read(): ClipboardReadResult {
        readCount++
        result?.let { return it }
        val value = text ?: return ClipboardReadResult.Empty
        return if (value.isBlank()) ClipboardReadResult.Empty else ClipboardReadResult.Text(value)
    }
}
