package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史仓储（M21）。
 *
 * 隐私边界：历史只留本机，不上传、不写日志、不进导出包；清空/删除都是**物理删除**。
 */
interface SearchHistoryRepository {

    /**
     * 记录一次搜索。
     *
     * @param normalizedQuery 已由 `SearchQuery.normalize` 归一化的查询串（非空）
     * @return 无；同一查询串（忽略英文大小写）只保留一行并累加次数
     */
    suspend fun record(normalizedQuery: String, now: Long = System.currentTimeMillis())

    /** 按最近搜索时间倒序的 `limit` 条。 */
    fun observeRecent(limit: Int = DISPLAY_LIMIT): Flow<List<SearchHistoryEntry>>

    /** 删除单条（按去重键）。 */
    suspend fun delete(queryKey: String)

    suspend fun clear()

    companion object {
        /** 库里最多保留的条数：再多也不会被展示，反而让"清空"变得不彻底。 */
        const val MAX_ENTRIES: Int = 30

        /** 搜索页空闲态最多展示的条数。 */
        const val DISPLAY_LIMIT: Int = 10
    }
}