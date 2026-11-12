package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.AUDIO_MIME_PREFIX
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.model.RecordType

/**
 * 从输入内容推断记录类型（PRD §4.1："类型由系统初步推断，用户不需要在收集时选择"）。
 *
 * 优先级（顺序不能换）：
 * 1. 有图片 ⇒ `IMAGE`（文字同时保留在 `rawText`，PRD 允许一条记录多形态）
 * 2. 有音频 ⇒ `VOICE_NOTE`（M9：App 内录音保存）
 * 3. 有其它附件 ⇒ `FILE`（PDF 等明确支持的文档）
 * 4. 纯文本且含链接 ⇒ `WEB_LINK`
 * 5. 其余纯文本 ⇒ 手动新建为 `TEXT_NOTE`，系统分享为 `SHARED_TEXT`
 *
 * 为什么图片优先于文本：分享"图片 + 一句说明"时，用户心智里主体是那张图；
 * 判成文本框会让卡片显示成一条文字，图完全看不见。
 *
 * 为什么音频排在 `FILE` 之前：录音必须落到 `VOICE_NOTE`，否则转写任务不会被调度
 * （`ProcessingJobTargets` 按记录类型决定跑什么）——一条语音被存成"文件"，
 * 用户会得到一段永远没有文字的录音。
 *
 * 为什么第 5 条要区分来源：PRD §4.1 把两类文字记录分开——`text_note` 是"用户自己想写的东西"，
 * `shared_text` 是"从别处收进来的文字"。同样的内容，入口不同，用户对它的心理归类就不同。
 */
object SharedContentClassifier {

    private val URL_REGEX = Regex("""https?://[^\s<>"'）)】]+""", RegexOption.IGNORE_CASE)
    private val IMAGE_MIME_PREFIX = "image/"

    fun classify(
        input: RecordInput,
        captureSource: CaptureSource = CaptureSource.SYSTEM_SHARE,
    ): RecordType {
        val mimes = input.supportedUris.map { it.mimeType.orEmpty() }
        return when {
            mimes.any { it.startsWith(IMAGE_MIME_PREFIX, ignoreCase = true) } -> RecordType.IMAGE
            mimes.any { it.startsWith(AUDIO_MIME_PREFIX, ignoreCase = true) } -> RecordType.VOICE_NOTE
            input.supportedUris.isNotEmpty() -> RecordType.FILE
            firstUrl(input.text) != null -> RecordType.WEB_LINK
            captureSource == CaptureSource.MANUAL -> RecordType.TEXT_NOTE
            else -> RecordType.SHARED_TEXT
        }
    }

    /**
     * 取文本里的第一个 http(s) 链接。
     *
     * 只认 `http/https`：`file://`、`content://` 出现在分享文本里通常是内部调试信息，
     * 当成"来源链接"展示反而误导用户。
     */
    fun firstUrl(text: String?): String? =
        text?.let { URL_REGEX.find(it)?.value }?.trimEnd('.', ',', ';', '。', '，', '；')
}
