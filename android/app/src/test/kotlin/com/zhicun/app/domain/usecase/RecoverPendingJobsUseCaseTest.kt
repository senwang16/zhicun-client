package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 启动补投（M6 审核整改）的行为测试。
 *
 * 审核指出的真问题：调度失败或进程被杀后，任务会永久停在 `QUEUED`/`RUNNING`，
 * 而整改前唯一会碰它们的是"用户再次保存"这条路径 —— 不保存就永远不跑。
 */
class RecoverPendingJobsUseCaseTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var useCase: RecoverPendingJobsUseCase

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        useCase = RecoverPendingJobsUseCase(jobRepository, scheduler)
    }

    private suspend fun seed(
        id: String,
        status: JobStatus,
        jobType: JobType = JobType.OCR,
        attempts: Int = 0,
    ): ProcessingJob {
        val job = ProcessingJob(
            id = id,
            recordId = "rec-$id",
            jobType = jobType,
            status = status,
            attempts = attempts,
            startedAt = if (status == JobStatus.RUNNING) 999L else null,
        )
        jobRepository.insert(job)
        return job
    }

    @Test
    fun Invoke_queuedJob_isReenqueued() = runTest {
        seed(id = "q1", status = JobStatus.QUEUED)

        val recovered = useCase()

        assertEquals(1, recovered)
        assertEquals(listOf("rec-q1" to JobType.OCR), scheduler.enqueued)
    }

    @Test
    fun Invoke_runningJob_isResetToQueuedAndReenqueued() = runTest {
        seed(id = "r1", status = JobStatus.RUNNING, attempts = 1)

        useCase()

        val job = jobRepository.getByRecordAndType("rec-r1", JobType.OCR)!!
        assertEquals(JobStatus.QUEUED, job.status)
        // 复位不能白送重试次数，否则反复崩溃会变成无限重试
        assertEquals(1, job.attempts)
        assertEquals(null, job.startedAt)
        assertEquals(listOf("rec-r1" to JobType.OCR), scheduler.enqueued)
    }

    @Test
    fun Invoke_terminalJobs_areUntouched() = runTest {
        seed(id = "d1", status = JobStatus.DONE)
        seed(id = "f1", status = JobStatus.FAILED, jobType = JobType.WEBPAGE_EXTRACT)
        seed(id = "c1", status = JobStatus.CANCELLED, jobType = JobType.SUMMARIZE)

        val recovered = useCase()

        assertEquals(0, recovered)
        assertTrue("终态任务不该被重新投递", scheduler.enqueued.isEmpty())
    }

    @Test
    fun Invoke_mixedStates_reportsOnlyPendingCount() = runTest {
        seed(id = "q1", status = JobStatus.QUEUED)
        seed(id = "q2", status = JobStatus.QUEUED, jobType = JobType.WEBPAGE_EXTRACT)
        seed(id = "r1", status = JobStatus.RUNNING, jobType = JobType.TRANSCRIPTION)
        seed(id = "d1", status = JobStatus.DONE)

        val recovered = useCase()

        // RUNNING 复位后并入 QUEUED 一起投递，因此是 3 而不是 4（不会重复投递）
        assertEquals(3, recovered)
        assertEquals(3, scheduler.enqueued.size)
    }

    @Test
    fun Invoke_idempotent_repeatedCallsProduceSameDeliverySet() = runTest {
        seed(id = "q1", status = JobStatus.QUEUED)

        useCase()
        useCase()

        // 重复调用只是再投一次（WorkManager 的 ExistingWorkPolicy.KEEP 会把它们合并），
        // 关键是不能因为重复调用而改变任务状态
        assertEquals(
            JobStatus.QUEUED,
            jobRepository.getByRecordAndType("rec-q1", JobType.OCR)!!.status,
        )
    }
}
