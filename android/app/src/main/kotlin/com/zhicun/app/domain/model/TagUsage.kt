package com.zhicun.app.domain.model

/**
 * 标签使用情况（M21 搜索页"标签"区块）。
 *
 * 同名标签可能分别由 AI 与用户创建（`tags` 表以 `(name, source)` 唯一），
 * 但用户看到的是同一个分类，因此这一层按**名字合并**，`recordCount` 是去重后的记录数。
 */
data class TagUsage(
    val name: String,
    val recordCount: Int,
)