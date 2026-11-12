package com.zhicun.app.fake

import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.repository.SearchHistoryRepository
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史假实现（M21）。
 *
 * 与真实实现对齐的两点：去重键 = 小写归一化串；超过 [MAX_ENTRIES] 只保留最近的。
 * `recorded` 记录调用顺序，供 ViewModel 用例断言"哪些动作会写历史"。
 */
class FakeSearchHistoryRepository(
    initial: List<SearchHistoryEntry> = emptyList(),
) : SearchHistoryRepository {

    private val entries = MutableStateFlow(initial)

    /** 每次 record 收到的查询串（按调用顺序），断言用。 */
    val recorded: MutableList<String> = mutableListOf()

    override suspend fun record(normalizedQuery: String, now: Long) {
        recorded += normalizedQuery
        val key = normalizedQuery.lowercase(Locale.ROOT)
        val next = SearchHistoryEntry(queryKey = key, query = normalizedQuery, searchedAt = now)
        entries.value = (listOf(next) + entries.value.filterNot { it.queryKey == key })
            .take(SearchHistoryRepository.MAX_ENTRIES)
    }

    override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>> =
        entries.map { list -> list.take(limit) }

    override suspend fun delete(queryKey: String) {
        entries.value = entries.value.filterNot { it.queryKey == queryKey }
    }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}