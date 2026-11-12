package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 删除记录（2026-10-24）。
 *
 * 这一层的价值不在于"调了几个方法"，而在于三条容易做坏的顺序/边界：
 * 1. **软删除必须在清理之前** —— 清理的 SQL 护栏是 `WHERE id = ? AND is_deleted = 1`，
 *    顺序反了就是"什么都没删"，而且不报错；
 * 2. **任务取消必须在清理之前** —— 否则 Worker 会在清理之后往派生表写孤儿行；
 * 3. **归档记录也必须能删** —— 归档箱里就有删除入口，而 `getByIds` 已经把归档排除了，
 *    用错方法会导致"归档的记录点了删除毫无反应"。
 */
class DeleteRecordsUseCaseTest {

    private lateinit var records: FakeRecordRepository
    private lateinit var maintenance: FakeMaintenanceRepository
    private lateinit var assets: FakeAssetRepository
    private lateinit var files: FakeLocalFileStore
    private lateinit var scheduler: FakeProcessingScheduler
    private val collections = FakeCollectionRepository()

    /** 三个假实现的公共事件流，用来断言"软删除 → 取消任务 → 清理"的相对顺序。 */
    private lateinit var events: MutableList<String>

    @Before
    fun setUp() {
        events = mutableListOf()
        maintenance = FakeMaintenanceRepository()
        assets = FakeAssetRepository()
        files = FakeLocalFileStore()
        scheduler = FakeProcessingScheduler()
        records = FakeRecordRepository()

        // 软删除要同步到清理侧：两个假实现各有各的存储，不接起来的话
        // 用例软删除之后清理看不到任何待清理记录，测试会一路绿灯但什么都没验证。
        records.onSoftDelete = { ids ->
            ids.forEach { id ->
                events += "mark:$id"
                maintenance.softDeletedRecords += testRecord(id = id, isDeleted = true)
            }
        }
        maintenance.onPurge = { events += "purge:$it" }
        files.onDelete = { events += "file:$it" }
    }

