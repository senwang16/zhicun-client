package com.zhicun.app.domain.model

/**
 * AI / 后台产生的派生内容（Domain 模型，对应 ARCHITECTURE.md §4.3 / PRD §9.3）。
 *
 * 派生内容是**可重建的**：处理失败时保留 [errorMessage]，成功时覆盖同类型旧结果，
 * 但永远不修改 [Record.rawText]。
 */
data class DerivedContent(
    val id: String,
    val recordId: String,
    val contentType: DerivedType,
    val content: String?,
    val processorVersion: String,
    val processedAt: Long,
    val errorMessage: String?,
    /**
     * 这条派生内容属于哪个附件。
     *
     * `null` = 记录级（PAGE_TEXT / TITLE / SUMMARY / TAGS / EMBEDDING）；
     * 非空 = 附件级（OCR_TEXT / TRANSCRIPTION），一条记录可以有多个同类型结果。
     * 库里用空串存记录级，因为 SQLite 的唯一索引不去重 NULL（见 v8 迁移注释）。
     */
    val assetId: String? = null,
)

/** 派生内容类型。EMBEDDING 的 content 存放序列化后的向量（方案待 T-003 决策）。 */
enum class DerivedType {
    PAGE_TEXT,
    OCR_TEXT,
    TRANSCRIPTION,
    TITLE,
    SUMMARY,
    TAGS,
    EMBEDDING,

    /**
     * 读到无法识别的库值时**只在解码层**使用的占位（2026-11-10 Bug 4）。
     *
     * ## 为什么需要它
     * DB 列存的是枚举常量名（见 `Converters`），一旦库里出现别的值（手工 SQL 预埋、
     * 版本回退、外部导入），原先的 `valueOf` 会抛 `IllegalArgumentException`，
     * 而这条异常发生在 Room 读行的过程中 —— 一条脏派生数据就让整页详情报
     * "Couldn't load this record"，连正文都看不到（真机实测：`content_type='TRANSCRIBE'`）。
     *
     * ## 使用边界
     * - **永不写库**：写入方的类型永远是具体值，`fromRaw` 也不会返回它；
     * - **永不展示**：仓储层读到它就跳过该行（`DerivedContent.toDomainOrNull`），
     *   因此界面与导出链路都看不到 UNKNOWN；
     * - 它不参与任何"正文/摘要/向量"的类型判断（那些一律按具体类型比对）。
     */
    UNKNOWN,
    ;

    companion object {
        /**
         * 容错解析：大小写/未知值一律返回 null，由调用方决定兜底方式。
         *
         * 刻意不接受字面量 `UNKNOWN`：库里真的存了 `UNKNOWN` 说明数据同样不可信，
         * 按"无法识别"处理（跳过）才是正确结果。
         */
        fun fromRaw(raw: String?): DerivedType? =
            entries.firstOrNull { it != UNKNOWN && it.name == raw }
    }
}
