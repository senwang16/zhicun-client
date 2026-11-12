package com.zhicun.app.data.worker

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.ai.GatewayRateLimitedException
import com.zhicun.app.domain.worker.ProcessorResult
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 任务执行器的状态机测试（M6 的核心：幂等 + 重试 + 状态回写）。
 *
 * 这才是"Worker 幂等性"真正被验证的地方：`CoroutineWorker` 只是把
 * WorkManager 的参数翻译成这里的调用，规则全在本类。
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingJobExecutorTest {

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var executor: ProcessingJobExecutor
    private lateinit var scheduler: FakeProcessingScheduler

    private val now = 1_700_000_000_000L
    private val recordId = "rec-1"

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        recordRepository = FakeRecordRepository(listOf(testRecord(id = recordId)))
            .aggregateJobsFrom(jobRepository)
        executor = ProcessingJobExecutor(
            jobRepository,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
            scheduler,
        )
    }

    private suspend fun seedJob(
        status: JobStatus = JobStatus.QUEUED,
        attempts: Int = 0,
        maxAttempts: Int = 3,
        waitRounds: Int = 0,
    ): ProcessingJob {
        val job = ProcessingJob(
            id = "job-1",
            recordId = recordId,
            jobType = JobType.OCR,
            status = status,
            attempts = attempts,
            maxAttempts = maxAttempts,
            waitRounds = waitRounds,
        )
        jobRepository.insert(job)
        return job
    }

    private suspend fun currentJob(): ProcessingJob =
        jobRepository.getByRecordAndType(recordId, JobType.OCR)!!

    @Test
    fun Execute_noJob_returnsNoJob() = runTest {
        val outcome = executor.execute(recordId, JobType.OCR) { ProcessorResult.Success }

        assertEquals(ExecutionOutcome.NoJob, outcome)
    }

    @Test
    fun Execute_alreadyDone_skipsWithoutProcessing() = runTest {
        seedJob(status = JobStatus.DONE)
        var processed = false

        val outcome = executor.execute(recordId, JobType.OCR) {
            processed = true
            ProcessorResult.Success
        }

        assertEquals(ExecutionOutcome.Skipped, outcome)
        assertTrue("已完成的任务绝不能再次执行（幂等第一道闸）", !processed)
    }

    @Test
    fun Execute_cancelled_skipsWithoutProcessing() = runTest {
        seedJob(status = JobStatus.CANCELLED)
        var processed = false

        val outcome = executor.execute(recordId, JobType.OCR) {
            processed = true
            ProcessorResult.Success
        }

        assertEquals(ExecutionOutcome.Skipped, outcome)
        assertTrue(!processed)
    }

    @Test
    fun Execute_success_marksJobDoneAndRecordDone() = runTest {
        seedJob()

        val outcome = executor.execute(recordId, JobType.OCR, now) { ProcessorResult.Success }

        assertEquals(ExecutionOutcome.Done, outcome)
        val job = currentJob()
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(1, job.attempts)
        assertEquals(now, job.finishedAt)
        assertEquals(ProcessingStatus.DONE, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun Execute_stateChangedDuringProcessing_discardsResult() = runTest {
        seedJob()

        // 模拟用户在 Worker 执行期间点了"重新生成"：状态被改回 QUEUED、attempts 归零。
        // 旧 Worker 结束时必须发现"库里已经不是 RUNNING 了"，而不是把结果盖回去 ——
        // 否则用户会看到"点了重试又跳回已完成/失败"，重试永远不生效。
        val outcome = executor.execute(recordId, JobType.OCR, now) {
            jobRepository.updateStatus(
                currentJob().copy(status = JobStatus.QUEUED, attempts = 0, startedAt = null),
            )
            ProcessorResult.Success
        }

        assertEquals(ExecutionOutcome.Skipped, outcome)
        val job = currentJob()
        assertEquals("用户重试后的状态不能被旧 Worker 覆盖", JobStatus.QUEUED, job.status)
        assertEquals(0, job.attempts)
    }

    @Test
    fun Execute_retryable_schedulesRetryWithBackoff() = runTest {
        seedJob()

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.Retryable("网络超时")
        }

        assertEquals(ExecutionOutcome.Retry(attempts = 1, nextRetryAt = now + 30_000L), outcome)
        val job = currentJob()
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(1, job.attempts)
        assertEquals(now + 30_000L, job.nextRetryAt)
        assertEquals("网络超时", job.errorCategory)
        // 记录不能卡在"处理中"，否则详情页会一直转圈
        assertEquals(ProcessingStatus.PENDING, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun Execute_retryableOnLastAttempt_marksFailedInsteadOfRetrying() = runTest {
        seedJob(attempts = 2, maxAttempts = 3)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.Retryable("又超时了")
        }

        assertTrue(outcome is ExecutionOutcome.Failed)
        val job = currentJob()
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(3, job.attempts)
        assertEquals(null, job.nextRetryAt)
        assertEquals(ProcessingStatus.FAILED, recordRepository.byId[recordId]!!.processingStatus)
    }

    @Test
    fun Execute_permanentFailure_marksFailedImmediately() = runTest {
        seedJob()

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.Permanent("文件不存在")
        }

        assertEquals(ExecutionOutcome.Failed("文件不存在"), outcome)
        val job = currentJob()
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals(1, job.attempts)
    }

    @Test
    fun Execute_attemptsExhausted_failsWithoutProcessing() = runTest {
        seedJob(attempts = 3, maxAttempts = 3)
        var processed = false

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            processed = true
            ProcessorResult.Success
        }

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertTrue("次数用尽不得再执行（幂等第二道闸）", !processed)
        assertEquals(JobStatus.FAILED, currentJob().status)
    }

    @Test
    fun Execute_recordMissing_failsWithoutRetry() = runTest {
        val orphanJob = ProcessingJob(
            id = "job-orphan",
            recordId = "rec-gone",
            jobType = JobType.OCR,
            status = JobStatus.QUEUED,
            attempts = 0,
        )
        jobRepository.insert(orphanJob)

        val outcome = executor.execute("rec-gone", JobType.OCR, now) { ProcessorResult.Success }

        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals(
            JobStatus.FAILED,
            jobRepository.getByRecordAndType("rec-gone", JobType.OCR)!!.status,
        )
    }

    @Test
    fun Execute_ioException_isTreatedAsRetryable() = runTest {
        seedJob()

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            throw IOException("连接被重置")
        }

        assertTrue(outcome is ExecutionOutcome.Retry)
    }

    @Test
    fun Execute_unexpectedException_isTreatedAsPermanent() = runTest {
        seedJob()

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            throw IllegalStateException("解析器内部错误")
        }

        assertTrue(outcome is ExecutionOutcome.Failed)
    }

    @Test
    fun Backoff_growsExponentiallyAndIsCapped() {
        assertEquals(30_000L, executor.backoffMillis(1))
        assertEquals(60_000L, executor.backoffMillis(2))
        assertEquals(120_000L, executor.backoffMillis(3))
        // 上限 1 小时：shift 再大也不会超过
        assertEquals(3_600_000L, executor.backoffMillis(20))
    }

    @Test
    fun Execute_processReceivesTheRecord() = runTest {
        seedJob()
        var seen: Record? = null

        executor.execute(recordId, JobType.OCR, now) { record ->
            seen = record
            ProcessorResult.Success
        }

        assertEquals(recordId, seen?.id)
    }

    // ------------------------------------------- 等上游（QODER P2-2 / 审查 R-4）

    /**
     * 等上游**不消耗重试次数**，但要记等待轮次。
     *
     * 转写单次可达 120 秒，按普通失败计数会 3 次耗尽；而不计轮次又会变成
     * "上游永远卡住 → 每 30 秒写一次库 + 重建一次 FTS"，耗电且界面永远"正在生成"。
     */
    @Test
    fun Execute_waitUpstream_requeuesWithoutConsumingAttempts() = runTest {
        seedJob(attempts = 1)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.WaitUpstream("等待上游任务 TRANSCRIPTION 产出正文")
        }

        assertTrue(outcome is ExecutionOutcome.Retry)
        val job = currentJob()
        assertEquals("等待不得消耗重试次数", 1, job.attempts)
        assertEquals("等待轮次要记进任务表", 1, job.waitRounds)
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(now + 30_000L, job.nextRetryAt)
        assertEquals(ProcessingStatus.PENDING, recordRepository.byId[recordId]!!.processingStatus)
    }

    /** 等够上限就按失败收尾，不再无限轮询（审查 R-4）。 */
    @Test
    fun Execute_waitUpstreamBeyondLimit_failsInsteadOfWaitingForever() = runTest {
        seedJob(attempts = 1, waitRounds = ProcessingJob.MAX_WAIT_ROUNDS)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.WaitUpstream("等待上游任务 OCR 产出正文")
        }

        assertTrue(outcome is ExecutionOutcome.Failed)
        val job = currentJob()
        assertEquals(JobStatus.FAILED, job.status)
        assertTrue(
            "失败原因要能看出是等超时：${job.errorCategory}",
            job.errorCategory.orEmpty().contains("超时"),
        )
        // 超时的原因里要带上最后一条等待理由，否则界面上只剩"超时"，看不出在等谁
        assertTrue(job.errorCategory.orEmpty().contains("OCR"))
        assertEquals(ProcessingStatus.FAILED, recordRepository.byId[recordId]!!.processingStatus)
    }

    /**
     * 上限的边界：第 5 轮仍然等待，第 6 轮才判失败（2026-10-23 复核 F-2）。
     *
     * 上限从 20 改成 5，是因为退避是指数的（30/60/120/240/480s ≈ 15 分钟），
     * 而不是原以为的"每 30 秒一次"。这条边界钉住"改常量时别把口径又改错"。
     */
    @Test
    fun Execute_waitUpstreamAtLastRound_stillWaits() = runTest {
        assertEquals("上限是 5 轮", 5, ProcessingJob.MAX_WAIT_ROUNDS)
        seedJob(waitRounds = ProcessingJob.MAX_WAIT_ROUNDS - 1)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            ProcessorResult.WaitUpstream("等待上游任务 OCR 产出正文")
        }

        assertTrue("最后一轮还没到上限，应当继续等", outcome is ExecutionOutcome.Retry)
        assertEquals(JobStatus.QUEUED, currentJob().status)
        assertEquals(ProcessingJob.MAX_WAIT_ROUNDS, currentJob().waitRounds)
    }

    /**
     * 429 限流：按 `Retry-After` 排一条带延迟的新任务，且**不消耗**重试次数。
     *
     * 不能返回 `Retry`：WorkManager 只会按请求创建时的 30s 指数退避再跑一次，
     * 服务端说 120 秒后，它 30 秒就再撞一次。执行器改用 `reenqueueAfter` 自行排期，
     * Worker 本次返回 success（`Deferred`），不叠加退避。
     */
    @Test
    fun Execute_rateLimited_defersByRetryAfterWithoutConsumingAttempts() = runTest {
        seedJob(attempts = 1)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            throw GatewayRateLimitedException("网关限流（429）", retryAfterSeconds = 120)
        }

        assertEquals(
            ExecutionOutcome.Deferred(now + 120_000L),
            outcome,
        )
        val job = currentJob()
        assertEquals("限流不得消耗重试次数", 1, job.attempts)
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(now + 120_000L, job.nextRetryAt)
        assertEquals(
            "必须按 Retry-After 排延迟任务",
            Triple(recordId, JobType.OCR, 120_000L),
            scheduler.reenqueuedAfter.single(),
        )
    }

    /** 服务端没给 Retry-After：退回 30 秒指数退避，但同样不消耗重试次数。 */
    @Test
    fun Execute_rateLimitedWithoutRetryAfter_usesDefaultBackoff() = runTest {
        seedJob(attempts = 1)

        val outcome = executor.execute(recordId, JobType.OCR, now) {
            throw GatewayRateLimitedException("网关限流（429）")
        }

        assertEquals(ExecutionOutcome.Deferred(now + 30_000L), outcome)
        assertEquals(1, currentJob().attempts)
        assertEquals(
            Triple(recordId, JobType.OCR, 30_000L),
            scheduler.reenqueuedAfter.single(),
        )
    }
}
