package com.zhicun.app.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.worker.ProcessorResult
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Worker 层的薄壳测试（M6 验收：同一任务重复执行不会重复处理）。
 *
 * 用 `TestListenableWorkerBuilder` + 自定义 [WorkerFactory] 构造一个测试用 Worker，
 * 这样既能走真实的 `CoroutineWorker.doWork()`，又不需要搭起整个 Hilt 图。
 * 处理逻辑本身由 [ProcessingJobExecutorTest] 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class BaseProcessingWorkerTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var executor: ProcessingJobExecutor

    private val recordId = "rec-1"

    /** 跨 Worker 实例共享的调用计数：幂等要求"第二个实例不干活"。 */
    private var performCount = 0

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        recordRepository = FakeRecordRepository(listOf(testRecord(id = recordId)))
            .aggregateJobsFrom(jobRepository)
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
    }

    @Test
    fun DoWork_withValidJob_runsOnceAndMarksDone() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = recordId,
                jobType = JobType.OCR,
                status = JobStatus.QUEUED,
                attempts = 0,
            ),
        )

        val result = buildWorker()?.startWork()?.get()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, performCount)
        assertEquals(
            JobStatus.DONE,
            jobRepository.getByRecordAndType(recordId, JobType.OCR)!!.status,
        )
    }

    @Test
    fun DoWork_secondRun_skipsProcessing() = runTest {
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = recordId,
                jobType = JobType.OCR,
                status = JobStatus.QUEUED,
                attempts = 0,
            ),
        )

        // WorkManager 重投同一任务时，会创建**新的** Worker 实例，但任务状态已 DONE
        buildWorker()?.startWork()?.get()
        val second = buildWorker()?.startWork()?.get()

        assertTrue("重复执行也要返回成功（不是失败），否则会无限重投", second is ListenableWorker.Result.Success)
        assertEquals("任务已完成，第二个实例不得再处理一次", 1, performCount)
    }

    @Test
    fun DoWork_missingInputData_fails() = runTest {
        val worker = TestListenableWorkerBuilder<TestWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .build()

        val result = worker.startWork().get()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, performCount)
    }

    @Test
    fun DoWork_unknownJobType_fails() = runTest {
        val worker = TestListenableWorkerBuilder<TestWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to "NOT_A_JOB_TYPE",
                ),
            )
            .build()

        val result = worker.startWork().get()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    private fun buildWorker(): TestWorker? =
        TestListenableWorkerBuilder<TestWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(factory())
            .setInputData(
                workDataOf(
                    ProcessingWorkKeys.RECORD_ID to recordId,
                    ProcessingWorkKeys.JOB_TYPE to JobType.OCR.name,
                ),
            )
            .build()

    private fun factory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == TestWorker::class.java.name) {
            TestWorker(appContext, workerParameters, executor)
        } else {
            null
        }
    }

    /** 测试用 Worker：只记录被调用了几次。 */
    inner class TestWorker(
        appContext: Context,
        params: WorkerParameters,
        executor: ProcessingJobExecutor,
    ) : BaseProcessingWorker(appContext, params, executor) {

        override suspend fun perform(record: Record): ProcessorResult {
            performCount++
            return ProcessorResult.Success
        }
    }
}
