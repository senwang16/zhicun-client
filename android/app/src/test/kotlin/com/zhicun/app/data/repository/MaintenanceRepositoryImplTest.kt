package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.AssetEntity
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.ProcessingJobEntity
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 数据维护仓储（M15）—— 真实 SQLite。
 *
 * 这里要钉的是**那条护栏**：`purgeRecord` 只删已标记删除的记录。
 * 假实现可以自己"守规矩"，但真正决定成败的是那条 SQL 里的 `is_deleted = 1`，
 * 只有跑在真库上才算验证过。
 *
 * 顺带钉住"一条记录连同附件、派生内容、任务、标签关联一起消失"——
 * 少删一张表就会留下既显示不出、也无从清理的孤儿行。
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MaintenanceRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = MaintenanceRepositoryImpl(database.maintenanceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 种一条记录及其全部附属行（附件 / 派生内容 / 任务 / 标签关联）。 */
    private fun seedRecord(id: String, deleted: Boolean) = runBlocking {
        database.recordDao().insert(
            RecordEntity(
                id = id,
                type = RecordType.TEXT_NOTE,
                captureSource = CaptureSource.MANUAL,
                rawText = "内容",
                sourceUrl = null,
                sourceTitle = null,
                sourceApp = null,
                createdAt = 1L,
                updatedAt = 1L,
                localStatus = LocalStatus.SAVED,
                processingStatus = ProcessingStatus.DONE,
                sensitivityStatus = SensitivityStatus.CLEAR,
                recordRevision = 1,
                isDeleted = deleted,
            ),
        )
        database.assetDao().insert(
            AssetEntity(
                id = "asset-$id",
                recordId = id,
                mimeType = "image/png",
                localPath = "/data/assets/$id/asset-$id",
                fileSize = 2048L,
                fileHash = null,
                originalUri = null,
                saveStatus = AssetSaveStatus.SAVED,
            ),
        )
        database.derivedContentDao().upsert(
            DerivedContentEntity(
                id = "derived-$id",
                recordId = id,
                contentType = DerivedType.OCR_TEXT,
                content = "识别结果",
                processorVersion = "test/1.0",
                processedAt = 2L,
                errorMessage = null,
            ),
        )
        database.processingJobDao().insert(
            ProcessingJobEntity(
                id = "job-$id",
                recordId = id,
                jobType = JobType.OCR,
                status = JobStatus.DONE,
                attempts = 1,
                nextRetryAt = null,
                errorCategory = null,
                startedAt = 1L,
                finishedAt = 2L,
            ),
        )
        database.tagDao().insert(
            TagEntity(
                id = "tag-$id",
                name = "标签-$id",
                source = TagSource.USER,
                confidence = null,
                createdAt = 1L,
            ),
        )
        database.tagDao().attachCrossRef(RecordTagCrossRef(recordId = id, tagId = "tag-$id"))
    }

    private suspend fun childRows(id: String): List<Int> = listOf(
        database.assetDao().getByRecordId(id).size,
        database.derivedContentDao().getByRecordId(id).size,
        database.tagDao().getByRecordId(id).size,
    )

    // ------------------------------------------------------------ 查询范围

    @Test
    fun GetSoftDeletedRecords_returnsOnlyDeletedOnes() = runBlocking {
        seedRecord("gone", deleted = true)
        seedRecord("alive", deleted = false)

        val records = repository.getSoftDeletedRecords()

        assertEquals(listOf("gone"), records.map { it.id })
        assertTrue("映射回 domain 时 deleted 标记要保持", records.single().isDeleted)
    }

    @Test
    fun GetSoftDeletedRecords_emptyWhenNothingDeleted() = runBlocking {
        seedRecord("alive", deleted = false)

        assertTrue(repository.getSoftDeletedRecords().isEmpty())
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun PurgeRecord_removesRecordAndEveryChildRow() = runBlocking {
        seedRecord("gone", deleted = true)
        assertEquals("前置：四条附属行都应存在", listOf(1, 1, 1), childRows("gone"))

        val purged = repository.purgeRecord("gone")

        assertTrue(purged)
        assertNull(database.recordDao().getById("gone"))
        assertEquals("附件行必须一并删掉", listOf(0, 0, 0), childRows("gone"))
        assertNull(
            database.processingJobDao().getByRecordAndType("gone", JobType.OCR),
        )
    }

    /**
     * 核心护栏：**未标记删除的记录不能被 purgeRecord 删掉**。
     * 少这一条，一次误调就会造成不可恢复的数据损失（本 App 没有云端备份）。
     */
    @Test
    fun PurgeRecord_refusesLiveRecordAndTouchesNothing() = runBlocking {
        seedRecord("alive", deleted = false)

        val purged = repository.purgeRecord("alive")

        assertFalse(purged)
        assertTrue("记录必须还在", database.recordDao().getById("alive") != null)
        assertEquals("附属行也一条都不能少", listOf(1, 1, 1), childRows("alive"))
    }

    @Test
    fun PurgeRecord_unknownIdReturnsFalse() = runBlocking {
        assertFalse(repository.purgeRecord("does-not-exist"))
    }

    /** 重复清理是常态（连点、失败重试）：第二次必须安静地返回 false。 */
    @Test
    fun PurgeRecord_isIdempotent() = runBlocking {
        seedRecord("gone", deleted = true)

        assertTrue(repository.purgeRecord("gone"))
        assertFalse(repository.purgeRecord("gone"))
    }

    /** 只删目标记录，同库里的其它已删除记录不受影响。 */
    @Test
    fun PurgeRecord_doesNotTouchOtherDeletedRecords() = runBlocking {
        seedRecord("gone-1", deleted = true)
        seedRecord("gone-2", deleted = true)

        repository.purgeRecord("gone-1")

        assertEquals(listOf("gone-2"), repository.getSoftDeletedRecords().map { it.id })
        assertEquals(listOf(1, 1, 1), childRows("gone-2"))
    }
}