    private fun useCase() = DeleteRecordsUseCase(
        recordRepository = records,
        collectionRepository = collections,
        cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
            maintenanceRepository = maintenance,
            assetRepository = assets,
            fileStore = files,
        ),
        scheduler = scheduler,
    )

    /** 预置一条"用户看得到"的记录。归档的也要能进去 —— `getById` 不过滤 archived。 */
    private fun givenRecord(
        id: String,
        type: RecordType = RecordType.TEXT_NOTE,
        archivedAt: Long? = null,
    ): Record = testRecord(id = id, type = type, archivedAt = archivedAt).also { record ->
        records.byId[id] = record
        records.stored.value = records.stored.value + record
    }

    // ------------------------------------------------------------ 顺序

    @Test
    fun Delete_marksDeletedBeforePurge() = runBlocking {
        givenRecord("r1")

        useCase()(listOf("r1"), now = 10L)

        assertEquals(listOf("mark:r1", "purge:r1"), events)
    }

    @Test
    fun Delete_cancelsJobsBeforePurge() = runBlocking {
        givenRecord("img", type = RecordType.IMAGE)
        val order = mutableListOf<String>()
        scheduler.onCancel = { order += "cancel:${it.first}" }
        maintenance.onPurge = { order += "purge:$it" }

        useCase()(listOf("img"), now = 10L)

        assertEquals("清理必须是最后一步", "purge:img", order.last())
        assertTrue(
            "取消任务必须全部发生在清理之前，实际顺序=$order",
            order.dropLast(1).all { it == "cancel:img" },
        )
    }

    // ------------------------------------------------------------ 每次删除都要取消该类型的全部任务

    @Test
    fun Delete_cancelsEveryJobTypeOfThatRecord() = runBlocking {
        givenRecord("img", type = RecordType.IMAGE)

        useCase()(listOf("img"), now = 10L)

        assertEquals(
            setOf(
                "img" to JobType.OCR,
                "img" to JobType.SUMMARIZE,
                "img" to JobType.EMBED,
            ),
            scheduler.cancelled.toSet(),
        )
    }

    @Test
    fun Delete_voiceNote_cancelsTranscriptionToo() = runBlocking {
        givenRecord("voice", type = RecordType.VOICE_NOTE)

        useCase()(listOf("voice"), now = 10L)

        assertTrue(
            "转写是云端任务，记录被删后必须一起取消（否则音频还会被上传）",
            JobType.TRANSCRIPTION in scheduler.cancelled.map { it.second },
        )
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun Delete_emptyIds_doesNotTouchAnything() = runBlocking {
        val report = useCase()(listOf("  ", ""), now = 10L)

        assertEquals(0, report.requested)
        assertEquals(0, report.deleted)
        assertTrue("空列表不该打数据库", events.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun Delete_duplicateIds_countedOnce() = runBlocking {
        givenRecord("r1")

        val report = useCase()(listOf("r1", "r1", "r1"), now = 10L)

        assertEquals(1, report.requested)
        assertEquals(1, report.deleted)
        assertEquals(listOf("mark:r1", "purge:r1"), events)
    }

    @Test
    fun Delete_archivedRecord_isDeletable() = runBlocking {
        givenRecord("archived", archivedAt = 5L)

        val report = useCase()(listOf("archived"), now = 10L)

        assertEquals(
            "归档箱里也要能删；用 getByIds（它已排除归档）就会一条都取不到",
            1,
            report.deleted,
        )
    }

    // ------------------------------------------------------------ 文件

    @Test
    fun Delete_removesAssetFilesBeforeRows() = runBlocking {
        val asset = testAsset(id = "a1", recordId = "r1")
        givenRecord("r1")
        assets.save(asset)
        files.presentPaths += asset.localPath

        val report = useCase()(listOf("r1"), now = 10L)

        assertEquals(1, report.deleted)
        assertFalse("附件文件应已删除", asset.localPath in files.presentPaths)
        // "purgeRecord 连 assets 行一起删" 是 MaintenanceDao 事务的职责，
        // 由 MaintenanceRepositoryImplTest 覆盖；这里只钉本用例的编排顺序。
        assertEquals(
            "先文件后行",
            listOf("mark:r1", "file:${asset.localPath}", "purge:r1"),
            events,
        )
    }

    @Test
    fun Delete_whenFileIsInUse_reportsIncompleteAndKeepsRow() = runBlocking {
        val asset = testAsset(id = "a1", recordId = "r1")
        givenRecord("r1")
        assets.save(asset)
        files.presentPaths += asset.localPath
        files.undeletable += asset.localPath

        val report = useCase()(listOf("r1"), now = 10L)

        assertEquals(1, report.requested)
        assertEquals("文件没删掉就不算删干净", 0, report.deleted)
        assertEquals(1, report.incomplete)
        assertTrue(report.hasIncomplete)
        assertTrue(
            "必须保留行当作下次重试的线索，否则文件永远找不到",
            events.none { it == "purge:r1" },
        )
        assertTrue(asset.localPath in files.presentPaths)
    }

    @Test
    fun Delete_partialSuccess_reportsBothSides() = runBlocking {
        val okAsset = testAsset(id = "a1", recordId = "r1")
        val stuckAsset = testAsset(id = "a2", recordId = "r2")
        givenRecord("r1")
        givenRecord("r2")
        assets.save(okAsset)
        assets.save(stuckAsset)
        files.presentPaths += okAsset.localPath
        files.presentPaths += stuckAsset.localPath
        files.undeletable += stuckAsset.localPath

        val report = useCase()(listOf("r1", "r2"), now = 10L)

        assertEquals(2, report.requested)
        assertEquals(1, report.deleted)
        assertEquals(1, report.incomplete)
    }

    @Test
    fun Delete_refreshesMembershipsBeforePhysicalCleanup() = runBlocking {
        givenRecord(id = "r1")
        givenRecord(id = "r2")

        useCase().invoke(ids = listOf("r1", "r2"), now = 10L)

        // 物理清理会级联删掉成员关系；刷新必须在清理前完成，否则合集再也找不到这条成员
        assertEquals(listOf("r1", "r2"), collections.refreshed)
    }
}
