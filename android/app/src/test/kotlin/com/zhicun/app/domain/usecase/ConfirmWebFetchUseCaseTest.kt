package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
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
 * 用户单独允许"抓取这个网页"（2026-10-23 复核 F-5）。
 *
 * 这个用例存在的理由只有一条：它与"允许上传云端"是**两次独立授权**。
 * 因此这里刻意不注入任何设置（`AiSettings` / `AiGatewaySettings`）——
 * 云端开关关着、网关没配置时，它照样要能成功。
 */
class ConfirmWebFetchUseCaseTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var useCase: ConfirmWebFetchUseCase

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        recordRepository = FakeRecordRepository()
        useCase = ConfirmWebFetchUseCase(
            recordRepository = recordRepository,
            jobRepository = jobRepository,
            scheduler = scheduler,
        )
    }

    private fun seed(record: Record) {
        recordRepository = FakeRecordRepository(listOf(record))
        useCase = ConfirmWebFetchUseCase(
            recordRepository = recordRepository,
            jobRepository = jobRepository,
            scheduler = scheduler,
        )
    }

    private fun sensitiveWebLink(
        id: String = "r1",
        recordRevision: Int = 1,
        webFetchConfirmedRevision: Int? = null,
    ) = testRecord(
        id = id,
        type = RecordType.WEB_LINK,
        rawText = null,
        sourceUrl = "https://example.com/a",
        sensitivityStatus = SensitivityStatus.SUSPECTED,
        recordRevision = recordRevision,
        webFetchConfirmedRevision = webFetchConfirmedRevision,
    )

    /**
     * 核心用例：云端通路完全不可用时也能允许抓取。
     *
     * 这个用例**没有** `AiSettings` / `AiGatewaySettings` 依赖 —— 结构上就证明了
     * "抓取与云端开关无关"。整改前这条路要求先确认云端上传，而那个确认以通路可用为前提，
     * 于是默认无网关的安装包里敏感网页永远抓不下来。
     */
    @Test
    fun Invoke_sensitiveWebLink_writesRevisionAndQueuesFetch() = runTest {
        seed(sensitiveWebLink())

        val result = useCase("r1", now = 5_000L)

        assertTrue("应当允许抓取", result)
        val record = recordRepository.getById("r1")!!
        assertEquals("授权要绑定当前内容版本", 1, record.webFetchConfirmedRevision)
        assertEquals("只允许抓取不代表允许上传", null, record.cloudConfirmedRevision)
        assertEquals(
            "必须重新排入网页抓取",
            listOf("r1" to JobType.WEBPAGE_EXTRACT),
            scheduler.enqueued,
        )
        assertEquals(
            JobStatus.QUEUED,
            jobRepository.getByRecordAndType("r1", JobType.WEBPAGE_EXTRACT)!!.status,
        )
    }

    /**
     * 只复活网页抓取，不排任何云端任务。
     *
     * 摘要/向量此刻仍被敏感闸挡着，顺手排上去只会被 Worker 再取消一次，
     * 在任务表里留一串无意义的 CANCELLED。
     */
    @Test
    fun Invoke_doesNotQueueAnyCloudJob() = runTest {
        seed(sensitiveWebLink())

        useCase("r1")

        assertFalse("摘要不能被顺带排入", JobType.SUMMARIZE in scheduler.enqueued.map { it.second })
        assertFalse("向量不能被顺带排入", JobType.EMBED in scheduler.enqueued.map { it.second })
        assertNull(jobRepository.getByRecordAndType("r1", JobType.SUMMARIZE))
        assertNull(jobRepository.getByRecordAndType("r1", JobType.EMBED))
    }

    /** 之前被拦下置为 CANCELLED 的任务要复位成"待执行"，并重新给满重试次数。 */
    @Test
    fun Invoke_cancelledJob_isResetAndRequeued() = runTest {
        seed(sensitiveWebLink())
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = "r1",
                jobType = JobType.WEBPAGE_EXTRACT,
                status = JobStatus.CANCELLED,
                attempts = 2,
                waitRounds = 3,
                errorCategory = "疑似敏感内容，待用户确认后再上传",
            ),
        )

        useCase("r1")

        val job = jobRepository.getByRecordAndType("r1", JobType.WEBPAGE_EXTRACT)!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals("重新给满次数", 0, job.attempts)
        assertEquals("等待轮次要一起清零", 0, job.waitRounds)
        assertNull("旧的失败原因要清掉", job.errorCategory)
        assertEquals(listOf("r1" to JobType.WEBPAGE_EXTRACT), scheduler.enqueued)
    }

    /** 内容被改过（版本前进）后，旧授权不算数：本次确认要写成当前版本。 */
    @Test
    fun Invoke_afterEdit_writesCurrentRevision() = runTest {
        seed(sensitiveWebLink(recordRevision = 3, webFetchConfirmedRevision = 1))

        useCase("r1")

        assertEquals(3, recordRepository.getById("r1")!!.webFetchConfirmedRevision)
    }

    /** 非网页记录没有"抓取"这回事，不该留下一个没有意义的授权标记。 */
    @Test
    fun Invoke_textNote_isRejected() = runTest {
        seed(
            testRecord(
                id = "r1",
                type = RecordType.TEXT_NOTE,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )

        assertFalse(useCase("r1"))
        assertNull(recordRepository.getById("r1")!!.webFetchConfirmedRevision)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    /** 不敏感的网页本来就不受这道闸约束：没有"放行"可做，也不要写标记。 */
    @Test
    fun Invoke_cleanWebLink_isRejected() = runTest {
        seed(
            testRecord(
                id = "r1",
                type = RecordType.WEB_LINK,
                rawText = null,
                sourceUrl = "https://example.com/a",
                sensitivityStatus = SensitivityStatus.CLEAR,
            ),
        )

        assertFalse(useCase("r1"))
        assertNull(recordRepository.getById("r1")!!.webFetchConfirmedRevision)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    /** 记录不存在：直接返回 false，不抛异常（详情页在删除竞态里仍会调它）。 */
    @Test
    fun Invoke_unknownRecord_returnsFalse() = runTest {
        seed(sensitiveWebLink())

        assertFalse(useCase("missing"))
        assertTrue(scheduler.enqueued.isEmpty())
    }
}
