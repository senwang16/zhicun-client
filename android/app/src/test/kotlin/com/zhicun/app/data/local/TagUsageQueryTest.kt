package com.zhicun.app.data.local

import android.content.Context
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 标签概览查询（M21）：只统计"搜得到"的记录，同名 AI/用户标签合并。
 *
 * 这两条是标签 chip 能不能点开有结果的前提：
 * - 已归档/已删除的记录不在检索范围里，它们的标签也不该出现在入口上；
 * - 同一分类被 AI 和用户各建一行时，界面上只能出现一个 `#工作`。
 */
@RunWith(RobolectricTestRunner::class)
class TagUsageQueryTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = AppDatabaseFactory.createInMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedRecord(
        id: String,
        isDeleted: Boolean = false,
        archivedAt: Long? = null,
    ) {
        database.recordDao().insert(
            testRecord(id = id, isDeleted = isDeleted, archivedAt = archivedAt).toEntity(),
        )
    }

    private suspend fun seedTag(id: String, name: String, source: TagSource) {
        database.tagDao().insert(
            TagEntity(id = id, name = name, source = source, confidence = null, createdAt = 1_000),
        )
    }

    @Test
    fun Usage_mergesSameNameAcrossSourcesAndIgnoresInvisibleRecords() = runBlocking {
        seedRecord("r-active-1")
        seedRecord("r-active-2")
        seedRecord("r-archived", archivedAt = 5_000)
        seedRecord("r-deleted", isDeleted = true)

        seedTag("t-work-ai", "工作", TagSource.AI)
        seedTag("t-work-user", "工作", TagSource.USER)
        seedTag("t-travel-ai", "旅行", TagSource.AI)

        // 同一记录同时挂 AI 与用户两个同名标签：只能算 1 条记录
        database.tagDao().attachCrossRef(RecordTagCrossRef("r-active-1", "t-work-ai"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r-active-1", "t-work-user"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r-active-2", "t-work-ai"))
        // 不可见记录上的标签不得参与
        database.tagDao().attachCrossRef(RecordTagCrossRef("r-archived", "t-travel-ai"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r-deleted", "t-travel-ai"))

        val usage = database.tagDao().observeTagUsage(limit = 10).first()

        assertEquals("同名标签必须合并成一条", 1, usage.size)
        assertEquals("工作", usage.single().name)
        assertEquals("同一记录挂两个同名标签只算一次", 2, usage.single().recordCount)
    }

    @Test
    fun Usage_ordersByRecordCountThenNameAndHonorsLimit() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        seedRecord("r3")

        seedTag("t-a", "学习", TagSource.AI)
        seedTag("t-b", "工作", TagSource.AI)
        seedTag("t-c", "旅行", TagSource.AI)

        // 学习 3 条、工作 2 条、旅行 1 条
        listOf("t-a" to "r1", "t-a" to "r2", "t-a" to "r3", "t-b" to "r1", "t-b" to "r2", "t-c" to "r1")
            .forEach { (tagId, recordId) ->
                database.tagDao().attachCrossRef(RecordTagCrossRef(recordId, tagId))
            }

        val topTwo = database.tagDao().observeTagUsage(limit = 2).first()

        assertEquals(listOf("学习", "工作"), topTwo.map { it.name })
        assertEquals(listOf(3, 2), topTwo.map { it.recordCount })
    }

    @Test
    fun GetForRecords_returnsOnlyRequestedRecords() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        seedRecord("r3")
        seedTag("t-work", "工作", TagSource.AI)
        seedTag("t-meeting", "会议", TagSource.AI)

        database.tagDao().attachCrossRef(RecordTagCrossRef("r1", "t-work"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r1", "t-meeting"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r2", "t-work"))
        database.tagDao().attachCrossRef(RecordTagCrossRef("r3", "t-meeting"))

        val rows = database.tagDao().getForRecords(listOf("r1", "r2"))

        assertEquals("只取请求的两条记录", setOf("r1", "r2"), rows.map { it.recordId }.toSet())
        assertEquals(3, rows.size)
        assertEquals(listOf("工作", "会议", "工作"), rows.map { it.tag.name })
    }
}