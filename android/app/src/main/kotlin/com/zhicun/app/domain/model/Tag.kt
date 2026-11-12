package com.zhicun.app.domain.model

/**
 * 标签（Domain 模型，对应 PRD §9.4）。
 *
 * AI 标签与用户标签**分开存储**（PRD §6.3 / M11 验收）：AI 重新处理只能覆盖 [TagSource.AI] 的标签，
 * 不得删除或改写用户手动创建的标签。`confidence` 仅 AI 标签有意义，用户标签为 null。
 */
data class Tag(
    val id: String,
    val name: String,
    val source: TagSource,
    val confidence: Double?,
    val createdAt: Long,
)

/** 标签来源。 */
enum class TagSource {
    USER,
    AI,
}
