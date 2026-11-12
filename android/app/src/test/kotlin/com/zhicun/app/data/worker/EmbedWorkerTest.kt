package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.FakeEmbedder
import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 向量 Worker（M12）。
 *
 * 走真实 `doWork()` + [ProcessingJobExecutor]，只替换网关与仓储。
 * 重点覆盖：
 * 1. **幂等**：任务已完成时不再调用网关、也不覆盖已有向量；
 * 2. 输入文本优先用摘要（向量要表达"这条记录讲什么"）；
 * 3. "没有可向量化的正文"不算失败（否则记录会被翻成红色）；
 * 4. 敏感内容不上传；
 * 5. 网关故障按可重试/确定性失败分类。
 */
@RunWith(RobolectricTestRunner::class)
class EmbedWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var embeddingRepository: FakeEmbeddingRepository
    private lateinit var embedder: FakeEmbedder
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
                    rawText = "想找一个能本地部署的视频生成模型",
                ),
            ),
        ).aggregateJobsFrom(jobRepository)
        derivedRepository = FakeDerivedContentRepository()
        embeddingRepository = FakeEmbeddingRepository()
        embedder = FakeEmbedder()
        executor = ProcessingJobExecutor(jobRepository, recordRepository, aiSettings, gatewaySettings)
        collectionRepository = FakeCollectionRepository().apply {
            recordLookup = { id -> recordRepository.byId[id] }
        }
    }

    private suspend fun seedJob(status: JobStatus = JobStatus.QUEUED) {
        jobRepository.insert(
            ProcessingJob(
                id = "job-embed",
                recordId = recordId,
                jobType = JobType.EMBED,
                status = status,
                attempts = 0,
            ),
        )
    }

    private suspend fun seedDerived(type: DerivedType, content: String) {
        derivedRepository.upsert(
            DerivedContent(
                id = "$recordId:$type",
                recordId = recordId,
                contentType = type,
                content = content,
                processorVersion = "test",
                processedAt = 1L,
                errorMessage = null,
            ),
        )
    }

    private suspend fun seedUpstreamJob(jobType: JobType, status: JobStatus) {
        jobRepository.insert(
            ProcessingJob(
                id = "job-$jobType",
                recordId = recordId,
                jobType = jobType,
                status = status,
                attempts = 0,
            ),
        )
    }

    private suspend fun currentJob() =
        jobRepository.getByRecordAndType(recordId, JobType.EMBED)!!

    private suspend fun startWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<EmbedWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.EMBED.name,
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
        ): ListenableWorker? = if (workerClassName == EmbedWorker::class.java.name) {
            EmbedWorker(
                appContext = appContext,
                params = workerParameters,
                executor = executor,
                derivedContentRepository = derivedRepository,
                embeddingRepository = embeddingRepository,
                jobRepository = jobRepository,
                embedder = embedder,
                aiSettings = aiSettings,
                gatewaySettings = gatewaySettings,
                collectionRepository = collectionRepository,
            )
        } else {
            null
        }
    }

    @Test
    fun DoWork_withRawText_storesVectorAndMarksDone() = runTest {
        seedJob()

        val result = startWorker()

        assertTrue("应成功，实际 $result", result is ListenableWorker.Result.Success)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f),
            embeddingRepository.stored.getValue(recordId).vector,
            0f,
        )
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
        assertEquals(
            "网关应收到原文",
            "想找一个能本地部署的视频生成模型",
            embedder.calls.single(),
        )
    }

    @Test
    fun DoWork_prefersSummaryOverRawText() = runTest {
        seedDerived(DerivedType.TITLE, "本地视频模型选型")
        seedDerived(DerivedType.SUMMARY, "对比了三个可本地部署的视频生成模型。")
        seedDerived(DerivedType.TAGS, "本地部署\n视频生成")
        seedJob()

        startWorker()

        val sent = embedder.calls.single()
        assertEquals(
            "标题 + 摘要 + 标签（标签原样带换行）",
            "本地视频模型选型\n对比了三个可本地部署的视频生成模型。\n本地部署\n视频生成",
            sent,
        )
        assertFalse("不该再退回原文", sent.contains("想找一个能本地部署"))
    }

    @Test
    fun DoWork_emptySummaryFallsBackToRawText() = runTest {
        // 摘要那一路"空摘要算成功"会写一行空串：向量化不该被这行拦住
        seedDerived(DerivedType.SUMMARY, "")
        seedJob()

        startWorker()

        assertEquals("想找一个能本地部署的视频生成模型", embedder.calls.single())
    }

    @Test
    fun DoWork_alreadyDone_skipsWithoutCallingGateway() = runTest {
        seedJob(status = JobStatus.DONE)
        embeddingRepository.seed(recordId, floatArrayOf(0.5f, 0.5f))

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("已完成的任务不该再花一次网关调用", embedder.calls.isEmpty())
        assertEquals("也不该覆盖已有向量", 0, embeddingRepository.upsertCount)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), embeddingRepository.stored.getValue(recordId).vector, 0f)
    }

    @Test
    fun DoWork_rerunOverwritesSameRow() = runTest {
        seedJob()
        embeddingRepository.seed(recordId, floatArrayOf(0.5f, 0.5f))

        startWorker()

        assertEquals("重跑覆盖同一行，不新增", 1, embeddingRepository.stored.size)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f),
            embeddingRepository.stored.getValue(recordId).vector,
            0f,
        )
    }

    @Test
    fun DoWork_emptyVectorFromGateway_succeedsWithoutWriting() = runTest {
        embedder.result = Embedding(model = "test-model", vector = FloatArray(0))
        seedJob()

        val result = startWorker()

        assertTrue("空向量是正常业务结果，不该判失败", result is ListenableWorker.Result.Success)
        assertEquals(0, embeddingRepository.upsertCount)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun DoWork_noText_doesNotFailRecord() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(rawText = null)
        seedJob()

        val result = startWorker()

        assertTrue(
            "没有正文只是一条记录进不了语义检索，不该让记录挂红，实际 $result",
            result is ListenableWorker.Result.Success,
        )
        assertTrue(embedder.calls.isEmpty())
        assertEquals(0, embeddingRepository.upsertCount)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
    }

    /**
     * 2026-11-05：判据从"正文为空才等"改成"上游还在跑就等"，与摘要同源。
     *
     * 图片 + 一句备注的形态最容易踩：备注让正文非空，于是立刻拿那句备注算了向量，
     * 整页 OCR 文字永远进不来——检索只是悄悄不准，用户根本看不出来。
     */
    @Test
    fun DoWork_userNoteButOcrRunning_waitsInsteadOfEmbeddingTheNote() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = "这个里面是结内容",
        )
        seedUpstreamJob(JobType.OCR, JobStatus.RUNNING)
        seedJob()

        val result = startWorker()

        assertTrue("OCR 还没跑完，不能只拿备注算向量，实际 $result", result is ListenableWorker.Result.Retry)
        assertTrue("不该调用网关", embedder.calls.isEmpty())
    }

    @Test
    fun DoWork_noTextButOcrRunning_retries() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        seedUpstreamJob(JobType.OCR, JobStatus.RUNNING)
        seedJob()

        val result = startWorker()

        assertTrue("上游还在 OCR，等它跑完就有正文了", result is ListenableWorker.Result.Retry)
        val job = currentJob()
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals("等待上游不消耗重试次数（QODER P2-2）", 0, job.attempts)
        assertTrue(job.nextRetryAt != null)
        assertTrue(embedder.calls.isEmpty())
    }

    @Test
    fun DoWork_noTextAndOcrFailed_doesNotLoop() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        seedUpstreamJob(JobType.OCR, JobStatus.FAILED)
        seedJob()

        val result = startWorker()

        assertTrue("上游已终态，再等也不会有正文", result is ListenableWorker.Result.Success)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertTrue(embedder.calls.isEmpty())
    }

    @Test
    fun DoWork_usesOcrTextWhenRawTextIsEmpty() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!.copy(
            type = RecordType.IMAGE,
            rawText = null,
        )
        seedDerived(DerivedType.OCR_TEXT, "白板上的架构图")
        seedUpstreamJob(JobType.OCR, JobStatus.DONE)
        seedJob()

        startWorker()

        assertEquals("白板上的架构图", embedder.calls.single())
        assertTrue(embeddingRepository.stored.containsKey(recordId))
    }

    @Test
    fun DoWork_suspectedSensitive_doesNotCallGatewayAndCancels() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!
            .copy(sensitivityStatus = SensitivityStatus.SUSPECTED)
        seedJob()

        val result = startWorker()

        assertTrue("疑似敏感不该上传；策略性跳过不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("绝不允许调用网关", embedder.calls.isEmpty())
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        assertNull(embeddingRepository.stored[recordId])
    }

    /**
     * M14：开关关闭后，**已经在队列里**的向量任务也不许再上传。
     * 与摘要 Worker 同一条闸门（`CloudUploadGate`），这里单独钉一次是因为
     * 向量那一路的输入是"摘要或原文"，很容易在改 `ProcessingInputText` 时
     * 顺手把前面的门禁挪走。
     */
    @Test
    fun DoWork_cloudAiDisabled_doesNotCallGateway() = runTest {
        aiSettings.cloudEnabled = false
        seedJob()

        val result = startWorker()

        assertTrue("策略性跳过不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("绝不允许调用网关", embedder.calls.isEmpty())
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertEquals(
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        assertNull(embeddingRepository.stored[recordId])
    }

    @Test
    fun DoWork_ioError_retries() = runTest {
        embedder.failure = IOException("连接被重置")
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertEquals(1, currentJob().attempts)
    }

    @Test
    fun DoWork_gatewayNotConfigured_failsWithoutRetry() = runTest {
        embedder.failure = GatewayNotConfiguredException()
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
        assertEquals(ProcessingStatus.FAILED, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun DoWork_longText_isTruncatedBeforeSending() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!
            .copy(rawText = "长".repeat(EmbedWorker.MAX_INPUT_CHARS + 500))
        seedJob()

        startWorker()

        assertEquals(EmbedWorker.MAX_INPUT_CHARS, embedder.calls.single().length)
    }
}
