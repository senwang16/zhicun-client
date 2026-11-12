package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.audio.TranscriptionPolicy
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeAudioDurationProbe
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeSpeechTranscriber
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testAsset
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
 * 语音转写 Worker（M9）。
 *
 * 与 OCR 测试同一套手法：构造**真的** [TranscribeWorker] 走完整 `doWork()` 与
 * [ProcessingJobExecutor] 状态机，只把转写器与仓储换成本地假实现。
 * 这样能同时验到"取出哪个附件""结果落哪张表""失败怎么分类"三件事。
 */
@RunWith(RobolectricTestRunner::class)
class TranscribeWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var transcriber: FakeSpeechTranscriber
    private lateinit var durationProbe: FakeAudioDurationProbe
    private lateinit var executor: ProcessingJobExecutor
    private lateinit var sensitivityGuard: DerivedSensitivityGuard

    /** M14：云端开关，测试里可随时翻转让 Worker 读到"用户已关闭"。 */
    private val aiSettings = FakeAiSettings()

    /** 默认已配置；"网关未配置"闸门单独用空配置的假对象验证。 */
    private val gatewaySettings = FakeAiGatewaySettings()

    private val recordId = "rec-voice"

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        recordRepository = FakeRecordRepository(
            listOf(testRecord(id = recordId, type = RecordType.VOICE_NOTE, rawText = null)),
        ).aggregateJobsFrom(jobRepository)
        assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "asset-audio", recordId = recordId, mimeType = "audio/mp4")),
        )
        derivedRepository = FakeDerivedContentRepository()
        transcriber = FakeSpeechTranscriber()
        durationProbe = FakeAudioDurationProbe()
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            aiSettings,
            gatewaySettings,
        )
        sensitivityGuard = DerivedSensitivityGuard(SensitiveContentDetector(), recordRepository, FakeCollectionRepository())
    }

    private suspend fun seedJob(status: JobStatus = JobStatus.QUEUED) {
        jobRepository.insert(
            com.zhicun.app.domain.model.ProcessingJob(
                id = "job-voice",
                recordId = recordId,
                jobType = JobType.TRANSCRIPTION,
                status = status,
                attempts = 0,
            ),
        )
    }

    private suspend fun currentJob() =
        jobRepository.getByRecordAndType(recordId, JobType.TRANSCRIPTION)!!

    private suspend fun startWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<TranscribeWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.TRANSCRIPTION.name,
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
        ): ListenableWorker? = if (workerClassName == TranscribeWorker::class.java.name) {
            TranscribeWorker(
                appContext = appContext,
                params = workerParameters,
                executor = executor,
                assetRepository = assetRepository,
                derivedContentRepository = derivedRepository,
                transcriber = transcriber,
                durationProbe = durationProbe,
                aiSettings = aiSettings,
                gatewaySettings = gatewaySettings,
                sensitivityGuard = sensitivityGuard,
            )
        } else {
            null
        }
    }

    @Test
    fun DoWork_withAudio_writesTranscriptionAndMarksDone() = runTest {
        seedJob()

        val result = startWorker()

        assertTrue("应返回 Success，实际 $result", result is ListenableWorker.Result.Success)
        val derived = transcriptionFor("asset-audio")
        assertEquals("今天下午三点开会", derived?.content)
        assertEquals(TranscribeWorker.PROCESSOR_VERSION, derived?.processorVersion)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
        assertEquals(
            "转写器应收到音频附件的本地路径",
            assetRepository.stored.first().localPath,
            transcriber.calls.single().first,
        )
        assertEquals("audio/mp4", transcriber.calls.single().second)
    }

    @Test
    fun DoWork_emptyTranscript_stillSucceeds() = runTest {
        // 静音 / 纯环境音：没有文字是正常结果，不是失败
        transcriber.result = ""
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("", transcriptionFor("asset-audio")?.content)
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    /**
     * 单段录音超过 5 分钟：不转写，但音频照常保存、任务算成功（不是失败）。
     *
     * 判据来自 `TranscriptionPolicy`（时间 + 体积双限制，2026-11-03 自海外轨同步）。
     */
    @Test
    fun DoWork_audioTooLong_skipsWithoutUploadingAndSucceeds() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId[recordId]!!
            .copy(
                captureDurationMs = TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS +
                    TranscriptionPolicy.DURATION_TOLERANCE_MS + 1,
            )
        seedJob()

        val result = startWorker()

        assertTrue("超限不转写是预期结果，不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("超限音频绝不能上传", transcriber.calls.isEmpty())
        assertNull("不写转写正文", transcriptionFor("asset-audio")?.content)
        assertTrue(
            "要落一条可解释的说明，界面不能一直显示'正在转写'",
            transcriptionFor("asset-audio")?.errorMessage.orEmpty().contains("5 分钟"),
        )
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    /** 体积超过 20MB：同样只跳过转写，不发起上传。 */
    @Test
    fun DoWork_audioTooLarge_skipsWithoutUploadingAndSucceeds() = runTest {
        assetRepository.stored[0] = assetRepository.stored[0]
            .copy(fileSize = TranscriptionPolicy.MAX_TRANSCRIBE_BYTES + 1)
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("超体积音频绝不能上传", transcriber.calls.isEmpty())
        assertNull(transcriptionFor("asset-audio")?.content)
        assertTrue(
            "要说明为什么没有文字",
            transcriptionFor("asset-audio")?.errorMessage.orEmpty().contains("20MB"),
        )
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    /** 多段混合：合规的短段照常转写，超限的长段只跳过，不拖累同批。 */
    @Test
    fun DoWork_mixedLengths_transcribesOnlyAllowedSegments() = runTest {
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "asset-short", recordId = recordId, mimeType = "audio/mp4")
        assetRepository.stored += testAsset(id = "asset-long", recordId = recordId, mimeType = "audio/mp4")
        durationProbe.byPath[assetRepository.stored[0].localPath] = 60_000L
        durationProbe.byPath[assetRepository.stored[1].localPath] =
            TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS + TranscriptionPolicy.DURATION_TOLERANCE_MS + 1
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("只该转写合规的那一段", 1, transcriber.calls.size)
        assertEquals(assetRepository.stored[0].localPath, transcriber.calls.single().first)
        assertEquals("今天下午三点开会", transcriptionFor("asset-short")?.content)
        assertNull("超限段不写转写正文", transcriptionFor("asset-long")?.content)
        assertTrue(
            "超限段要留下原因，而不是让用户以为还在转",
            transcriptionFor("asset-long")?.errorMessage.orEmpty().contains("5 分钟"),
        )
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_twoAudios_writesOneTranscriptionPerAsset() = runTest {
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "asset-a1", recordId = recordId, mimeType = "audio/mp4")
        assetRepository.stored += testAsset(id = "asset-a2", recordId = recordId, mimeType = "audio/mp4")
        transcriber.resultByPath[assetRepository.stored[0].localPath] = "第一段：上午站会"
        transcriber.resultByPath[assetRepository.stored[1].localPath] = "第二段：客户电话"
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("两段音频各发一次请求", 2, transcriber.calls.size)
        assertEquals("第一段：上午站会", transcriptionFor("asset-a1")?.content)
        assertEquals("第二段：客户电话", transcriptionFor("asset-a2")?.content)
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_oneAudioMissing_writesErrorRowAndStillSucceeds() = runTest {
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "asset-a1", recordId = recordId, mimeType = "audio/mp4")
        assetRepository.stored += testAsset(
            id = "asset-a2",
            recordId = recordId,
            mimeType = "audio/mp4",
            saveStatus = AssetSaveStatus.MISSING,
        )
        seedJob()

        val result = startWorker()

        assertTrue("一段丢失不该让整条记录失败，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("只该转写落盘成功的那段", 1, transcriber.calls.size)
        assertEquals("今天下午三点开会", transcriptionFor("asset-a1")?.content)
        assertTrue(
            "丢失的音频要留下可解释的错误行",
            transcriptionFor("asset-a2")?.errorMessage.orEmpty().contains("音频未成功落盘"),
        )
    }

    @Test
    fun DoWork_cleansLegacyAndRemovedAssetRows() = runTest {
        derivedRepository.stored += com.zhicun.app.fake.testDerived(
            id = "$recordId:TRANSCRIPTION",
            recordId = recordId,
            contentType = DerivedType.TRANSCRIPTION,
            content = "老版本合并结果",
            assetId = null,
        )
        derivedRepository.stored += com.zhicun.app.fake.testDerived(
            id = "$recordId:TRANSCRIPTION:asset-gone",
            recordId = recordId,
            contentType = DerivedType.TRANSCRIPTION,
            content = "已删除音频的结果",
            assetId = "asset-gone",
        )
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        val remainingAssets = derivedRepository.stored
            .filter { it.contentType == DerivedType.TRANSCRIPTION }
            .map { it.assetId }
        assertEquals(listOf("asset-audio"), remainingAssets)
    }

    @Test
    fun DoWork_noAudioAsset_failsWithoutCallingTranscriber() = runTest {
        assetRepository.stored.clear()
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(transcriber.calls.isEmpty())
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_audioNotSaved_failsWithoutCallingTranscriber() = runTest {
        assetRepository.stored[0] = assetRepository.stored[0].copy(saveStatus = AssetSaveStatus.FAILED)
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(transcriber.calls.isEmpty())
    }

    /**
     * M14：用户关掉云端 AI 后，**已经在队列里**的转写任务也不许再上传音频。
     *
     * 这是纵深防御的意义所在：调度侧已经不会新排任务了，但开关关闭前已排进队列的那批
     * 只能靠执行时自查。音频一旦上传就撤不回来。
     */
    @Test
    fun DoWork_cloudAiDisabled_doesNotUploadAudio() = runTest {
        aiSettings.cloudEnabled = false
        seedJob()

        val result = startWorker()

        assertTrue("策略性跳过不是失败，实际 $result", result is ListenableWorker.Result.Success)
        assertTrue("绝不允许调用转写器", transcriber.calls.isEmpty())
        assertTrue("没有任何结果写入", derivedRepository.stored.isEmpty())
        assertEquals(JobStatus.CANCELLED, currentJob().status)
        assertEquals(
            "音频留在本地，记录应显示'仅本地'而不是'处理失败'",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
    }

    @Test
    fun DoWork_networkFailure_retriesInsteadOfFailing() = runTest {
        transcriber.failure = IOException("网关超时")
        seedJob()

        val result = startWorker()

        assertTrue("网络故障应重试，实际 $result", result is ListenableWorker.Result.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertEquals(ProcessingStatus.PENDING, recordRepository.byId[recordId]!!.processingStatus)
        assertNull(transcriptionFor("asset-audio"))
    }

    private fun transcriptionFor(assetId: String) =
        derivedRepository.stored.firstOrNull {
            it.recordId == recordId &&
                it.contentType == DerivedType.TRANSCRIPTION &&
                it.assetId == assetId
        }

    @Test
    fun DoWork_gatewayNotConfigured_failsPermanently() = runTest {
        // 未配置网关属于"用户可修复但不该自动重试"的情形：重试三次一样是未配置
        transcriber.failure = GatewayNotConfiguredException()
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
        assertEquals("转写失败不应删除音频附件", 1, assetRepository.stored.size)
    }

    @Test
    fun DoWork_alreadyDone_skipsWithoutCallingTranscriber() = runTest {
        seedJob(status = JobStatus.DONE)

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(transcriber.calls.isEmpty())
    }
}
