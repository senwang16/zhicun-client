package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 创建合集用例（M20）。
 *
 * 重点守三条：
 * 1. 成员校验（数量、存在、已删除、嵌套合集）—— 界面虽然也会禁用，但多选快照可能过期；
 * 2. 成员按 `createdAt` 排序，合集自身字段按约定初始化；
 * 3. 敏感状态取"未删除成员并集 + 合集说明扫描"，否则闸门会用错的徽标。
 */
class CreateCollectionUseCaseTest {

    private lateinit var records: FakeRecordRepository
    private lateinit var collections: FakeCollectionRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var jobs: FakeProcessingJobRepository
    private lateinit var diagnosticLog: FakeDiagnosticLog
    private lateinit var useCase: CreateCollectionUseCase

    @Before
    fun setUp() {
        records = FakeRecordRepository()
        collections = FakeCollectionRepository()
        scheduler = FakeProcessingScheduler()
        jobs = FakeProcessingJobRepository()
        diagnosticLog = FakeDiagnosticLog()
        useCase = CreateCollectionUseCase(
            recordRepository = records,
            collectionRepository = collections,
            scheduleProcessing = ScheduleProcessingUseCase(
                jobs,
                scheduler,
                records,
                FakeAiSettings(),
                FakeAiGatewaySettings(),
            ),
            sensitiveDetector = SensitiveContentDetector(),
            diagnosticLog = diagnosticLog,
        )
    }

    private fun seed(id: String, createdAt: Long, sensitivity: SensitivityStatus = SensitivityStatus.CLEAR) =
        testRecord(
            id = id,
            createdAt = createdAt,
            updatedAt = createdAt,
            sensitivityStatus = sensitivity,
        ).also { records.byId[id] = it }

    @Test
    fun Invoke_fewerThanTwoDistinctIds_rejected() = runTest {
        seed("a", createdAt = 1_000L)

        val result = useCase(listOf("a", "a", " "))

        assertEquals(
            CreateCollectionResult.Rejected(CreateCollectionResult.Reason.TOO_FEW),
            result,
        )
        assertTrue(collections.creations.isEmpty())
    }

    @Test
    fun Invoke_missingRecord_rejected() = runTest {
        seed("a", createdAt = 1_000L)

        val result = useCase(listOf("a", "gone"))

        assertEquals(
            CreateCollectionResult.Rejected(CreateCollectionResult.Reason.MISSING_RECORD),
            result,
        )
        assertTrue(collections.creations.isEmpty())
    }

    @Test
    fun Invoke_deletedMember_rejected() = runTest {
        seed("a", createdAt = 1_000L)
        records.byId["b"] = testRecord(
            id = "b",
            createdAt = 2_000L,
            isDeleted = true,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )

        val result = useCase(listOf("a", "b"))

        assertEquals(
            CreateCollectionResult.Rejected(CreateCollectionResult.Reason.DELETED_MEMBER),
            result,
        )
        assertTrue(collections.creations.isEmpty())
    }

    @Test
    fun Invoke_collectionAsMember_rejected() = runTest {
        seed("a", createdAt = 1_000L)
        records.byId["old-collection"] = testRecord(
            id = "old-collection",
            type = RecordType.COLLECTION,
            createdAt = 2_000L,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )

        val result = useCase(listOf("a", "old-collection"))

        assertEquals(
            CreateCollectionResult.Rejected(CreateCollectionResult.Reason.NESTED_COLLECTION),
            result,
        )
        assertTrue(collections.creations.isEmpty())
    }

    @Test
    fun Invoke_validMembers_createsCollectionWithSortedMembersAndUnionSensitivity() = runTest {
        seed("late", createdAt = 2_000L, sensitivity = SensitivityStatus.SUSPECTED)
        seed("early", createdAt = 1_000L, sensitivity = SensitivityStatus.CLEAR)

        val result = useCase(listOf("late", "early"), description = "  两个想法的合并  ", now = 9_000L)

        val created = result as CreateCollectionResult.Created
        val creation = collections.creations.single()
        assertEquals(created.collectionId, creation.collection.id)
        assertEquals(listOf("early", "late"), creation.memberRecordIds)
        assertEquals(9_000L, creation.now)

        val collection = creation.collection
        assertEquals(RecordType.COLLECTION, collection.type)
        assertEquals(CaptureSource.MANUAL, collection.captureSource)
        assertEquals("两个想法的合并", collection.rawText)
        assertEquals(LocalStatus.SAVED, collection.localStatus)
        assertEquals(ProcessingStatus.PENDING, collection.processingStatus)
        assertEquals(SensitivityStatus.SUSPECTED, collection.sensitivityStatus)
        assertEquals(1, collection.recordRevision)
        assertFalse(collection.isDeleted)
    }

    @Test
    fun Invoke_sensitiveDescription_marksCollectionSuspectedAndBlocksCloudJobs() = runTest {
        seed("a", createdAt = 1_000L)
        seed("b", createdAt = 2_000L)

        useCase(listOf("a", "b"), description = "联系我 13800138000")

        val collection = collections.creations.single().collection
        assertEquals(SensitivityStatus.SUSPECTED, collection.sensitivityStatus)
        // 疑似敏感 → 不排任何云端任务（合集只有 SUMMARY / EMBED）
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun Invoke_cleanCollection_enqueuesSummaryAndEmbed() = runTest {
        seed("a", createdAt = 1_000L)
        seed("b", createdAt = 2_000L)

        val result = useCase(listOf("a", "b")) as CreateCollectionResult.Created

        assertEquals(
            setOf(result.collectionId to JobType.SUMMARIZE, result.collectionId to JobType.EMBED),
            scheduler.enqueued.toSet(),
        )
        assertEquals(2, scheduler.enqueued.size)
    }

    @Test
    fun Invoke_scheduleThrows_stillReportsCreatedAndLogs() = runTest {
        seed("a", createdAt = 1_000L)
        seed("b", createdAt = 2_000L)
        scheduler.failOnEnqueue = true

        val result = useCase(listOf("a", "b"))

        assertTrue(result is CreateCollectionResult.Created)
        assertEquals(1, collections.creations.size)
        // 调度失败不能让用户以为成员关系没写进去，但必须留痕
        assertEquals("CreateCollection", diagnosticLog.entries.single().tag)
    }

    @Test
    fun Invoke_blankDescription_storedAsNull() = runTest {
        seed("a", createdAt = 1_000L)
        seed("b", createdAt = 2_000L)

        useCase(listOf("a", "b"), description = "   ")

        assertEquals(null, collections.creations.single().collection.rawText)
    }
}
