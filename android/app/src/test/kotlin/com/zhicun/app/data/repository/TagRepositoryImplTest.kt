package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.domain.model.TagSource
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
 * 标签仓储（M11）—— 真实 SQLite。
 *
 * 这一层要钉住的是 M11 的硬约束：**重新生成 AI 标签不得动用户标签**，
 * 以及同名同源标签复用同一行（否则检索与展示会出现重复标签）。
 */
@RunWith(RobolectricTestRunner::class)
class TagRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: TagRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = TagRepositoryImpl(database.tagDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seedRecord(id: String) = runBlocking {
        database.recordDao().insert(
            com.zhicun.app.data.local.entity.RecordEntity(
                id = id,
                type = com.zhicun.app.domain.model.RecordType.TEXT_NOTE,
                captureSource = com.zhicun.app.domain.model.CaptureSource.MANUAL,
                rawText = "内容",
                sourceUrl = null,
                sourceTitle = null,
                sourceApp = null,
                createdAt = 1L,
                updatedAt = 1L,
                localStatus = com.zhicun.app.domain.model.LocalStatus.SAVED,
                processingStatus = com.zhicun.app.domain.model.ProcessingStatus.PENDING,
                sensitivityStatus = com.zhicun.app.domain.model.SensitivityStatus.CLEAR,
                recordRevision = 1,
                isDeleted = false,
            ),
        )
    }

    @Test
    fun ReplaceAiTags_insertsAndAssociates() = runBlocking {
        seedRecord("r1")

        repository.replaceAiTags("r1", listOf("产品设计", "AI"), now = 10L)

        val tags = repository.getByRecordId("r1")
        assertEquals(listOf("产品设计", "AI"), tags.map { it.name })
        assertTrue(tags.all { it.source == TagSource.AI })
    }

    @Test
    fun ReplaceAiTags_secondRun_dropsOldAiTags() = runBlocking {
        seedRecord("r1")
        repository.replaceAiTags("r1", listOf("旧的"), now = 10L)

        repository.replaceAiTags("r1", listOf("新的"), now = 20L)

        assertEquals(listOf("新的"), repository.getByRecordId("r1").map { it.name })
    }

    @Test
    fun ReplaceAiTags_keepsUserTags() = runBlocking {
        seedRecord("r1")
        val userTagId = "user-tag"
        database.tagDao().insert(
            com.zhicun.app.data.local.entity.TagEntity(
                id = userTagId,
                name = "我手动加的",
                source = TagSource.USER,
                confidence = null,
                createdAt = 1L,
            ),
        )
        database.tagDao().attachCrossRef(
            com.zhicun.app.data.local.entity.RecordTagCrossRef("r1", userTagId),
        )
        repository.replaceAiTags("r1", listOf("AI标签"), now = 10L)

        val names = repository.getByRecordId("r1").map { it.name }
        assertTrue("用户标签必须原样保留", names.contains("我手动加的"))
        assertTrue(names.contains("AI标签"))
    }

    @Test
    fun ReplaceAiTags_sameNameAcrossRecords_sharesSingleTagRow() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")

        repository.replaceAiTags("r1", listOf("会议"), now = 10L)
        repository.replaceAiTags("r2", listOf("会议"), now = 20L)

        // 同名同源只有一行 tag，两条记录各自挂一条关联
        val rows = database.tagDao().observeAllWithRecord().first()
        assertEquals(2, rows.size)
        assertEquals("同一个标签行应被复用", 1, rows.map { it.tag.id }.distinct().size)
    }

    @Test
    fun ObserveAllWithRecord_groupsByRecord() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.replaceAiTags("r1", listOf("一"), now = 1L)
        repository.replaceAiTags("r2", listOf("二"), now = 2L)

        val rows = repository.observeAllWithRecord().first()

        assertEquals(setOf("r1", "r2"), rows.map { it.recordId }.toSet())
        assertEquals("一", rows.first { it.recordId == "r1" }.tag.name)
    }

    @Test
    fun ReplaceAiTags_emptyList_clearsOnlyAiTags() = runBlocking {
        seedRecord("r1")
        repository.replaceAiTags("r1", listOf("会被清掉"), now = 1L)

        repository.replaceAiTags("r1", emptyList(), now = 2L)

        assertTrue(repository.getByRecordId("r1").isEmpty())
    }

    // ------------------------------------------------------------ 用户标签（M16）

    @Test
    fun AddUserTag_insertsAndAttaches() = runBlocking {
        seedRecord("r1")

        val added = repository.addUserTag("r1", "会议", now = 10L)

        assertTrue(added)
        val tags = repository.getByRecordId("r1")
        assertEquals(listOf("会议"), tags.map { it.name })
        assertEquals(TagSource.USER, tags.single().source)
    }

    @Test
    fun AddUserTag_sameNameTwice_reportsDuplicate() = runBlocking {
        seedRecord("r1")
        repository.addUserTag("r1", "会议", now = 10L)

        val added = repository.addUserTag("r1", "会议", now = 20L)

        assertEquals(false, added)
        assertEquals("不能出现两行同名关联", 1, repository.getByRecordId("r1").size)
    }

    /** 同名同源复用同一行 tag（真实表上的唯一索引行为），否则检索与展示会看到重复标签。 */
    @Test
    fun AddUserTag_sameNameAcrossRecords_sharesSingleTagRow() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")

        repository.addUserTag("r1", "会议", now = 10L)
        repository.addUserTag("r2", "会议", now = 20L)

        val tags = database.tagDao().getByRecordId("r1") + database.tagDao().getByRecordId("r2")
        assertEquals(2, tags.size)
        assertEquals(1, tags.map { it.id }.distinct().size)
    }

    /**
     * 解除关联只删关联行：同名标签还被别的记录挂着，删标签行会让那条记录跟着丢标签。
     */
    @Test
    fun RemoveTag_detachesOnlyThisRecord() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.addUserTag("r1", "会议", now = 10L)
        repository.addUserTag("r2", "会议", now = 20L)
        val tagId = repository.getByRecordId("r1").single().id

        repository.removeTag("r1", tagId)

        assertTrue("r1 的关联应该没了", repository.getByRecordId("r1").isEmpty())
        assertEquals("r2 的标签不能受影响", listOf("会议"), repository.getByRecordId("r2").map { it.name })
    }

    /** 用户也能删掉 AI 标签：不该被"这是模型加的"挡住。 */
    @Test
    fun RemoveTag_canDetachAiTag() = runBlocking {
        seedRecord("r1")
        repository.replaceAiTags("r1", listOf("AI给的"), now = 10L)
        val tagId = repository.getByRecordId("r1").single().id

        repository.removeTag("r1", tagId)

        assertTrue(repository.getByRecordId("r1").isEmpty())
    }

    /** 重新生成 AI 标签不得把它们之外的用户标签带走（M11 约束在 M16 依然成立）。 */
    @Test
    fun ReplaceAiTags_keepsUserTagsAddedByHand() = runBlocking {
        seedRecord("r1")
        repository.addUserTag("r1", "我加的", now = 10L)
        repository.replaceAiTags("r1", listOf("AI给的"), now = 20L)

        val names = repository.getByRecordId("r1").map { it.name }

        assertTrue(names.contains("我加的"))
        assertTrue(names.contains("AI给的"))
    }
}
