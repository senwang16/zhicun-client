package com.zhicun.app.data.repository

import android.content.Context
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 搜索历史仓储（M21）。
 *
 * 用真实 Room（in-memory）而不是 mock DAO：本类要验证的正是"去重 / 排序 / 裁剪"
 * 这三条由 SQL 承担的行为，mock 掉 DAO 等于把被测逻辑一起 mock 了。
 */
@RunWith(RobolectricTestRunner::class)
class SearchHistoryRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: SearchHistoryRepositoryImpl

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = AppDatabaseFactory.createInMemory(context)
        repository = SearchHistoryRepositoryImpl(database.searchHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun Record_sameQueryIgnoringCase_keepsSingleRowWithLatestSpelling() = runBlocking {
        repository.record("Compose", now = 1_000)
        repository.record("compose", now = 2_000)

        val entries = repository.observeRecent(10).first()
        assertEquals("大小写不同也算同一条", 1, entries.size)
        assertEquals("compose", entries.single().query)
        assertEquals(2_000, entries.single().searchedAt)
    }

    @Test
    fun Record_ordersByMostRecentFirst() = runBlocking {
        repository.record("陪伴", now = 1_000)
        repository.record("工作", now = 2_000)
        repository.record("旅行", now = 3_000)

        val queries = repository.observeRecent(10).first().map { it.query }
        assertEquals(listOf("旅行", "工作", "陪伴"), queries)
    }

    @Test
    fun Record_trimsToMaxEntries() = runBlocking {
        repeat(SearchHistoryRepository.MAX_ENTRIES + 5) { index ->
            repository.record("查询$index", now = 1_000L + index)
        }

        val entries = repository.observeRecent(SearchHistoryRepository.MAX_ENTRIES + 100).first()
        assertEquals(SearchHistoryRepository.MAX_ENTRIES, entries.size)
        assertEquals("最早写的应先被裁掉", "查询5", entries.last().query)
        assertEquals("最新写的必须还在", "查询34", entries.first().query)
    }

    @Test
    fun Delete_removesOnlyMatchingKey() = runBlocking {
        repository.record("陪伴", now = 1_000)
        repository.record("工作", now = 2_000)

        repository.delete("陪伴")

        assertEquals(listOf("工作"), repository.observeRecent(10).first().map { it.query })
    }

    @Test
    fun Clear_removesAllRows() = runBlocking {
        repository.record("陪伴", now = 1_000)
        repository.record("工作", now = 2_000)

        repository.clear()

        assertTrue(repository.observeRecent(10).first().isEmpty())
    }
}