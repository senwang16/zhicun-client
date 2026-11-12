package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 任务调度用例（M6 建立，M11 加敏感门禁与摘要任务，M12 加向量任务）。
 *
 * 守两条不变量：
 * 1. 同一 `recordId + jobType` 只排一个任务（幂等）；
 * 2. **疑似敏感记录不排任何云端任务**（PRD §6.4：不上传，等用户确认）。
 */
class ScheduleProcessingUseCaseTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var useCase: ScheduleProcessingUseCase

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        recordRepository = FakeRecordRepository()
        useCase = ScheduleProcessingUseCase(
            jobRepository,
            scheduler,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
    }

    @Test
    fun Invoke_imageRecord_enqueuesOcrThenCloudJobs() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)

        useCase(record)

        // 本地 OCR 先跑，两条云端任务（摘要、向量）随后 —— 它们与 OCR 并行入队，
        // 靠 Worker 自查上游状态来"等正文"
        assertEquals(
            listOf(
                "r-img" to JobType.OCR,
                "r-img" to JobType.SUMMARIZE,
                "r-img" to JobType.EMBED,
            ),
            scheduler.enqueued,
        )
        val job = jobRepository.getByRecordAndType("r-img", JobType.OCR)!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(0, job.attempts)
        assertEquals(ProcessingJob.DEFAULT_MAX_ATTEMPTS, job.maxAttempts)
    }

    @Test
    fun Invoke_webLinkRecord_enqueuesExtractThenCloudJobs() = runTest {
        val record = testRecord(id = "r-link", type = RecordType.WEB_LINK)

        useCase(record)

        assertEquals(
            listOf(
                "r-link" to JobType.WEBPAGE_EXTRACT,
                "r-link" to JobType.SUMMARIZE,
                "r-link" to JobType.EMBED,
            ),
            scheduler.enqueued,
        )
    }

    @Test
    fun Invoke_textRecord_enqueuesBothCloudJobs() = runTest {
        // 纯文本没有本地预处理，直接进两条云端任务
        useCase(testRecord(id = "r-text", type = RecordType.TEXT_NOTE))

        assertEquals(
            listOf("r-text" to JobType.SUMMARIZE, "r-text" to JobType.EMBED),
            scheduler.enqueued,
        )
    }

    @Test
    fun Invoke_twice_doesNotCreateDuplicateJob() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)

        useCase(record)
        // 第二次时任务还在 QUEUED，会补投一次调度（防止调度丢失），但**不会**再插一条任务
        useCase(record)

        assertEquals(3, jobRepository.jobs.size)
        assertEquals(6, scheduler.enqueued.size)
    }

    @Test
    fun Invoke_afterJobDone_doesNotReschedule() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)
        useCase(record)
        listOf(JobType.OCR, JobType.SUMMARIZE, JobType.EMBED).forEach { jobType ->
            val job = jobRepository.getByRecordAndType("r-img", jobType)!!
            jobRepository.updateStatus(job.copy(status = JobStatus.DONE))
        }
        scheduler.enqueued.clear()

        useCase(record)

        assertTrue("已完成的任务不得重投", scheduler.enqueued.isEmpty())
        assertEquals(3, jobRepository.jobs.size)
    }

    @Test
    fun Invoke_afterJobFailed_doesNotAutoRetry() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)
        useCase(record)
        val job = jobRepository.getByRecordAndType("r-img", JobType.OCR)!!
        jobRepository.updateStatus(job.copy(status = JobStatus.FAILED))
        scheduler.enqueued.clear()

        useCase(record)

        // SUMMARIZE / EMBED 仍是 QUEUED，会各补投一次；OCR 已 FAILED 不再投
        assertEquals(
            listOf("r-img" to JobType.SUMMARIZE, "r-img" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertTrue(jobRepository.jobs.values.none { it.jobType == JobType.SUMMARIZE && it.status == JobStatus.FAILED })
    }

    @Test
    fun Invoke_suspectedSensitive_skipsCloudJobsButKeepsLocalOnes() = runTest {
        val record = testRecord(
            id = "r-img",
            type = RecordType.IMAGE,
            sensitivityStatus = SensitivityStatus.SUSPECTED,
        )
        // 记录必须真的在仓储里，否则断言不到 LOCAL_ONLY 的写入（假实现按 id 更新）
        recordRepository = FakeRecordRepository(listOf(record))
        useCase = ScheduleProcessingUseCase(
            jobRepository,
            scheduler,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )

        useCase(record)

        assertEquals("只该排本地 OCR，云端任务一律不排", listOf("r-img" to JobType.OCR), scheduler.enqueued)
        assertTrue(
            "疑似敏感记录不该有任何云端任务行（摘要 / 向量）",
            jobRepository.jobs.values.none { it.jobType in com.zhicun.app.domain.worker.ProcessingJobTargets.CLOUD_JOBS },
        )
        assertEquals(
            "记录应标记为仅本地处理，而不是失败",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId["r-img"]!!.processingStatus,
        )
    }

    @Test
    fun Invoke_confirmedSensitive_alsoSkipsCloudJobs() = runTest {
        val record = testRecord(
            id = "r-text",
            type = RecordType.TEXT_NOTE,
            sensitivityStatus = SensitivityStatus.CONFIRMED_SENSITIVE,
        )

        useCase(record)

        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(jobRepository.jobs.isEmpty())
    }

    @Test
    fun Invoke_clearRecord_queuesCloudJobs() = runTest {
        val record = testRecord(
            id = "r-text",
            type = RecordType.TEXT_NOTE,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )

        useCase(record)

        assertEquals(
            listOf("r-text" to JobType.SUMMARIZE, "r-text" to JobType.EMBED),
            scheduler.enqueued,
        )
    }
}
