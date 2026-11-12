package com.zhicun.app.domain.model

/**
 * 一次搜索结果的标签索引（M21 决策 3）。
 *
 * `tags` 是结果页要渲染的 chip（按结果内命中记录数降序、最多 6 个）；
 * `recordIdsByTag` 是"点某个 chip 时该收窄到哪些记录"的映射。
 * 两者在同一次查询里一起算出来，避免点击时再查一次库。
 *
 * 为什么不在点击时重新查：结果集上限 50 条，点一下再跑一条 `IN (50 ids)` 的
 * 查询虽然不慢，但收窄/恢复是纯 UI 过滤语义，不该有 IO 抖动；而且重查会
 * 把"两段式检索中途结果被替换"的时序问题带进来。
 */
data class ResultTagIndex(
    val tags: List<TagUsage> = emptyList(),
    val recordIdsByTag: Map<String, Set<String>> = emptyMap(),
) {
    /** 该标签对应的记录 id；标签已不在索引里时返回空集（调用方据此恢复完整结果）。 */
    fun recordIdsFor(tag: String): Set<String> = recordIdsByTag[tag].orEmpty()

    companion object {
        val EMPTY = ResultTagIndex()
    }
}