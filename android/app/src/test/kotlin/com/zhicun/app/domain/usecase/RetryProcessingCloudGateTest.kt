package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 重试侧的云端闸门（M14）。
 *
 * 关键判断：**"用户点了重试"不等于"用户同意上传"**。
 * 开关关着时点重试，语义是"再试一次能在本地做完的事"，不是"绕过我刚关掉的开关"。
 *
 * 另一个约束是诚实：一个任务都排不进去时要返回 false 且**不动状态**，
 * 而不是把记录刷成"处理中"然后再也没有下文。
 */
class RetryProcessingCloudGateTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var recordRepository: FakeRecordRepository

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
    }

    private fun useCase(cloudAiEnabled: Boolean): RetryProcessingUseCase =
        RetryProcessingUseCase(
            recordRepository = recordRepository,
            jobRepository = jobRepository,
            scheduler = scheduler,
            aiSettings = FakeAiSettings(cloudAiEnabled = cloudAiEnabled),
            gatewaySettings = FakeAiGatewaySettings(),
        )

    @Test
    fun CloudOff_textNote_returnsFalseAndKeepsStatus() = runTest {
        val record = testRecord(
            id = "r-text",
            type = RecordType.TEXT_NOTE,
            processingStatus = ProcessingStatus.FAILED,
        )
        recordRepository = FakeRecordRepository(listOf(record))

        val enqueuedAny = useCase(cloudAiEnabled = false).invoke("r-text", now = 2_000L)

        assertFalse("云端任务全被挡下时不该谎报'已重排'", enqueuedAny)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(
            "状态必须原样保留，界面继续显示失败，用户能看出什么都没发生",
            ProcessingStatus.FAILED,
            recordRepository.byId.getValue("r-text").processingStatus,
        )
    }

    @Test
    fun CloudOff_imageRecord_stillRunsLocalOcr() = runTest {
        val record = testRecord(id = "r-img", type = RecordType.IMAGE)
        recordRepository = FakeRecordRepository(listOf(record))

        val enqueuedAny = useCase(cloudAiEnabled = false).invoke("r-img", now = 2_000L)

        assertTrue(enqueuedAny)
        assertEquals(listOf("r-img" to JobType.OCR), scheduler.enqueued)
        assertEquals(
            "只剩本地任务时，状态要如实标成'仅本地'",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-img").processingStatus,
        )
    }

    @Test
    fun CloudOn_textNote_setsPending() = runTest {
        val record = testRecord(
            id = "r-text",
            type = RecordType.TEXT_NOTE,
            processingStatus = ProcessingStatus.FAILED,
        )
        recordRepository = FakeRecordRepository(listOf(record))

        val enqueuedAny = useCase(cloudAiEnabled = true).invoke("r-text", now = 2_000L)

        assertTrue(enqueuedAny)
        assertEquals(
            listOf("r-text" to JobType.SUMMARIZE, "r-text" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertEquals(
            ProcessingStatus.PENDING,
            recordRepository.byId.getValue("r-text").processingStatus,
        )
    }

    /** 敏感记录同样不能在"重试"里绕过闸门（与调度侧同一条判据）。 */
    @Test
    fun Sensitive_imageRecord_skipsCloudJobs() = runTest {
        val record = testRecord(
            id = "r-img",
            type = RecordType.IMAGE,
            sensitivityStatus = SensitivityStatus.SUSPECTED,
        )
        recordRepository = FakeRecordRepository(listOf(record))

        useCase(cloudAiEnabled = true).invoke("r-img", now = 2_000L)

        assertEquals(listOf("r-img" to JobType.OCR), scheduler.enqueued)
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue("r-img").processingStatus,
        )
    }

    @Test
    fun UnknownRecord_returnsFalse() = runTest {
        recordRepository = FakeRecordRepository()

        assertFalse(useCase(cloudAiEnabled = true).invoke("missing", now = 2_000L))
        assertTrue(scheduler.enqueued.isEmpty())
    }
}
