package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.FakeOcrEngine
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
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
 * OCR Worker 测试（M7 验收：Mock OCR 服务，验证结果写入 DerivedContent）。
 *
 * 与 M6 的 [BaseProcessingWorkerTest] 同一套手法：用 `TestListenableWorkerBuilder`
 * + 自定义 [WorkerFactory] 构造**真的** [OcrWorker]，走真实的 `doWork()` 与
 * [ProcessingJobExecutor] 状态机，只把引擎、仓储换成本地假实现。
 *
 * 这样一次能同时验到三件事：Worker 取图逻辑、OCR 结果落库、以及它与幂等/重试
 * 框架拼在一起后的整体行为（例如"IO 异常要变成重试而不是失败"）。
 */
@RunWith(RobolectricTestRunner::class)
class OcrWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var engine: FakeOcrEngine
    private lateinit var executor: ProcessingJobExecutor
    private lateinit var sensitivityGuard: DerivedSensitivityGuard

    private val recordId = "rec-img"

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        recordRepository = FakeRecordRepository(
            listOf(testRecord(id = recordId, type = RecordType.IMAGE, rawText = null)),
        ).aggregateJobsFrom(jobRepository)
        assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "asset-1", recordId = recordId, mimeType = "image/png")),
        )
        derivedRepository = FakeDerivedContentRepository()
        engine = FakeOcrEngine(result = "会议记录：周五发布")
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
        sensitivityGuard = DerivedSensitivityGuard(SensitiveContentDetector(), recordRepository, FakeCollectionRepository())
    }

    @Test
    fun DoWork_withImage_writesOcrTextAndMarksDone() = runTest {
        seedJob()

        val result = startWorker()

        assertTrue("应返回 Success，实际 $result", result is ListenableWorker.Result.Success)
        val derived = ocrFor("asset-1")
        assertEquals("会议记录：周五发布", derived?.content)
        assertEquals(OcrWorker.PROCESSOR_VERSION, derived?.processorVersion)
        assertEquals("引擎应收到附件的本地路径", assetRepository.stored.first().localPath, engine.lastPath)
        assertEquals(1, engine.callCount)
        assertEquals(JobStatus.DONE, currentJob().status)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun DoWork_blankTextFromEngine_stillSucceeds() = runTest {
        // 纯色图 / 风景照：识别不出字是正常结果，不是失败
        engine.result = ""
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("", ocrFor("asset-1")?.content)
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_withoutImageAsset_failsWithoutCallingEngine() = runTest {
        assetRepository.stored.clear()
        seedJob()

        val result = startWorker()

        assertTrue("没有图片属于数据异常，应直接终态失败", result is ListenableWorker.Result.Failure)
        assertEquals("没有可识别的图片就不该白跑一次模型", 0, engine.callCount)
        assertTrue("没有附件就不该写任何派生行", derivedRepository.stored.isEmpty())
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_assetNotSaved_failsWithoutCallingEngine() = runTest {
        assetRepository.stored[0] = assetRepository.stored[0].copy(saveStatus = AssetSaveStatus.FAILED)
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, engine.callCount)
        assertTrue(
            "唯一一张图没落盘时仍要留下错误行，详情页才能说清为什么没结果",
            ocrFor("asset-1")?.errorMessage.orEmpty().contains("图片未成功落盘"),
        )
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_twoImages_writesOneRowPerAsset() = runTest {
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "asset-1", recordId = recordId, mimeType = "image/png")
        assetRepository.stored += testAsset(id = "asset-2", recordId = recordId, mimeType = "image/jpeg")
        engine.resultByPath[assetRepository.stored[0].localPath] = "第一张：报错截图"
        engine.resultByPath[assetRepository.stored[1].localPath] = "第二张：白板"
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(2, engine.callCount)
        assertEquals("第一张：报错截图", ocrFor("asset-1")?.content)
        assertEquals("第二张：白板", ocrFor("asset-2")?.content)
        assertEquals("每条结果必须归属自己的附件", "asset-1", ocrFor("asset-1")?.assetId)
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_oneImageMissing_writesErrorRowAndStillSucceeds() = runTest {
        assetRepository.stored.clear()
        assetRepository.stored += testAsset(id = "asset-1", recordId = recordId, mimeType = "image/png")
        assetRepository.stored += testAsset(
            id = "asset-2",
            recordId = recordId,
            mimeType = "image/png",
            saveStatus = AssetSaveStatus.MISSING,
        )
        seedJob()

        val result = startWorker()

        assertTrue("一张坏图不该拖垮整条记录，实际 $result", result is ListenableWorker.Result.Success)
        assertEquals("只该识别落盘成功的那张", 1, engine.callCount)
        assertEquals("会议记录：周五发布", ocrFor("asset-1")?.content)
        assertTrue(
            "丢失的附件要留下可解释的错误行",
            ocrFor("asset-2")?.errorMessage.orEmpty().contains("图片未成功落盘"),
        )
        assertEquals(JobStatus.DONE, currentJob().status)
    }

    @Test
    fun DoWork_cleansLegacyAndRemovedAssetRows() = runTest {
        derivedRepository.stored += testDerived(
            id = "$recordId:OCR_TEXT",
            recordId = recordId,
            contentType = DerivedType.OCR_TEXT,
            content = "老版本合并结果",
            assetId = null,
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:OCR_TEXT:asset-gone",
            recordId = recordId,
            contentType = DerivedType.OCR_TEXT,
            content = "已删除附件的结果",
            assetId = "asset-gone",
        )
        seedJob()

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        val remainingAssets = derivedRepository.stored
            .filter { it.contentType == DerivedType.OCR_TEXT }
            .map { it.assetId }
        assertEquals("只保留当前附件的结果", listOf("asset-1"), remainingAssets)
    }

    @Test
    fun DoWork_engineThrowsIo_retriesAndKeepsDerivedEmpty() = runTest {
        engine.failure = IOException("图片被并发删除")
        seedJob()

        val result = startWorker()

        assertTrue("IO 异常必须变成重试，而不是终态失败", result is ListenableWorker.Result.Retry)
        assertNull(ocrFor("asset-1"))
        val job = currentJob()
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(1, job.attempts)
        assertEquals(
            "记录要回到待处理，不能让详情页一直显示处理中",
            ProcessingStatus.PENDING,
            recordRepository.byId[recordId]!!.processingStatus,
        )
    }

    @Test
    fun DoWork_engineThrowsOtherError_failsPermanently() = runTest {
        engine.failure = IllegalStateException("模型加载失败")
        seedJob()

        val result = startWorker()

        assertTrue("非 IO 异常重试多少次都一样，应直接失败", result is ListenableWorker.Result.Failure)
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun DoWork_jobAlreadyDone_skipsEngine() = runTest {
        seedJob(status = JobStatus.DONE)

        val result = startWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("已完成的任务不得重复调用模型", 0, engine.callCount)
        assertTrue(derivedRepository.stored.isEmpty())
    }

    @Test
    fun DerivedId_isStableForSameRecordAndType() {
        // 主键确定性：重复处理覆盖同一行，而不是插入第二行
        assertEquals(
            DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, "asset-1"),
            DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, "asset-1"),
        )
        assertTrue(DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, "asset-1").contains(recordId))
        assertTrue(
            "不同附件的结果必须是不同主键，否则 REPLACE 会互相覆盖",
            DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, "asset-1") !=
                DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, "asset-2"),
        )
    }

    private fun ocrFor(assetId: String) =
        derivedRepository.stored.firstOrNull {
            it.recordId == recordId &&
                it.contentType == DerivedType.OCR_TEXT &&
                it.assetId == assetId
        }

    private suspend fun seedJob(status: JobStatus = JobStatus.QUEUED) {
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = recordId,
                jobType = JobType.OCR,
                status = status,
                attempts = 0,
            ),
        )
    }

    private suspend fun currentJob(): ProcessingJob =
        jobRepository.getByRecordAndType(recordId, JobType.OCR)!!

    private suspend fun startWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<OcrWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.OCR.name,
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
        ): ListenableWorker? = if (workerClassName == OcrWorker::class.java.name) {
            OcrWorker(
                appContext = appContext,
                params = workerParameters,
                executor = executor,
                assetRepository = assetRepository,
                derivedContentRepository = derivedRepository,
                ocrEngine = engine,
                sensitivityGuard = sensitivityGuard,
            )
        } else {
            null
        }
    }
}
