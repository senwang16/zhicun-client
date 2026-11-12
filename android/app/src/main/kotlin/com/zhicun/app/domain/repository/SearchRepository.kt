package com.zhicun.app.domain.repository

/**
 * 关键词检索仓储（M10）。
 *
 * 只返回 recordId 而不是 [com.zhicun.app.domain.model.Record]：
 * "检索"和"取实体"是两件事，分开后排序策略（M12 会引入语义分）可以只在 id 层做，
 * 不必把整行数据搬进内存再排序。
 */
interface SearchRepository {

    /**
     * 检索命中的 recordId，按相关性/新旧顺序返回，**已去重**。
     *
     * @param normalized 已由 [com.zhicun.app.domain.search.SearchQuery.normalize] 归一化的查询串
     * @param limit 上限（含）
     */
    suspend fun searchIds(normalized: String, limit: Int): List<String>
}
