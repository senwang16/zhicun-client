package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ProcessingJobRepository 集成测试。
 *
 * 重点验证 ARCHITECTURE §6.4 的幂等性：同一 `recordId + jobType` 不能产生第二条任务。
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingJobRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ProcessingJobRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = ProcessingJobRepositoryImpl(database.processingJobDao())
        // 外键约束要求宿主记录存在
        database.recordDao().insert(
            com.zhicun.app.data.local.entity.RecordEntity(
                id = "rec-1",
                type = RecordType.IMAGE,
                captureSource = CaptureSource.GALLERY,
                rawText = null,
                sourceUrl = null,
                sourceTitle = null,
                sourceApp = null,
                createdAt = 1L,
                updatedAt = 1L,
                localStatus = LocalStatus.SAVED,
                processingStatus = ProcessingStatus.PENDING,
                sensitivityStatus = SensitivityStatus.UNKNOWN,
                recordRevision = 1,
                isDeleted = false,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun job(
        id: String = "job-1",
        recordId: String = "rec-1",
        jobType: JobType = JobType.OCR,
        status: JobStatus = JobStatus.QUEUED,
        attempts: Int = 0,
    ) = ProcessingJob(
        id = id,
        recordId = recordId,
        jobType = jobType,
        status = status,
        attempts = attempts,
    )

    @Test
    fun ProcessingJobRepository_insertThenGetByRecordAndType_returnsJob() = runBlocking {
        repository.insert(job())

        val found = repository.getByRecordAndType("rec-1", JobType.OCR)

        assertNotNull(found)
        assertEquals("job-1", found!!.id)
        assertEquals(JobStatus.QUEUED, found.status)
        assertEquals(3, found.maxAttempts)
    }

    @Test
    fun ProcessingJobRepository_getUnknownJob_returnsNull() = runBlocking {
        assertNull(repository.getByRecordAndType("rec-1", JobType.EMBED))
    }

    @Test
    fun ProcessingJobRepository_insertSameRecordAndType_throwsToEnforceIdempotency() = runBlocking {
        repository.insert(job(id = "job-1"))

        val failed = try {
            repository.insert(job(id = "job-2"))
            false
        } catch (e: Exception) {
            true
        }

        assertTrue("同一 recordId+jobType 二次插入必须被唯一索引拒绝", failed)
    }

    @Test
    fun ProcessingJobRepository_updateStatus_persistsRetryFields() = runBlocking {
        repository.insert(job())

        repository.updateStatus(
            job(status = JobStatus.FAILED, attempts = 2).copy(
                nextRetryAt = 9_000L,
                errorCategory = "NETWORK",
                startedAt = 7_000L,
                finishedAt = 8_000L,
            ),
        )

        val updated = repository.getByRecordAndType("rec-1", JobType.OCR)
        assertEquals(JobStatus.FAILED, updated?.status)
        assertEquals(2, updated?.attempts)
        assertEquals(9_000L, updated?.nextRetryAt)
        assertEquals("NETWORK", updated?.errorCategory)
        assertEquals(7_000L, updated?.startedAt)
        assertEquals(8_000L, updated?.finishedAt)
    }

    @Test
    fun ProcessingJobRepository_insertForMissingRecord_violatesForeignKey() = runBlocking {
        val failed = try {
            repository.insert(job(id = "job-orphan", recordId = "rec-missing"))
            false
        } catch (e: Exception) {
            true
        }

        assertTrue("存在外键约束时不允许悬空任务", failed)
    }

    /**
     * `wait_rounds` 必须真的落库（2026-11-05 审核 CN-01）。
     *
     * 这条只能用**真实 Room** 钉住：`FakeProcessingJobRepository` 是整对象覆盖，
     * SQL 里漏一列它照样绿，于是"等上游的轮次永远读回 0、上限形同虚设"这个 bug
     * 在全部单测里都看不见。执行器的等待路径走的是 `updateStatusIfRunning`，
     * 启动补投/重试复位走的是 `updateStatus`，两条都要覆盖。
     */
    @Test
    fun ProcessingJobRepository_updateStatus_persistsWaitRounds() = runBlocking {
        repository.insert(job())

        repository.updateStatus(job(status = JobStatus.QUEUED, attempts = 1).copy(waitRounds = 3))

        assertEquals(
            "漏写 wait_rounds 会让 MAX_WAIT_ROUNDS 永远读 0，任务无限重排",
            3,
            repository.getByRecordAndType("rec-1", JobType.OCR)?.waitRounds,
        )
    }

    @Test
    fun ProcessingJobRepository_updateStatusIfRunning_persistsWaitRounds() = runBlocking {
        repository.insert(job(status = JobStatus.RUNNING, attempts = 1))

        val applied = repository.updateStatusIfRunning(
            job(status = JobStatus.QUEUED, attempts = 1).copy(waitRounds = 2),
            expectedAttempts = 1,
        )

        assertTrue("CAS 应命中", applied)
        assertEquals(
            "CAS 回写同样必须带上 wait_rounds",
            2,
            repository.getByRecordAndType("rec-1", JobType.OCR)?.waitRounds,
        )
    }
}
