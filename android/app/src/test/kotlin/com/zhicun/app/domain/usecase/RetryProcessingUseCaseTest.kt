package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "重新识别"用例测试（M7）。
 *
 * 这个用例存在的意义是把"重试耗尽后的人工重跑"与"自动补投"分开：
 * 前者会**清零次数**并清空错误，后者绝不会碰 FAILED 任务（否则就成了无限重试）。
 */
class RetryProcessingUseCaseTest {

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var useCase: RetryProcessingUseCase

    private val recordId = "rec-1"

    @Before
    fun setUp() {
        recordRepository = FakeRecordRepository(
            listOf(testRecord(id = recordId, type = RecordType.IMAGE)),
        )
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        useCase = RetryProcessingUseCase(
            recordRepository,
            jobRepository,
            scheduler,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
    }

    @Test
    fun Retry_afterFailedJob_resetsAttemptsAndRequeues() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = recordId,
                jobType = JobType.OCR,
                status = JobStatus.FAILED,
                attempts = 3,
                waitRounds = 5,
                nextRetryAt = 999L,
                errorCategory = "重试次数已用尽",
                startedAt = 100L,
                finishedAt = 200L,
            ),
        )

        val scheduled = useCase(recordId, now = 1_000L)

        assertTrue(scheduled)
        val job = jobRepository.getByRecordAndType(recordId, JobType.OCR)!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals("人工重试要重新给满 3 次机会", 0, job.attempts)
        assertEquals("等待轮次也要清零，否则第一轮就再次超限（复核 F-2）", 0, job.waitRounds)
        assertNull(job.errorCategory)
        assertNull(job.nextRetryAt)
        assertNull(job.startedAt)
        assertNull(job.finishedAt)
        // M11/M12 起图片的完整链路是「识别 → 摘要 → 向量」，重试要把下游一起重排，
        // 否则只重跑 OCR 的话摘要与向量会一直停在上一轮的旧结果上。
        assertEquals(
            listOf(
                recordId to JobType.OCR,
                recordId to JobType.SUMMARIZE,
                recordId to JobType.EMBED,
            ),
            scheduler.enqueued,
        )
        assertEquals(
            ProcessingStatus.PENDING,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        // 2026-11-05：必须走 reenqueue（REPLACE）。用 enqueue（KEEP）时，正卡在
        // WorkManager 指数退避里的同名任务会挡住这次重试，界面只能干等旧任务到点。
        assertEquals(
            listOf(
                recordId to JobType.OCR,
                recordId to JobType.SUMMARIZE,
                recordId to JobType.EMBED,
            ),
            scheduler.reenqueued,
        )
    }

    @Test
    fun Retry_withoutExistingJob_insertsAndEnqueues() = runTest {
        val scheduled = useCase(recordId)

        assertTrue(scheduled)
        val job = jobRepository.getByRecordAndType(recordId, JobType.OCR)!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(0, job.attempts)
        assertEquals(
            listOf(
                recordId to JobType.OCR,
                recordId to JobType.SUMMARIZE,
                recordId to JobType.EMBED,
            ),
            scheduler.enqueued,
        )
    }

    @Test
    fun Retry_missingRecord_doesNothing() = runTest {
        val scheduled = useCase("rec-gone")

        assertFalse(scheduled)
        assertTrue("记录不存在就不该插任务", jobRepository.jobs.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun Retry_blankId_doesNothing() = runTest {
        assertFalse(useCase("  "))
        assertTrue(jobRepository.jobs.isEmpty())
    }

    @Test
    fun Retry_textNote_onlyRequeuesCloudJobs() = runTest {
        // M11 之前纯文本记录没有后台任务，"重试点了没反应"是当时的正确行为。
        // 现在文本要生成摘要与向量，语义反过来：重试必须把两条云端任务都重排，
        // 且**不能**带进 OCR / 网页提取这类本地任务（对文本记录毫无意义）。
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId)
            .copy(type = RecordType.TEXT_NOTE)

        val scheduled = useCase(recordId)

        assertTrue(scheduled)
        assertEquals(
            listOf(recordId to JobType.SUMMARIZE, recordId to JobType.EMBED),
            scheduler.enqueued,
        )
    }

    @Test
    fun Retry_onlySubset_requeuesJustThoseJobs() = runTest {
        // 2026-10-26 原文编辑：只该重算摘要与向量，不能顺手重新识别图片
        val scheduled = useCase(recordId, only = setOf(JobType.SUMMARIZE, JobType.EMBED))

        assertTrue(scheduled)
        assertEquals(
            listOf(recordId to JobType.SUMMARIZE, recordId to JobType.EMBED),
            scheduler.enqueued,
        )
        assertNull("OCR 不在本次范围内", jobRepository.getByRecordAndType(recordId, JobType.OCR))
        assertEquals(
            ProcessingStatus.PENDING,
            recordRepository.byId[recordId]!!.processingStatus,
        )
    }

    @Test
    fun Retry_onlySubsetWithNoRunnableJob_returnsFalse() = runTest {
        // 文本记录的局部重跑只有云端任务；云端关闭时一个都跑不了
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId)
            .copy(type = RecordType.TEXT_NOTE, processingStatus = ProcessingStatus.DONE)
        useCase = RetryProcessingUseCase(
            recordRepository,
            jobRepository,
            scheduler,
            FakeAiSettings(cloudAiEnabled = false),
            FakeAiGatewaySettings(),
        )

        val scheduled = useCase(recordId, only = setOf(JobType.SUMMARIZE, JobType.EMBED))

        assertFalse(scheduled)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(
            "一个任务都没排时不能改状态",
            ProcessingStatus.DONE,
            recordRepository.byId[recordId]!!.processingStatus,
        )
    }

    @Test
    fun EveryRecordType_hasAtLeastOneJob() {
        // `RetryProcessingUseCase` / `ScheduleProcessingUseCase` 里都有
        // "jobTypes.isEmpty() 就什么都不做" 的分支。M11 之后所有类型都至少有一条
        // 摘要任务，那条分支成了纯防御代码（测不到）。这里用契约断言把它钉住：
        // 将来谁把某个类型映射成空列表，必须在这里显式改一笔，而不是静默变成"点了没反应"。
        val empty = RecordType.entries.filter { ProcessingJobTargets.of(it).isEmpty() }

        assertTrue("这些记录类型没有后台任务，会退化成“点了没反应”：$empty", empty.isEmpty())
    }
}
