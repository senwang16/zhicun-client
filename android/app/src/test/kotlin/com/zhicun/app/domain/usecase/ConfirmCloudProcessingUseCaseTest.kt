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
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用户确认上传（M11）。
 *
 * 三条关键行为：只排云端任务（不连累 OCR）、确认绑定到当前内容版本、记录回到 PENDING。
 * 确认**不清除**敏感标记 —— 一次放行不等于把整条记录洗白（QODER P2-6）。
 * M12 起"云端任务"是两条（摘要 + 向量），因此这里的期望值也跟着变成两条。
 */
class ConfirmCloudProcessingUseCaseTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var derivedContentRepository: FakeDerivedContentRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var gatewaySettings: FakeAiGatewaySettings
    private lateinit var useCase: ConfirmCloudProcessingUseCase

    /** 2026-10-24：确认上传会顺带放行敏感网页的抓取，因此用例内部也依赖它。 */
    private fun confirmWebFetchUseCase() = ConfirmWebFetchUseCase(
        recordRepository = recordRepository,
        jobRepository = jobRepository,
        scheduler = scheduler,
    )

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        recordRepository = FakeRecordRepository()
        derivedContentRepository = FakeDerivedContentRepository()
        assetRepository = FakeAssetRepository()
        aiSettings = FakeAiSettings()
        gatewaySettings = FakeAiGatewaySettings()
        useCase = ConfirmCloudProcessingUseCase(
            recordRepository = recordRepository,
            collectionRepository = FakeCollectionRepository(),
            jobRepository = jobRepository,
            derivedContentRepository = derivedContentRepository,
            assetRepository = assetRepository,
            scheduler = scheduler,
            aiSettings = aiSettings,
            gatewaySettings = gatewaySettings,
            confirmWebFetch = confirmWebFetchUseCase(),
        )
    }

    private fun seed(record: com.zhicun.app.domain.model.Record) {
        recordRepository = FakeRecordRepository(listOf(record))
        useCase = ConfirmCloudProcessingUseCase(
            recordRepository = recordRepository,
            collectionRepository = FakeCollectionRepository(),
            jobRepository = jobRepository,
            derivedContentRepository = derivedContentRepository,
            assetRepository = assetRepository,
            scheduler = scheduler,
            aiSettings = aiSettings,
            gatewaySettings = gatewaySettings,
            confirmWebFetch = confirmWebFetchUseCase(),
        )
    }

    @Test
    fun Invoke_suspectedTextNote_bindsConfirmationAndQueuesCloudJobs() = runTest {
        val record = testRecord(
            id = "r1",
            sensitivityStatus = SensitivityStatus.SUSPECTED,
            processingStatus = ProcessingStatus.LOCAL_ONLY,
        )
        seed(record)

        val scheduled = useCase("r1", now = 1_000L)

        assertTrue(scheduled)
        assertEquals(
            listOf("r1" to JobType.SUMMARIZE, "r1" to JobType.EMBED),
            scheduler.enqueued,
        )
        val updated = recordRepository.byId["r1"]!!
        assertEquals(
            "确认只放行这一版内容，风险标记必须保留（PRD §6.4）",
            SensitivityStatus.SUSPECTED,
            updated.sensitivityStatus,
        )
        assertEquals("确认必须绑定当前内容版本", 1, updated.cloudConfirmedRevision)
        assertEquals(ProcessingStatus.PENDING, updated.processingStatus)
        assertEquals(1_000L, updated.updatedAt)
    }

    /**
     * 确认要同时记下"用户此刻看到的是哪几块内容"（2026-10-23 审查 R-2）。
     *
     * 只记版本号时，后来才落地的转写文本会被一次旧确认顺带放行 ——
     * 用户没看过的东西照样发去摘要。范围记下来之后，那种情况会重新要求确认。
     */
    @Test
    fun Invoke_recordsConfirmedScopeOfPresentContent() = runTest {
        derivedContentRepository.stored +=
            testDerived(
                id = "r1:TRANSCRIPTION",
                recordId = "r1",
                contentType = DerivedType.TRANSCRIPTION,
                content = "录音里的内容",
            )
        assetRepository.save(testAsset(id = "a1", recordId = "r1", mimeType = "audio/3gpp"))
        seed(
            testRecord(
                id = "r1",
                type = RecordType.VOICE_NOTE,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )

        useCase("r1", now = 1_000L)

        assertEquals(
            "确认范围要覆盖此刻存在的原文 + 转写 + 会上传的音频",
            setOf(ContentPart.RAW_TEXT, ContentPart.TRANSCRIPTION, ContentPart.ATTACHMENT),
            recordRepository.byId["r1"]!!.cloudConfirmedScope,
        )
    }

    /** 图片的字节从不出设备（OCR 在本地），确认范围里不该出现 ATTACHMENT。 */
    @Test
    fun Invoke_imageRecord_doesNotClaimAttachmentConfirmed() = runTest {
        assetRepository.save(testAsset(id = "a1", recordId = "r1", mimeType = "image/png"))
        seed(
            testRecord(
                id = "r1",
                type = RecordType.IMAGE,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )

        useCase("r1")

        assertTrue(
            "图片不会被上传，不该被算进确认范围：${recordRepository.byId["r1"]!!.cloudConfirmedScope}",
            ContentPart.ATTACHMENT !in recordRepository.byId["r1"]!!.cloudConfirmedScope,
        )
    }

    /** 图片记录确认上传后不该重跑 OCR：本地已经算过的东西不因为一次确认再算一遍。 */
    @Test
    fun Invoke_imageRecord_onlyQueuesCloudJobs() = runTest {
        seed(
            testRecord(
                id = "r-img",
                type = RecordType.IMAGE,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )

        useCase("r-img")

        assertEquals(
            listOf("r-img" to JobType.SUMMARIZE, "r-img" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertTrue("本地 OCR 不该被重跑", jobRepository.jobs.values.none { it.jobType == JobType.OCR })
    }

    @Test
    fun Invoke_existingFailedJob_isResetToQueuedWithFreshAttempts() = runTest {
        seed(testRecord(id = "r1", sensitivityStatus = SensitivityStatus.SUSPECTED))
        jobRepository.insert(
            com.zhicun.app.domain.model.ProcessingJob(
                id = "job-1",
                recordId = "r1",
                jobType = JobType.SUMMARIZE,
                status = JobStatus.FAILED,
                attempts = 3,
                errorCategory = "GatewayTransientException",
            ),
        )

        useCase("r1")

        val job = jobRepository.getByRecordAndType("r1", JobType.SUMMARIZE)!!
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals("必须重新给满 3 次机会", 0, job.attempts)
        assertEquals(null, job.errorCategory)
        assertEquals(
            listOf("r1" to JobType.SUMMARIZE, "r1" to JobType.EMBED),
            scheduler.enqueued,
        )
    }

    /**
     * 已完成的转写不重跑（2026-10-23 复核 F-1）。
     *
     * 整改前确认会把 `TRANSCRIPTION` 一起复位成 QUEUED：同一段音频被再上传一次，
     * 转写落地后的派生敏感复查又清掉刚写入的确认 → 摘要被取消、横幅再出现。
     * 用户每点一次确认就重复上传一次音频，摘要永远生成不出来。
     */
    @Test
    fun Invoke_doneTranscription_isNotRerun() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-t",
                recordId = "r1",
                jobType = JobType.TRANSCRIPTION,
                status = JobStatus.DONE,
                attempts = 1,
            ),
        )
        seed(
            testRecord(
                id = "r1",
                type = RecordType.VOICE_NOTE,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )

        useCase("r1")

        val transcription = jobRepository.getByRecordAndType("r1", JobType.TRANSCRIPTION)!!
        assertEquals("已完成的转写不能再跑一次", JobStatus.DONE, transcription.status)
        assertTrue(
            "转写不该重新入队：${scheduler.enqueued}",
            scheduler.enqueued.none { it.second == JobType.TRANSCRIPTION },
        )
        // 摘要与向量照常复位：它们是"用户这次确认要的产物"
        assertEquals(
            listOf("r1" to JobType.SUMMARIZE, "r1" to JobType.EMBED),
            scheduler.enqueued,
        )
        assertEquals(JobStatus.QUEUED, jobRepository.getByRecordAndType("r1", JobType.SUMMARIZE)!!.status)
    }

    /**
     * 确认要清掉等待轮次（2026-10-23 复核 F-2）。
     *
     * 不清的话，"等待上游超时"失败后点确认，第一轮就 `rounds+1` 超限、立刻再失败，
     * 用户看到的是"点了确认也没用"。
     */
    @Test
    fun Invoke_resetsWaitRounds() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-s",
                recordId = "r1",
                jobType = JobType.SUMMARIZE,
                status = JobStatus.FAILED,
                attempts = 3,
                waitRounds = 5,
            ),
        )
        seed(testRecord(id = "r1", sensitivityStatus = SensitivityStatus.SUSPECTED))

        useCase("r1")

        val job = jobRepository.getByRecordAndType("r1", JobType.SUMMARIZE)!!
        assertEquals("确认后必须重新获得完整等待窗口", 0, job.waitRounds)
    }

    @Test
    fun Invoke_unknownRecord_returnsFalseWithoutScheduling() = runTest {
        assertFalse(useCase("missing"))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun Invoke_blankId_returnsFalse() = runTest {
        assertFalse(useCase("  "))
    }

    /**
     * 云端通路不可用时，确认是空动作（R-3）。
     *
     * 整改前这里照样写 `cloudConfirmedRevision` 并排任务：Worker 再次判闸即 CANCELLED，
     * 而横幅因"已确认"消失 —— 用户点了一次、界面说好了、什么都没跑，且再也点不了。
     * 现在必须不写确认、不排任务，由界面如实说明原因。
     */
    @Test
    fun Invoke_cloudDisabled_doesNotConsumeConfirmation() = runTest {
        aiSettings.cloudEnabled = false
        seed(testRecord(id = "r1", sensitivityStatus = SensitivityStatus.SUSPECTED))

        assertFalse(useCase("r1", now = 1_000L))
        assertTrue("通路不通就不该排任务", scheduler.enqueued.isEmpty())
        assertEquals(
            "确认不能被空耗：cloudConfirmedRevision 必须保持未设置",
            null,
            recordRepository.byId["r1"]!!.cloudConfirmedRevision,
        )
    }

    @Test
    fun Invoke_gatewayNotConfigured_doesNotConsumeConfirmation() = runTest {
        gatewaySettings.baseUrl = null
        gatewaySettings.token = null
        seed(testRecord(id = "r1", sensitivityStatus = SensitivityStatus.SUSPECTED))

        assertFalse(useCase("r1", now = 1_000L))
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(null, recordRepository.byId["r1"]!!.cloudConfirmedRevision)
    }

    /**
     * 确认后，被敏感闸门取消的**本地出网**任务要跟着复活（R-7）。
     *
     * 否则用户确认了上传、摘要也生成了，但网页正文永远抓不到——
     * 因为那个任务停在 CANCELLED，没有任何路径会再碰它。
     */
    @Test
    fun Invoke_confirmedRecord_revivesCancelledWebExtract() = runTest {
        seed(
            testRecord(
                id = "w1",
                type = RecordType.WEB_LINK,
                sourceUrl = "https://example.com/post",
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )
        jobRepository.insert(
            com.zhicun.app.domain.model.ProcessingJob(
                id = "job-web",
                recordId = "w1",
                jobType = JobType.WEBPAGE_EXTRACT,
                status = JobStatus.CANCELLED,
                attempts = 3,
                errorCategory = "疑似敏感内容，待用户确认后再上传",
            ),
        )

        useCase("w1", now = 1_000L)

        val web = jobRepository.getByRecordAndType("w1", JobType.WEBPAGE_EXTRACT)!!
        assertEquals("被策略取消的抓取要重新排队", JobStatus.QUEUED, web.status)
        assertEquals("重新给满重试次数", 0, web.attempts)
        assertEquals(null, web.errorCategory)
        assertTrue(
            "必须真的投给调度器，实际 ${scheduler.enqueued}",
            scheduler.enqueued.contains("w1" to JobType.WEBPAGE_EXTRACT),
        )
    }

    /**
     * 敏感网页只点一次确认（2026-10-24 用户反馈）。
     *
     * 此前要分别点"确认上传"与"允许抓取"，用户的心智却是"我知悉敏感、允许出网"。
     * 现在同一次确认同时写两列确认，并把网页正文纳入确认范围 —— 后者决定抓取完成后
     * 摘要/向量会不会因为"PAGE_TEXT 不在范围里"再弹一次横幅。
     */
    @Test
    fun Invoke_sensitiveWebLink_alsoAllowsFetchAndCoversPageContent() = runTest {
        seed(
            testRecord(
                id = "w1",
                type = RecordType.WEB_LINK,
                sourceUrl = "https://example.com/post",
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
        )
        jobRepository.insert(
            ProcessingJob(
                id = "job-web",
                recordId = "w1",
                jobType = JobType.WEBPAGE_EXTRACT,
                status = JobStatus.CANCELLED,
                attempts = 3,
                errorCategory = "疑似敏感内容，待用户确认后再上传",
            ),
        )

        val scheduled = useCase("w1", now = 1_000L)

        assertTrue(scheduled)
        val updated = recordRepository.byId["w1"]!!
        assertEquals("一次点击同时写入抓取确认", 1, updated.webFetchConfirmedRevision)
        assertTrue(
            "抓回来的正文与标题要落在确认范围里：${updated.cloudConfirmedScope}",
            setOf(ContentPart.PAGE_TEXT, ContentPart.SOURCE_TITLE)
                .all { it in updated.cloudConfirmedScope },
        )
        assertEquals(
            "抓取任务要重新排队",
            JobStatus.QUEUED,
            jobRepository.getByRecordAndType("w1", JobType.WEBPAGE_EXTRACT)!!.status,
        )
        assertTrue(
            "必须真的投给调度器，实际 ${scheduler.enqueued}",
            scheduler.enqueued.contains("w1" to JobType.WEBPAGE_EXTRACT),
        )
    }

    /** 非网页记录不该被写上抓取确认：它没有可抓的 URL。 */
    @Test
    fun Invoke_sensitiveTextNote_doesNotWriteWebFetchConfirmation() = runTest {
        seed(testRecord(id = "r1", sensitivityStatus = SensitivityStatus.SUSPECTED))

        useCase("r1")

        assertEquals(null, recordRepository.byId["r1"]!!.webFetchConfirmedRevision)
        assertTrue(scheduler.enqueued.none { it.second == JobType.WEBPAGE_EXTRACT })
    }
}
