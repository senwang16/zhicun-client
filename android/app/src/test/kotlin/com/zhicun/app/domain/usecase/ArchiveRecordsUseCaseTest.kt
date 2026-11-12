package com.zhicun.app.domain.usecase

import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批量归档 / 取消归档（M16）。
 *
 * 归档是**可逆**动作，但"批量"让它有了新的失败面：多选列表里的 id 可能重复、
 * 可能包含刚被删掉的记录、可能是空列表。这三种情况都会让界面上的"已归档 N 条"
 * 与用户看到的结果对不上，所以逐条钉住。
 */
class ArchiveRecordsUseCaseTest {

    private lateinit var records: FakeRecordRepository

    @Before
    fun setUp() {
        records = FakeRecordRepository(
            listOf(
                // createdAt 特意各不相同：收件箱是"新的在前"，排序断言才有意义
                testRecord(id = "r1", createdAt = 1_000L),
                testRecord(id = "r2", createdAt = 2_000L),
                testRecord(id = "r3", createdAt = 3_000L),
            ),
        )
    }

    private fun useCase() = ArchiveRecordsUseCase(records)

    // ------------------------------------------------------------ 归档

    @Test
    fun Archive_setsTimestampOnEveryTarget() = runBlocking {
        val count = useCase()(listOf("r1", "r2"), now = 5_000L)

        assertEquals(2, count)
        assertEquals(5_000L, records.byId["r1"]?.archivedAt)
        assertEquals(5_000L, records.byId["r2"]?.archivedAt)
        assertNull("不在入参里的记录不能被动", records.byId["r3"]?.archivedAt)
    }

    /**
     * 归档后必须**离开收件箱**、出现在归档列表里 —— 这是"归档"这个动作的全部意义。
     * 直接断言两个视图，而不是只断言 `archivedAt` 非空：字段写对了但 SQL 过滤没写，
     * 用户看到的仍然是"点了归档，记录还在原地"。
     */
    @Test
    fun Archive_removesFromInboxAndAddsToArchive() = runBlocking {
        useCase()(listOf("r1"), now = 5_000L)

        val inbox = inboxIds()
        val archived = archivedIds()
        assertEquals(listOf("r3", "r2"), inbox)
        assertEquals(listOf("r1"), archived)
    }

    @Test
    fun Archive_duplicateIdsAreCountedOnce() = runBlocking {
        // 多选模式下"先选中、取消、再选中"会让同一个 id 进两次
        val count = useCase()(listOf("r1", "r1", "r2"), now = 5_000L)

        assertEquals("界面上的'已归档 N 条'不能把重复算进去", 2, count)
    }

    @Test
    fun Archive_blankIdsAreIgnored() = runBlocking {
        val count = useCase()(listOf("r1", "", "  "), now = 5_000L)

        assertEquals(1, count)
    }

    /** 空列表是正常路径（用户什么都没选就点了归档）：不能报错，也不该打数据库。 */
    @Test
    fun Archive_emptyList_doesNothing() = runBlocking {
        assertEquals(0, useCase()(emptyList(), now = 5_000L))
        assertTrue(records.byId.values.all { it.archivedAt == null })
    }

    /** 已删除的记录不该被归档"复活"：它随时会被 M15 清理掉。 */
    @Test
    fun Archive_deletedRecord_isNotTouched() = runBlocking {
        records.stored.value = records.stored.value.map {
            if (it.id == "r1") it.copy(isDeleted = true) else it
        }

        val count = useCase()(listOf("r1"), now = 5_000L)

        assertEquals(0, count)
        assertNull(records.stored.value.first { it.id == "r1" }.archivedAt)
    }

    @Test
    fun Archive_unknownId_returnsZero() = runBlocking {
        assertEquals(0, useCase()(listOf("ghost"), now = 5_000L))
    }

    // ------------------------------------------------------------ 取消归档

    @Test
    fun Restore_clearsTimestampAndReturnsToInbox() = runBlocking {
        useCase()(listOf("r1"), now = 5_000L)

        val count = useCase().restore(listOf("r1"), now = 6_000L)

        assertEquals(1, count)
        assertNull(records.byId["r1"]?.archivedAt)
        assertTrue("r1" in inboxIds())
        assertTrue("r1" !in archivedIds())
    }

    @Test
    fun Restore_emptyList_doesNothing() = runBlocking {
        useCase()(listOf("r1"), now = 5_000L)

        assertEquals(0, useCase().restore(emptyList(), now = 6_000L))
        assertEquals(5_000L, records.byId["r1"]?.archivedAt)
    }

    // ------------------------------------------------------------ 视图

    private suspend fun inboxIds(): List<String> = records.observeInbox().first().map { it.id }

    private suspend fun archivedIds(): List<String> = records.observeArchived().first().map { it.id }
}
