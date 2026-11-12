package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.SearchHistoryDao
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.repository.SearchHistoryRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SearchHistoryRepository] 的 Room 实现（M21）。
 *
 * ## 去重键
 * `query_key = normalize(query).lowercase()`：英文查询 "Compose" 与 "compose" 视为
 * 同一条历史（SQLite 的 LIKE/FTS 对 ASCII 本来就不区分大小写，历史口径应一致）；
 * 展示串保留最近一次写法。中文没有大小写，不受影响。
 *
 * ## 并发
 * 先 `UPDATE` 再 `INSERT`：两条极端并发（同一查询几乎同时记录两次）时可能都插一次，
 * 但表上 `query_key` 是主键、插入用 `IGNORE`，最坏结果只是少记一次计数，
 * 不会出现重复行。个人单机使用，这个代价可以接受。
 */
@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val dao: SearchHistoryDao,
) : SearchHistoryRepository {

    override suspend fun record(normalizedQuery: String, now: Long) {
        val key = normalizedQuery.lowercase(Locale.ROOT)
        // 三步写入在 DAO 的 @Transaction 里完成：协程在任意一步被取消都不会留下半截状态
        dao.record(
            queryKey = key,
            query = normalizedQuery,
            now = now,
            keep = SearchHistoryRepository.MAX_ENTRIES,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>> =
        dao.observeRecent(limit).map { rows ->
            rows.map { row ->
                SearchHistoryEntry(
                    queryKey = row.queryKey,
                    query = row.query,
                    searchedAt = row.lastSearchedAt,
                )
            }
        }

    override suspend fun delete(queryKey: String) {
        dao.deleteByKey(queryKey)
    }

    override suspend fun clear() {
        dao.clear()
    }
}