package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 云端开关重开后的补投（2026-10-23 审查 R-3）。
 *
 * 整改前"关掉开关 → 存记录 → 再打开开关"是一条单向门：记录停在 `LOCAL_ONLY`，
 * 既没有云端任务，界面上也没有重算入口。这里钉住四条边界：
 * 真的补投、通路不通就不投、敏感未确认不代办、已完成的不重跑。
 */
class ResumeCloudJobsUseCaseTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var derivedContentRepository: FakeDerivedContentRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var gatewaySettings: FakeAiGatewaySettings

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        derivedContentRepository = FakeDerivedContentRepository()
        assetRepository = FakeAssetRepository()
        aiSettings = FakeAiSettings(cloudAiEnabled = true)
        gatewaySettings = FakeAiGatewaySettings()
    }

    /**
     * 建用例并把聚合接到假任务表上。
     *
     * 不接的话 `recomputeProcessingStatus` 会读到"没有任务" → 状态又落回 `LOCAL_ONLY`，
     * 测试看着像生产代码没回写（M13 踩过的两路视图坑）。
     */
    private fun useCase(records: FakeRecordRepository) = ResumeCloudJobsUseCase(
        recordRepository = records,
        collectionRepository = FakeCollectionRepository(),
        jobRepository = jobRepository,
        derivedContentRepository = derivedContentRepository,
        assetRepository = assetRepository,
        scheduler = scheduler,
        aiSettings = aiSettings,
        gatewaySettings = gatewaySettings,
    ).also {
        records.jobStatusProvider = { id ->
            jobRepository.jobs.values.filter { job -> job.recordId == id }.map { job -> job.status }
        }
    }

    @Test
    fun Invoke_localOnlyRecord_queuesCloudJobsAndBackToPending() = runTest {
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", rawText = "一条笔记", processingStatus = ProcessingStatus.LOCAL_ONLY)),
        )

        val resumed = useCase(records)(now = 1_000L)

        assertEquals(1, resumed)
        assertEquals(
            listOf("r1" to JobType.SUMMARIZE, "r1" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertEquals(
            "补投后由任务集合聚合出 PENDING，而不是硬写一个状态",
            ProcessingStatus.PENDING,
            records.byId["r1"]!!.processingStatus,
        )
    }

    @Test
    fun Invoke_cloudStillDisabled_resumesNothing() = runTest {
        aiSettings.cloudEnabled = false
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", processingStatus = ProcessingStatus.LOCAL_ONLY)),
        )

        val resumed = useCase(records)(now = 1_000L)

        assertEquals(0, resumed)
        assertTrue("通路没开就不该排任何任务", scheduler.enqueued.isEmpty())
        assertEquals(ProcessingStatus.LOCAL_ONLY, records.byId["r1"]!!.processingStatus)
    }

    @Test
    fun Invoke_gatewayNotConfigured_resumesNothing() = runTest {
        gatewaySettings.baseUrl = null
        gatewaySettings.token = null
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", processingStatus = ProcessingStatus.LOCAL_ONLY)),
        )

        assertEquals(0, useCase(records)(now = 1_000L))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    /** 敏感未确认的记录不能靠一个全局开关代办：必须走详情页逐条确认（PRD §6.4）。 */
    @Test
    fun Invoke_suspectedUnconfirmed_isLeftToPerRecordConfirmation() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    processingStatus = ProcessingStatus.LOCAL_ONLY,
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                ),
            ),
        )

        assertEquals(0, useCase(records)(now = 1_000L))
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(ProcessingStatus.LOCAL_ONLY, records.byId["r1"]!!.processingStatus)
    }

    /** 已经成功的那一半不重跑：用户要的是摘要，不是把向量再算一遍。 */
    @Test
    fun Invoke_alreadyDoneCloudJob_isNotRerun() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-embed",
                recordId = "r1",
                jobType = JobType.EMBED,
                status = JobStatus.DONE,
                attempts = 1,
            ),
        )
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", processingStatus = ProcessingStatus.LOCAL_ONLY)),
        )

        assertEquals(1, useCase(records)(now = 1_000L))
        assertEquals(listOf("r1" to JobType.SUMMARIZE), scheduler.enqueued)
        assertEquals(JobStatus.DONE, jobRepository.getByRecordAndType("r1", JobType.EMBED)!!.status)
    }

    /** 用户手动把开关拨回来，等于重新给一次完整的等待窗口（2026-10-23 复核 F-2）。 */    @Test
    fun Invoke_resetsWaitRounds() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-s",
                recordId = "r1",
                jobType = JobType.SUMMARIZE,
                status = JobStatus.FAILED,
                attempts = 3,
                waitRounds = 5,
                errorCategory = "等待上游产出正文超时",
            ),
        )
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", processingStatus = ProcessingStatus.LOCAL_ONLY)),
        )

        useCase(records)(now = 1_000L)

        assertEquals(0, jobRepository.getByRecordAndType("r1", JobType.SUMMARIZE)!!.waitRounds)
    }

    /**
     * 确认过版本号、但确认范围不全的记录不在这里补投（2026-10-23 复核 F-9）。
     *
     * "确认过原文、之后才落地转写"就是这种形态：只看版本号会把它排上去，
     * 然后 Worker 立刻按范围判据取消，白排一轮、状态还要抖一次。
     * 正确入口是详情页横幅（用户看过新内容再点确认）。
     */
    @Test
    fun Invoke_confirmedButScopeIncomplete_isNotResumed() = runTest {
        derivedContentRepository.stored += testDerived(
            id = "r1:TRANSCRIPTION",
            recordId = "r1",
            contentType = DerivedType.TRANSCRIPTION,
            content = "录音里的内容",
        )
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.VOICE_NOTE,
                    sensitivityStatus = SensitivityStatus.SUSPECTED,
                    processingStatus = ProcessingStatus.LOCAL_ONLY,
                    recordRevision = 1,
                    cloudConfirmedRevision = 1,
                    cloudConfirmedScope = setOf(ContentPart.RAW_TEXT),
                ),
            ),
        )

        assertEquals(0, useCase(records)(now = 1_000L))
        assertTrue("范围不全不该被补投：${scheduler.enqueued}", scheduler.enqueued.isEmpty())
    }
}
