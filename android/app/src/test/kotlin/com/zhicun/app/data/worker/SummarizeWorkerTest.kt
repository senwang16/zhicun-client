package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.ai.GatewayRejectedException
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeContentEnricher
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 摘要 Worker（M11）。
 *
 * 走真实 `doWork()` + [ProcessingJobExecutor] 状态机，只替换网关与仓储。
 * 重点覆盖四条容易写错的规则：
 * 1. 疑似敏感**不上传**（且判终态，不反复重试）；
 * 2. 没有正文时"上游还在跑" → 可重试，"上游已终态" → 终态失败；
 * 3. 空摘要算成功（不能挂红）；
 * 4. 标签最多 5 个，且只替换 AI 标签。
 */
@RunWith(RobolectricTestRunner::class)
class SummarizeWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var enricher: FakeContentEnricher
    private lateinit var executor: ProcessingJobExecutor
    private lateinit var collectionRepository: FakeCollectionRepository

    /** M14：云端开关，测试里可随时翻转让 Worker 读到"用户已关闭"。 */
    private val aiSettings = FakeAiSettings()

    /** 默认已配置；"网关未配置"闸门单独用空配置的假对象验证。 */
    private val gatewaySettings = FakeAiGatewaySettings()

    private val recordId = "rec-text"

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        recordRepository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = recordId,
                    type = RecordType.TEXT_NOTE,
                    rawText = "今天和产品对齐了范围：先做本地优先的收件箱，再补 AI 摘要。",
                ),
            ),
        ).aggregateJobsFrom(jobRepository)
        derivedRepository = FakeDerivedContentRepository()
        tagRepository = FakeTagRepository()
        enricher = FakeContentEnricher()
        executor = ProcessingJobExecutor(jobRepository, recordRepository, aiSettings, gatewaySettings)
        collectionRepository = FakeCollectionRepository().apply {
            recordLookup = { id -> recordRepository.byId[id] }
        }
    }

    private suspend fun seedJob(
        status: JobStatus = JobStatus.QUEUED,
        jobType: JobType = JobType.SUMMARIZE,
        outputLanguage: String? = null,
    ) {
        jobRepository.insert(
            ProcessingJob(
                id = "job-$jobType",
                recordId = recordId,
                jobType = jobType,
                status = status,
                attempts = 0,
                outputLanguage = outputLanguage,
            ),
        )
    }

    private suspend fun currentJob() = jobRepository.getByRecordAndType(recordId, JobType.SUMMARIZE)!!

    private suspend fun startWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<SummarizeWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.SUMMARIZE.name,
                ),
            )
            .build()
            .startWork()
            .get()

    private fun factory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == SummarizeWorker::class.java.name) {
            SummarizeWorker(
                appContext = appContext,
                params = workerParameters,
                executor = executor,
                derivedContentRepository = derivedRepository,
                tagRepository = tagRepository,
                jobRepository = jobRepository,
                enricher = enricher,
                aiSettings = aiSettings,
                gatewaySettings = gatewaySettings,
                collectionRepository = collectionRepository,
            )
        } else {
            null
        }
    }

    private suspend fun derived(type: DerivedType) =
        derivedRepository.getByRecordAndType(recordId, type)

    @Test
    fun DoWork_withRawText_writesTitleSummaryAndTags() = runTest {
        seedJob()

        val result = startWorker()

        assertTrue("应成功，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("语音会议纪要", derived(DerivedType.TITLE)?.content)
        assertEquals("讨论了三点：范围、时间、负责人。", derived(DerivedType.SUMMARY)?.content)
        assertEquals("会议\n待办", derived(DerivedType.TAGS)?.content)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
        assertEquals(
            "网关应收到原文",
            "今天和产品对齐了范围：先做本地优先的收件箱，再补 AI 摘要。",
            enricher.calls.single(),
        )
    }

    @Test
    fun DoWork_tagsAreCappedAtFive() = runTest {
        enricher.result = enricher.result.copy(
            tags = listOf("一", "二", "三", "四", "五", "六", "七", "一"),
        )
        seedJob()

        startWorker()

        val stored = tagRepository.getByRecordId(recordId)
        assertEquals("PRD 规定最多 5 个 AI 标签", 5, stored.size)
        assertEquals(listOf("一", "二", "三", "四", "五"), stored.map { it.name })
        assertEquals(TagSource.AI, stored.first().source)
    }

    @Test
    fun DoWork_replacesOnlyAiTags_keepsUserTags() = runTest {
        tagRepository.replaceAiTags(recordId, listOf("旧AI标签"))
        tagRepository.associations.value += com.zhicun.app.domain.repository.RecordTag(
            recordId = recordId,
            tag = com.zhicun.app.domain.model.Tag(
                id = "user-1",
                name = "我的标签",
                source = TagSource.USER,
                confidence = null,
                createdAt = 1L,
            ),
        )
        seedJob()

        startWorker()

        val names = tagRepository.getByRecordId(recordId).map { it.name }
        assertTrue("用户标签必须保留", names.contains("我的标签"))
        assertTrue("旧 AI 标签必须被替换", !names.contains("旧AI标签"))
        assertTrue(names.contains("会议"))
    }

    @Test
    fun DoWork_emptySummaryFromGateway_failsSoUserCanRetry() = runTest {
        // 有正文、网关却只回了空壳（2026-11-05 线上：商汤 reasoning 烧光 max_tokens 时吐出
        // {"title":null,"summary":null,"tags":[]}）。以前这里算成功 ⇒ 界面显示
        // "没有可摘要的文字内容"、状态却是 DONE ⇒ 连重试入口都没有，用户只能删掉重建。
        //
        // 与 DoWork_fileRecordWithoutText_succeedsWithEmptySummary 的区别就在**有没有正文**：
        // 本来就没正文的记录仍算成功，有正文却拿不到摘要的必须能重跑。
        enricher.result = com.zhicun.app.domain.ai.Enrichment(title = null, summary = null, tags = emptyList())
        seedJob()

        val result = startWorker()

        assertTrue("有正文却没有摘要，要让用户能重跑，实际 $result", result is ListenableWorker.Result.Failure)
        assertEquals("空摘要仍要落库，重试成功后会被覆盖", "", derived(DerivedType.SUMMARY)?.content)
        assertEquals(JobStatus.FAILED, currentJob().status)
        assertTrue("没有标签时应清空 AI 标签", tagRepository.getByRecordId(recordId).isEmpty())
    }

    /**
     * 短正文的"空摘要"是提示词允许的合法结果，不能判失败（2026-11-05 修复）。
     *
     * "买牛奶"这类备忘没有可摘要的内容；此前与"上游失手"混为一谈，记录永久挂红，
     * 用户点多少次"重新生成"都还是同一个空结果。判据由 `SummaryPolicy` 统一给出。
     */
    @Test
    fun DoWork_shortBodyWithEmptySummary_succeeds() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!
            .copy(rawText = "买牛奶")
        enricher.result = com.zhicun.app.domain.ai.Enrichment(
            title = "买牛奶",
            summary = null,
            tags = emptyList(),
        )
        seedJob()

        val result = startWorker()

        assertTrue("短正文空摘要应算成功，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("空摘要仍要落库，界面据此显示'没有可摘要的文字'", "", derived(DerivedType.SUMMARY)?.content)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun DoWork_keepsExtractedWebTitle() = runTest {
        // 网页标题是站点自己的，不该被模型生成的短标题覆盖
        derivedRepository.upsert(
            DerivedContent(
                id = "$recordId:${DerivedType.TITLE}",
                recordId = recordId,
                contentType = DerivedType.TITLE,
                content = "Compose 官方文档",
                processorVersion = "web/v1",
                processedAt = 1L,
                errorMessage = null,
            ),
        )
        seedJob()

        startWorker()

        assertEquals("Compose 官方文档", derived(DerivedType.TITLE)?.content)
    }

    @Test
    fun DoWork_suspectedSensitive_doesNotCallGatewayAndCancels() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!
            .copy(sensitivityStatus = SensitivityStatus.SUSPECTED)
        seedJob()

        val result = startWorker()

        assertTrue("疑似敏感不该上传；策略性跳过不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("绝不允许调用网关", enricher.calls.isEmpty())
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertEquals(
            "不上传不等于处理失败，记录应聚合为'仅本地'",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        assertNull(derived(DerivedType.SUMMARY))
    }

    /**
     * M14：开关关闭后，**已经在队列里**的摘要任务也不许再上传。
     *
     * 调度侧已经不会新排任务了，但关闭前已排进队列的那批只能靠执行时自查 ——
     * 上传是不可撤销的动作，多判一次的成本远低于泄露一次。
     */
    @Test
    fun DoWork_cloudAiDisabled_doesNotCallGateway() = runTest {
        aiSettings.cloudEnabled = false
        seedJob()

        val result = startWorker()

        assertTrue("策略性跳过不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("绝不允许调用网关", enricher.calls.isEmpty())
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        assertNull(derived(DerivedType.SUMMARY))
        assertTrue("用户关掉开关时不该产生 AI 标签", tagRepository.associations.value.isEmpty())
    }

    @Test
    fun DoWork_noTextButUpstreamRunning_retries() = runTest {
        // 图片记录：原文为空，OCR 还在排队
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.QUEUED, jobType = JobType.OCR)

        val result = startWorker()

        assertTrue("等上游时应该重试而不是判死，实际 $result", result is ListenableWorker.Result.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertTrue(enricher.calls.isEmpty())
    }

    @Test
    fun DoWork_noTextAndUpstreamFailed_failsTerminally() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.FAILED, jobType = JobType.OCR)

        val result = startWorker()

        assertTrue("上游已终态时再等没有意义，实际 $result", result is ListenableWorker.Result.Failure)
        assertTrue(enricher.calls.isEmpty())
    }

    /**
     * 纯风景照 / 壁纸：OCR 跑完但零文字（写入空串，任务 DONE）。
     *
     * 2026-11-05 审核 CN-02 / GL-01：此前判据是"记录类型该不该有正文"（IMAGE 恒为 true），
     * 于是"上游成功但没字"被当成"上游失败了"，摘要永久挂红且重试无效 ——
     * 而 `EmbedWorker` 对同一事实判的是成功，两处结论互相矛盾。
     */
    @Test
    fun DoWork_imageWithNoTextAfterSuccessfulOcr_succeedsWithEmptySummary() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.DONE, jobType = JobType.OCR)
        derivedRepository.upsert(
            DerivedContent(
                id = "$recordId:${DerivedType.OCR_TEXT}",
                recordId = recordId,
                contentType = DerivedType.OCR_TEXT,
                content = "",
                processorVersion = "mlkit/v1",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val result = startWorker()

        assertTrue("上游成功但没有文字，不该判失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals("空摘要仍要落库", "", derived(DerivedType.SUMMARY)?.content)
        assertTrue("没有正文就不该调用网关", enricher.calls.isEmpty())
    }

    /**
     * 静音录音 / 超过 5 分钟按 `TranscriptionPolicy` 跳过的音频：转写任务 DONE、没有正文。
     *
     * 同一类误判的语音版本（审核 CN-02）：用户录了段环境音，或录满 5 分钟被策略跳过，
     * 摘要却报"没有可用于摘要的正文"永久失败。
     */
    @Test
    fun DoWork_voiceNoteWithoutTranscript_succeedsWithEmptySummary() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.VOICE_NOTE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.DONE, jobType = JobType.TRANSCRIPTION)

        val result = startWorker()

        assertTrue("转写成功但没字，不该判失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals("", derived(DerivedType.SUMMARY)?.content)
        assertTrue(enricher.calls.isEmpty())
    }

    /**
     * 对照：上游任务**被策略取消**（云端关闭 / 疑似敏感）不是"上游失手"。
     *
     * `CANCELLED` 表示"这件事按策略不做"，重试也还是同一个结论；判失败会让界面
     * 挂一条永远不会好的红色状态。
     */
    @Test
    fun DoWork_noTextAndUpstreamCancelled_succeedsWithEmptySummary() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.VOICE_NOTE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.CANCELLED, jobType = JobType.TRANSCRIPTION)

        val result = startWorker()

        assertTrue("策略性取消不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("", derived(DerivedType.SUMMARY)?.content)
    }

    @Test
    fun DoWork_fileRecordWithoutText_succeedsWithEmptySummary() = runTest {
        // 只挂了一个 PDF 的 FILE 记录：没有 OCR / 转写可跑，也永远不会产出正文。
        // 2026-10-24 之前这里被判 Permanent 失败，用户看到的"PDF 上传失败"就是这条 —— 
        // 同一个事实在 EmbedWorker 里是"跳过并成功"，两套判据让记录聚合成 PARTIAL。
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.FILE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()

        val result = startWorker()

        assertTrue("没有正文可摘要不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals("空摘要仍要落库，界面才能区分'处理过没内容'与'没处理'", "", derived(DerivedType.SUMMARY)?.content)
        assertTrue("没有正文就不该调用网关", enricher.calls.isEmpty())
    }

    /**
     * 2026-11-05 用户实测：图片记录写了句备注（"这个里面是结内容"）+ 整页菜谱截图。
     * 摘要只拿到那句备注，OCR 文字根本没进输入 —— 因为旧判据是"正文为空才等上游"。
     *
     * 现在判据改为"上游还在跑就等"，OCR 没出来之前绝不外发半份资料。
     */
    @Test
    fun DoWork_imageWithUserNote_waitsForOcrBeforeSummarizing() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = "这个里面是结内容",
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob()
        seedJob(status = JobStatus.QUEUED, jobType = JobType.OCR)

        val result = startWorker()

        assertTrue("OCR 还没产出就该等，实际 $result", result is ListenableWorker.Result.Retry)
        assertTrue("不能把半份资料发出去", enricher.calls.isEmpty())
        assertEquals(JobStatus.QUEUED, currentJob().status)
    }

    /**
     * 对照：OCR 完成后同一类记录要把**备注 + 识别文字**一起送进模型。
     *
     * 这条同时钉住"别为了修上面那个 bug 把用户备注丢掉"—— 备注是用户自己写的，
     * 优先级并不低于 OCR。
     */
    @Test
    fun DoWork_imageWithUserNoteAndOcr_sendsBothTexts() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = "这个里面是结内容",
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        derivedRepository.upsert(
            DerivedContent(
                id = "$recordId:${DerivedType.OCR_TEXT}",
                recordId = recordId,
                contentType = DerivedType.OCR_TEXT,
                content = "北大博雅员工餐厅一周菜谱\n周一 小米粥 煮鸡蛋",
                processorVersion = "mlkit/v1",
                processedAt = 1L,
                errorMessage = null,
            ),
        )
        seedJob()

        startWorker()

        val sent = enricher.calls.single()
        assertTrue("用户备注不能丢，实际：$sent", sent.contains("这个里面是结内容"))
        assertTrue("OCR 文字必须进模型，实际：$sent", sent.contains("北大博雅员工餐厅一周菜谱"))
    }

    @Test
    fun DoWork_usesOcrTextWhenRawTextIsEmpty() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(rawText = null)
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        derivedRepository.upsert(
            DerivedContent(
                id = "$recordId:${DerivedType.OCR_TEXT}",
                recordId = recordId,
                contentType = DerivedType.OCR_TEXT,
                content = "截图里的报错：NullPointerException",
                processorVersion = "mlkit/v1",
                processedAt = 1L,
                errorMessage = null,
            ),
        )
        seedJob()

        startWorker()

        assertEquals("截图里的报错：NullPointerException", enricher.calls.single())
    }

    @Test
    fun DoWork_networkFailure_retries() = runTest {
        enricher.failure = IOException("网关超时")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertEquals(ProcessingStatus.PENDING, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun DoWork_gatewayNotConfigured_failsWithoutRetry() = runTest {
        enricher.failure = GatewayNotConfiguredException()
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_contentRejected_failsWithoutRetry() = runTest {
        enricher.failure = GatewayRejectedException("CONTENT_REJECTED", "内容被拒绝")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_alreadyDone_skips() = runTest {
        seedJob(status = JobStatus.DONE)

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(enricher.calls.isEmpty())
    }

    // ------------------------------------------------------------- 输出语言（P11-07）

    /**
     * 任务行快照是输出语言的**唯一**依据：Worker 不读偏好。
     *
     * 这样"排完队之后用户切了语言"不会让自动重试换成另一种语言 ——
     * 同一次逻辑处理在任何一次重试里都必须产出同一种语言（R-I18N-04/05）。
     */
    @Test
    fun DoWork_englishJobSnapshot_callsEnricherWithEnglishAndRecordsIt() = runTest {
        seedJob(outputLanguage = AppLanguage.EN.code)

        val result = startWorker()

        assertTrue("应成功，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals(listOf(AppLanguage.EN), enricher.languages)
        assertEquals("en", derived(DerivedType.TITLE)?.outputLanguage)
        assertEquals("en", derived(DerivedType.SUMMARY)?.outputLanguage)
        assertEquals("en", derived(DerivedType.TAGS)?.outputLanguage)
    }

    @Test
    fun DoWork_chineseJobSnapshot_recordsChinese() = runTest {
        seedJob(outputLanguage = AppLanguage.ZH.code)

        startWorker()

        assertEquals(listOf(AppLanguage.ZH), enricher.languages)
        assertEquals("zh", derived(DerivedType.SUMMARY)?.outputLanguage)
    }

    /** 升级前的历史任务行没有语言快照：按中文处理，与服务端缺省 `zh` 同口径。 */
    @Test
    fun DoWork_missingSnapshot_defaultsToChinese() = runTest {
        seedJob()

        startWorker()

        assertEquals(listOf(AppLanguage.ZH), enricher.languages)
        assertEquals("zh", derived(DerivedType.SUMMARY)?.outputLanguage)
    }

    /** 模板（图片无字 / 静音录音）落空摘要时同样要记语言，否则历史结果无法区分。 */
    @Test
    fun DoWork_emptySummaryStillRecordsLanguage() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.FILE,
            rawText = null,
        )
        recordRepository.stored.value = listOf(recordRepository.byId[recordId]!!)
        seedJob(outputLanguage = AppLanguage.EN.code)

        startWorker()

        assertEquals("空摘要也属于生成类结果", "en", derived(DerivedType.SUMMARY)?.outputLanguage)
    }

    // ------------------------------------------------------------- 合集（M20）

    /** 把主记录换成合集，并把成员注册到假仓储；成员顺序即入参顺序。 */
    private suspend fun seedCollection(vararg members: Record, description: String? = "合集说明") {
        val collection = testRecord(
            id = recordId,
            type = RecordType.COLLECTION,
            rawText = description,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )
        recordRepository.byId[recordId] = collection
        members.forEach { recordRepository.byId[it.id] = it }
        recordRepository.stored.value = listOf(collection) + members
        collectionRepository.create(collection, members.map { it.id }, now = 1L)
    }

    @Test
    fun DoWork_collection_summarizesDescriptionAndMembersAndMarksGenerated() = runTest {
        seedCollection(
            testRecord(id = "m1", rawText = "成员一的内容"),
            testRecord(id = "m2", rawText = "成员二的内容"),
        )
        seedJob()

        val result = startWorker()

        assertTrue("合集摘要应成功，实际 $result", result is ListenableWorker.Result.Success)
        val input = enricher.calls.single()
        assertTrue(input.contains("合集说明"))
        assertTrue(input.contains("成员一的内容"))
        assertTrue(input.contains("成员二的内容"))
        assertEquals("生成成功后应刷新成员快照", listOf(recordId), collectionRepository.generated)
    }

    @Test
    fun DoWork_collectionMemberUpstreamRunning_retries() = runTest {
        // 合集自己没有正文，成员还是待 OCR 的图片
        seedCollection(testRecord(id = "m1", type = RecordType.IMAGE, rawText = null), description = null)
        seedJob()
        jobRepository.insert(
            ProcessingJob(
                id = "job-ocr-m1",
                recordId = "m1",
                jobType = JobType.OCR,
                status = JobStatus.QUEUED,
                attempts = 0,
            ),
        )

        val result = startWorker()

        assertTrue("成员上游还在跑时应等待，实际 $result", result is ListenableWorker.Result.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertTrue(enricher.calls.isEmpty())
    }

    @Test
    fun DoWork_collectionOnlyDeletedMembers_writesEmptySummary() = runTest {
        seedCollection(
            testRecord(id = "m1", rawText = "已删除成员的内容", isDeleted = true),
            description = null,
        )
        seedJob()

        val result = startWorker()

        assertTrue("全部成员已删除不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("", derived(DerivedType.SUMMARY)?.content)
        assertTrue("没有正文就不该调用网关", enricher.calls.isEmpty())
        assertEquals(listOf(recordId), collectionRepository.generated)
    }
}
