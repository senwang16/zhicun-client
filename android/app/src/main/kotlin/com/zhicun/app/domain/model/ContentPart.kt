package com.zhicun.app.domain.model

/**
 * 一条记录里"可能被发出去的一块内容"（2026-10-23 R-2）。
 *
 * ## 为什么需要它
 * 用户点"确认上传"时，看到的是**当时已经存在**的内容。但上传是分批发生的：
 * 摘要可能晚于 OCR、向量可能晚于转写。此前的确认只比对内容版本号（`recordRevision`），
 * 而版本号在派生内容落地时并不递增 —— 于是"确认过一次"等于给这条记录发了一张
 * 永久通行证，连后来才产生的 OCR / 转写文本也一起放行（这正是 PRD §6.4
 * "不因一次确认就把整条记录永久标记为安全"要防的情况）。
 *
 * ## 怎么用
 * 确认时把"此刻存在的内容块"记进 [Record.cloudConfirmedScope]；
 * 每次真正外发前算出这一次用到哪些块（[com.zhicun.app.domain.ai.ProcessingInputText]），
 * 要求它们是确认范围的子集。多出来的那块意味着"用户没见过它"，必须重新确认。
 *
 * ## 为什么是枚举而不是字符串
 * 存进 DB 时用 `name` 拼逗号串（`cloud_confirmed_scope`）。枚举保证编码/解码两侧
 * 用的是同一份取值，新增一种内容时编译器会在 `when` 里报错，而不是悄悄写进一个
 * 拼错的字符串。
 */
enum class ContentPart {

    /** 用户自己写下的原文。 */
    RAW_TEXT,

    /** 来源标题（分享时对方给的标题、网页 `<title>`）。 */
    SOURCE_TITLE,

    /** 网页正文。 */
    PAGE_TEXT,

    /** 图片 OCR 出来的文字。 */
    OCR_TEXT,

    /** 语音转写文本。 */
    TRANSCRIPTION,

    /** 摘要本身（向量化时可能只用摘要，不再传原文）。 */
    SUMMARY,

    /** AI 生成的标签。 */
    TAGS,

    /** 附件本体：语音音频、图片字节 —— 内容不在文字里，但仍然会被上传。 */
    ATTACHMENT,
    ;

    /**
     * 这块内容外发前是否需要落在用户的确认范围内。
     *
     * `SUMMARY` / `TAGS` **不需要**：它们是 AI 自己不透明地浓缩出来的产物，
     * 不含任何"用户没看过的新信息" —— 而它们能存在，本身就证明原材料当时已经通过了闸门
     * （摘要被拦就不会有摘要）。若也要求确认，会出现这个死循环：
     * 用户确认原文 → 摘要生成 → 向量要发摘要 → 摘要不在范围里 → 又要确认 → 又生成新摘要…
     * 结果是"语义检索永久不可用"，而用户看到的只是"点了确认也没用"。
     *
     * 反过来说，`PAGE_TEXT` / `OCR_TEXT` / `TRANSCRIPTION` 虽然是程序产出的，
     * 但它们承载的是**外部世界的内容**（网页、图片、录音），用户很可能没读过，必须确认。
     */
    val requiresConfirmation: Boolean
        get() = this != SUMMARY && this != TAGS

    companion object {

        /** 编码成 DB 里的一列（逗号分隔）。空集合编码成空串。 */
        fun encode(parts: Set<ContentPart>): String =
            parts.sortedBy { it.ordinal }.joinToString(",") { it.name }

        /**
         * 从 DB 列解码。
         *
         * 不认识的名字直接忽略（用户可能先装了新版本又回退，或者数据被外部工具改过）——
         * 忽略的后果只是"这块内容不在确认范围里"，也就是更严格，方向是安全的。
         */
        fun decode(raw: String?): Set<ContentPart> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(',')
                .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
                .toSet()
        }
    }
}
