package com.zhicun.app.data.worker

import android.util.Log
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.GatewayRateLimitedException
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.NoopProcessingScheduler
import com.zhicun.app.domain.worker.ProcessingScheduler
import com.zhicun.app.domain.worker.ProcessorResult
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 一次任务执行的最终结论（Worker 据此决定返回 success / retry / failure）。
 *
 * 与 [ProcessorResult] 的区别：那个是"处理器怎么想"，这个是"调度器该怎么做"。
 * 中间差了一层重试次数判定 —— 处理器说可重试，但已经用完 3 次时结论是失败。
 */
sealed interface ExecutionOutcome {

    /** 任务已完成或已取消，本次没有执行（幂等跳过）。 */
    data object Skipped : ExecutionOutcome

    /** 任务不存在（被删除或从未创建），不当作失败。 */
    data object NoJob : ExecutionOutcome

    data object Done : ExecutionOutcome

    /** 任务按策略取消（不上传），本次没有执行业务逻辑。 */
    data class Cancelled(val reason: String) : ExecutionOutcome

    /** 需要重试，`nextRetryAt` 已写入任务表。 */
    data class Retry(val attempts: Int, val nextRetryAt: Long) : ExecutionOutcome

    /**
     * 已按 `nextRetryAt` 排好一条**带延迟的新任务**，当前这次执行直接结束。
     *
     * 与 [Retry] 的区别：WorkManager 的 `Result.retry()` 只能走请求创建时定死的
     * 指数退避，无法表达服务端 `Retry-After`。限流路径由执行器调
     * [ProcessingScheduler.reenqueueAfter] 自行排期，Worker 返回 success，
     * 避免 WorkManager 再叠加一次 30 秒退避。
     */
    data class Deferred(val nextRetryAt: Long) : ExecutionOutcome

    /** 终态失败：要么确定性错误，要么重试次数耗尽。 */
    data class Failed(val reason: String) : ExecutionOutcome
}

/**
 * 任务执行的编排与状态机（M6 的"幂等 + 重试 + 状态同步"全在这里）。
 *
 * 为什么从 Worker 里抽出来：`CoroutineWorker` 的构造依赖 `WorkerParameters`，
 * 直接测它得搭一套 WorkManager 测试环境；而"已完成就跳过""超过 3 次判失败"
 * 是纯粹的业务规则，值得用普通单测穷举。Worker 于是只剩"读参数 → 调这里 → 翻译成 Result"。
 *
 * ## 记录状态是聚合出来的（2026-10-23 QODER P2-1）
 * 一条记录并行挂多个任务，本执行器**不再**"谁跑完谁改记录状态"—— 那样最后完成的
 * 任务会覆盖一切（摘要永久失败但向量成功时，详情页永远"正在生成摘要"）。
 * 每次任务行状态变化后调 [syncRecordStatus]，由 SQL 对任务集合聚合出记录状态。
 */
@Singleton
class ProcessingJobExecutor @Inject constructor(
    private val jobRepository: ProcessingJobRepository,
    private val recordRepository: RecordRepository,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
    /**
     * 延迟重排用（429 `Retry-After`）。默认空实现只为手工构造的旧测试；
     * 生产环境由 Hilt 注入 WorkManager 实现。
     */
    private val scheduler: ProcessingScheduler = NoopProcessingScheduler,
) {

    /**
     * 执行一次任务。
     *
     * @param process 真正的处理逻辑（OCR / 网页提取…），由具体 Worker 提供
     * @param now 时间戳；显式传入便于测试断言退避时间
     */
    suspend fun execute(
        recordId: String,
        jobType: JobType,
        now: Long = System.currentTimeMillis(),
        process: suspend (Record) -> ProcessorResult,
    ): ExecutionOutcome {
        val job = jobRepository.getByRecordAndType(recordId, jobType)
            ?: return ExecutionOutcome.NoJob.also {
                Log.w(TAG, "任务不存在，跳过 recordId=$recordId type=$jobType")
            }

        // 幂等第一道闸：已完成/已取消的任务绝不能再跑（ARCHITECTURE §6.4）
        if (job.status == JobStatus.DONE || job.status == JobStatus.CANCELLED) {
            Log.i(TAG, "任务已处于 ${job.status}，跳过 recordId=$recordId type=$jobType")
            return ExecutionOutcome.Skipped
        }

        // 幂等第二道闸：次数已用完（例如系统重投时状态没来得及更新），直接判终态
        if (job.attempts >= job.maxAttempts) {
            val reason = "重试次数已用尽（${job.attempts}/${job.maxAttempts}）"
            finishAsFailed(job, now, reason)
            return ExecutionOutcome.Failed(reason)
        }

        val stored = recordRepository.getById(recordId)
        if (stored == null) {
            // 记录被删除：任务没有意义，也不该重试
            val reason = "记录不存在，可能已被删除"
            finishAsFailed(job, now, reason)
            return ExecutionOutcome.Failed(reason)
        }

        // 记录已被标记删除（2026-10-24 删除功能）。
        //
        // `getById` 刻意不过滤 is_deleted（详情页要靠它显示"已删除"横幅），所以这里必须
        // 单独判一次。用户点了删除后，调度侧会 cancel，但取消信号到达 Worker 有延迟 ——
        // 这段时间里继续跑完就会往 derived_contents 写一行，而清理事务可能已经结束，
        // 结果是留下一条指向不存在记录的孤儿派生内容。
        //
        // 判为 CANCELLED 而不是 FAILED：这不是处理出错，是"这件事已经不需要做了"。
        // 判成失败会让界面显示"删除失败"这种让人困惑的提示。
        if (stored.isDeleted) {
            finishAsCancelled(job, now, reason = "记录已被删除")
            return ExecutionOutcome.Skipped
        }

        // 合集交给 Worker 的是"成员敏感并集"视图（M20）：Worker 里的第一道门禁
        // 与调度侧必须看到同一个敏感状态，否则会出现"调度放行、执行拦截"的抖动。
        // 其他类型原样返回，语义不变。
        val record = recordRepository.gateRecord(stored)

        val attempt = job.attempts + 1
        jobRepository.updateStatus(
            job.copy(
                status = JobStatus.RUNNING,
                attempts = attempt,
                startedAt = now,
                nextRetryAt = null,
            ),
        )
        syncRecordStatus(recordId, now)

        val result = try {
            process(record)
        } catch (e: CancellationException) {
            // Worker 被取消（用户撤销 / 系统停止）：必须原样抛出，让 WorkManager 拿到取消信号；
            // 任务状态留在 RUNNING，下次重投会重新接管
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "处理失败 recordId=$recordId type=$jobType attempt=$attempt", e)
            classify(e)
        }

        // 执行结束后的每次回写都走 CAS（`status='RUNNING' AND attempts=:attempt`）：
        // 这期间用户可能点了"重新生成"（状态被改成 QUEUED、attempts 清零）或删了记录，
        // 无条件 UPDATE 会把用户的动作覆盖回去 —— 表现为"点了重试又跳回失败/处理中"。
        return when (result) {
            is ProcessorResult.Success -> {
                val applied = persistOutcome(
                    job.copy(
                        status = JobStatus.DONE,
                        attempts = attempt,
                        startedAt = now,
                        finishedAt = now,
                        nextRetryAt = null,
                        errorCategory = null,
                    ),
                    expectedAttempts = attempt,
                )
                if (!applied) {
                    staleOutcome(recordId, jobType)
                } else {
                    syncRecordStatus(recordId, now)
                    ExecutionOutcome.Done
                }
            }

            is ProcessorResult.WaitUpstream -> waitForUpstream(job, now, result.reason, attempt)

            is ProcessorResult.RateLimited ->
                waitForRateLimit(job, now, result.reason, result.retryAfterSeconds, attempt)

            is ProcessorResult.Retryable -> handleRetryable(job, attempt, now, result.reason)

            is ProcessorResult.Permanent -> {
                val applied = finishAsFailed(
                    job.copy(attempts = attempt, startedAt = now),
                    now,
                    result.reason,
                    expectedAttempts = attempt,
                )
                if (applied) ExecutionOutcome.Failed(result.reason) else staleOutcome(recordId, jobType)
            }

            is ProcessorResult.Blocked -> {
                // 策略性不上传：终态 CANCELLED，不是失败。重试/补投不会再碰它；
                // 用户改变主意（确认上传 / 打开开关）时由对应用例把它复位成 QUEUED。
                val applied = persistOutcome(
                    job.copy(
                        status = JobStatus.CANCELLED,
                        attempts = attempt,
                        startedAt = now,
                        finishedAt = now,
                        nextRetryAt = null,
                        errorCategory = result.reason,
                    ),
                    expectedAttempts = attempt,
                )
                if (!applied) {
                    staleOutcome(recordId, jobType)
                } else {
                    syncRecordStatus(recordId, now)
                    Log.i(TAG, "任务按策略取消 recordId=$recordId type=$jobType reason=${result.reason}")
                    ExecutionOutcome.Cancelled(result.reason)
                }
            }
        }
    }

    /**
     * 状态回写。
     *
     * @param expectedAttempts 非 null 时走条件更新（CAS）；null = 无条件写入，
     *   只用于"任务本来就不在 RUNNING"的前置检查路径（次数用尽、记录不存在）
     * @return false = 状态已被别人改过，本次结果作废
     */
    private suspend fun persistOutcome(job: ProcessingJob, expectedAttempts: Int?): Boolean {
        if (expectedAttempts == null) {
            jobRepository.updateStatus(job)
            return true
        }
        return jobRepository.updateStatusIfRunning(job, expectedAttempts)
    }

    /**
     * 结果作废：执行期间用户点了重试或删了记录，本次产出不该覆盖用户的选择。
     *
     * 返回 [ExecutionOutcome.Skipped] 而不是 Failed：这既不是处理出错，也不是"做完了"，
     * 只是"这条记录的当前状态已经由更新的操作说了算"。Worker 据此返回 success，
     * 避免 WorkManager 再重投一次反而把用户刚排的新任务盖掉。
     */
    private fun staleOutcome(recordId: String, jobType: JobType): ExecutionOutcome {
        Log.w(TAG, "任务状态已被外部修改，本次结果作废 recordId=$recordId type=$jobType")
        return ExecutionOutcome.Skipped
    }

    /**
     * 外发前的二次门禁（2026-10-23 审查 R-6：TOCTOU）。
     *
     * `perform(record)` 拿到的是**任务开始时**的记录快照。从"判闸通过"到"真正把内容发出去"
     * 之间隔着若干次 DB 读（读派生正文、读附件），而同一条记录的兄弟任务
     * （图片 = OCR + 摘要 + 向量并行）可能就在这个窗口里把记录升为 `SUSPECTED`。
     * 窗口是毫秒级、概率很低，但后果是"刚判定为敏感的内容仍然被上传"，不可撤销。
     *
     * 因此各云端 Worker 在**即将发请求的前一行**再读一次记录、再判一次闸。
     * 判据仍然是同一个 [CloudUploadGate]：这里不做任何自己的判断。
     *
     * @return 不该外发时返回原因（调用方转成 [ProcessorResult.Blocked]）；允许外发返回 null
     */
    suspend fun recheckBeforeUpload(
        recordId: String,
        requiredParts: Set<ContentPart> = emptySet(),
    ): String? {
        // 记录在"任务开始"与"真的要发请求"之间被删掉了（2026-10-23 复核 F-9）：
        // 此前这里返回 null 表示放行，于是"删除"这个动作拦不住已经在跑的上传 ——
        // 用户刚删掉的东西照样发到了云端。删除是明确意图，按"不要发"处理。
        val fresh = recordRepository.getById(recordId) ?: return REASON_RECORD_GONE
        return CloudUploadGate.blockReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
            record = recordRepository.gateRecord(fresh),
            requiredParts = requiredParts,
        )
    }

    /**
     * 上游还没产出正文：原样重新排队，**不消耗重试次数**（QODER P2-2）。
     *
     * `attempts` 写回执行前的值 —— 本轮自增的 1 被撤销。等待的终止条件是上游任务
     * 到达终态；上游卡在 QUEUED/RUNNING 的情况由 `RecoverPendingJobsUseCase`
     * 在进程重启后复位，不会形成永久轮询（见 `ProcessorResult.WaitUpstream` KDoc）。
     *
     * ## 退避与实际唤醒时间（2026-10-23 复核 F-2）
     * 真正决定"多久后再叫醒 Worker"的是 `WorkManager` 的 `BackoffPolicy.EXPONENTIAL`
     * （30s 起，翻倍，5 小时封顶），不是这里的常量。因此 [nextRetryAt] 用**同一条曲线**
     * （[backoffMillis]）算出来，才能如实反映"下次大概什么时候再看一眼"；
     * 原来写死 30 秒，界面上的时间会撒谎，也让人误以为 20 轮只有 10 分钟。
     * 它仍然只是**诊断字段**，调度不读它。
     */
    private suspend fun waitForUpstream(
        job: ProcessingJob,
        now: Long,
        reason: String,
        expectedAttempts: Int,
    ): ExecutionOutcome {
        // 等待有上限（2026-10-23 审查 R-4）：上游若卡在 QUEUED 永远不会推进，
        // 没有上限就是一次次写库 + 重建 FTS，耗电且界面永远"正在生成"。
        // 轮次写进任务表而不是内存：进程被杀正是"上游不推进"的常见原因。
        val rounds = job.waitRounds + 1
        if (rounds > ProcessingJob.MAX_WAIT_ROUNDS) {
            val why = "等待上游产出正文超时（已等 ${job.waitRounds} 轮）：$reason"
            val applied = finishAsFailed(
                job.copy(waitRounds = rounds, startedAt = now),
                now,
                why,
                expectedAttempts = expectedAttempts,
            )
            Log.w(TAG, "等待上游超限，按失败收尾 recordId=${job.recordId} type=${job.jobType}")
            return if (applied) ExecutionOutcome.Failed(why) else staleOutcome(job.recordId, job.jobType)
        }

        val nextRetryAt = now + backoffMillis(rounds)
        val applied = persistOutcome(
            job.copy(
                status = JobStatus.QUEUED,
                attempts = job.attempts,
                waitRounds = rounds,
                startedAt = null,
                finishedAt = null,
                nextRetryAt = nextRetryAt,
                errorCategory = reason,
            ),
            expectedAttempts = expectedAttempts,
        )
        if (!applied) return staleOutcome(job.recordId, job.jobType)
        syncRecordStatus(job.recordId, now)
        Log.i(
            TAG,
            "等待上游产出正文 recordId=${job.recordId} type=${job.jobType} " +
                "round=$rounds/${ProcessingJob.MAX_WAIT_ROUNDS} reason=$reason",
        )
        return ExecutionOutcome.Retry(attempts = job.attempts, nextRetryAt = nextRetryAt)
    }

    /**
     * 被网关限流（429）：按服务端 `Retry-After` 重新排队，**不消耗重试次数**。
     *
     * `attempts` 写回执行前的值（本轮自增的 1 被撤销），与 [waitForUpstream] 同一套
     * "不把等待算成失败"的口径。区别只在退避依据：优先用 `Retry-After`，
     * 缺失时退回指数曲线。
     *
     * 为什么必须有这条：国内包所有用户共享一个令牌，网关限流是按令牌计数的
     * 全局桶 —— 高峰期被 429 是常态。若按普通失败计数，用户点几次"重新处理"
     * 就把 3 次机会用光，记录从此永久 FAILED，且看起来像"服务坏了"。
     */
    private suspend fun waitForRateLimit(
        job: ProcessingJob,
        now: Long,
        reason: String,
        retryAfterSeconds: Long?,
        expectedAttempts: Int,
    ): ExecutionOutcome {
        val delayMs = retryAfterSeconds?.let { it * 1_000L } ?: backoffMillis(1)
        // 上限兜底：服务端若给出离谱值（或系统时间被改），不能让任务永久不再被叫醒
        val boundedMs = delayMs.coerceIn(MIN_RATE_LIMIT_DELAY_MS, MAX_RATE_LIMIT_DELAY_MS)
        val nextRetryAt = now + boundedMs
        val applied = persistOutcome(
            job.copy(
                status = JobStatus.QUEUED,
                attempts = job.attempts,
                startedAt = null,
                finishedAt = null,
                nextRetryAt = nextRetryAt,
                errorCategory = reason,
            ),
            expectedAttempts = expectedAttempts,
        )
        if (!applied) return staleOutcome(job.recordId, job.jobType)
        syncRecordStatus(job.recordId, now)

        // 真正按 Retry-After 推迟下一次执行（2026-11-05）：
        // WorkManager 的 Result.retry() 只认请求创建时的 30s 指数退避，服务端说
        // "120 秒后再来"时，返回 Retry 会在 30 秒就再撞一次 429。这里直接排一条
        // 带 initialDelay 的新任务，Worker 本次返回 success（不叠加退避）。
        scheduler.reenqueueAfter(job.recordId, job.jobType, boundedMs)
        Log.i(
            TAG,
            "网关限流，已按 Retry-After 延迟重排 recordId=${job.recordId} type=${job.jobType} " +
                "after=${retryAfterSeconds ?: "默认"}s attempts=${job.attempts}",
        )
        return ExecutionOutcome.Deferred(nextRetryAt)
    }

    private suspend fun handleRetryable(
        job: ProcessingJob,
        attempt: Int,
        now: Long,
        reason: String,
    ): ExecutionOutcome {
        if (attempt >= job.maxAttempts) {
            // 用尽最后一次机会：判终态，不再排队
            val applied = finishAsFailed(
                job.copy(attempts = attempt, startedAt = now),
                now,
                reason,
                expectedAttempts = attempt,
            )
            return if (applied) ExecutionOutcome.Failed(reason) else staleOutcome(job.recordId, job.jobType)
        }

        val nextRetryAt = now + backoffMillis(attempt)
        val applied = persistOutcome(
            job.copy(
                status = JobStatus.QUEUED,
                attempts = attempt,
                startedAt = null,
                finishedAt = null,
                nextRetryAt = nextRetryAt,
                errorCategory = reason,
            ),
            expectedAttempts = attempt,
        )
        if (!applied) return staleOutcome(job.recordId, job.jobType)
        // 记录回到"待处理"而不是留在"处理中"：处理中是个瞬时态，卡住会让详情页一直转圈
        syncRecordStatus(job.recordId, now)
        Log.i(TAG, "任务将重试 recordId=${job.recordId} attempt=$attempt nextRetryAt=$nextRetryAt")
        return ExecutionOutcome.Retry(attempts = attempt, nextRetryAt = nextRetryAt)
    }

    /**
     * 任务被作废（记录已被删除）。与 [finishAsFailed] 的区别：
     * - 不调 `syncRecordStatus`：记录都没了，没有"处理状态"可同步；
     * - 落 `CANCELLED` 而不是 `FAILED`：这不是错误，补投逻辑也不该再捞它。
     */
    private suspend fun finishAsCancelled(job: ProcessingJob, now: Long, reason: String) {
        jobRepository.updateStatus(
            job.copy(
                status = JobStatus.CANCELLED,
                finishedAt = now,
                nextRetryAt = null,
                errorCategory = reason,
            ),
        )
        Log.i(TAG, "任务作废 recordId=${job.recordId} type=${job.jobType} reason=$reason")
    }

    /**
     * 终态失败。
     *
     * @param expectedAttempts 非 null 时走 CAS（执行路径都必须传）：执行期间用户可能已点重试，
     *   无条件写 FAILED 会把刚重置的任务重新打成失败
     * @return false = 状态已被外部修改，本次失败结论作废
     */
    private suspend fun finishAsFailed(
        job: ProcessingJob,
        now: Long,
        reason: String,
        expectedAttempts: Int? = null,
    ): Boolean {
        val applied = persistOutcome(
            job.copy(
                status = JobStatus.FAILED,
                finishedAt = now,
                nextRetryAt = null,
                errorCategory = reason,
            ),
            expectedAttempts,
        )
        if (!applied) {
            Log.w(TAG, "终态失败被跳过（状态已被外部修改）recordId=${job.recordId} type=${job.jobType}")
            return false
        }
        syncRecordStatus(job.recordId, now)
        Log.w(TAG, "任务终态失败 recordId=${job.recordId} type=${job.jobType} reason=$reason")
        return true
    }

    /**
     * 记录状态 = 任务集合的聚合，交给 [RecordRepository.recomputeProcessingStatus] 在
     * SQL 里原子完成。`cloudActive` 取"当前策略是否允许这条记录上云"：它只影响
     * "全部任务已完成"这一档 —— 全部完成且云端可用 → DONE，否则 LOCAL_ONLY。
     */
    private suspend fun syncRecordStatus(recordId: String, now: Long) {
        val cloudActive = recordRepository.getById(recordId)?.let { record ->
            CloudUploadGate.blockReason(
                cloudAiEnabled = aiSettings.cloudAiEnabled,
                gatewayConfigured = gatewaySettings.isConfigured,
                record = recordRepository.gateRecord(record),
            ) == null
        } ?: false
        recordRepository.recomputeProcessingStatus(recordId, cloudActive, now)
    }

    /**
     * 异常 → 可重试 / 确定性失败。
     *
     * 只把 IO（网络、磁盘）当作可重试：解析失败、格式不支持、参数错误重试多少次都一样，
     * 让它们重试只是白白耗电。
     */
    private fun classify(e: Exception): ProcessorResult = when (e) {
        // 限流单独一类：等待但不消耗重试次数（见 ProcessorResult.RateLimited）
        is GatewayRateLimitedException ->
            ProcessorResult.RateLimited(e.javaClass.simpleName, e.retryAfterSeconds)
        is IOException -> ProcessorResult.Retryable(e.javaClass.simpleName)
        else -> ProcessorResult.Permanent(e.javaClass.simpleName)
    }

    /**
     * 指数退避：30s、60s、120s…
     *
     * **实际能走多远**（2026-10-24 全项目审查 L-2 把边界写清楚，避免常量之间看着自相矛盾）：
     * - 任务重试：`attempts` 最大 3 → 30s / 60s / 120s；
     * - 等上游：`wait_rounds` 最大 5（`ProcessingJob.MAX_WAIT_ROUNDS`）→ 最多 480s。
     *
     * 因此 [MAX_BACKOFF_MS]（1 小时）**在当前调用序列里永远碰不到**，它是给异常入参兜底的业务上限；
     * [MAX_BACKOFF_SHIFT] 则是防止 `attempt` 离谱时 `shl` 溢出成负数（Long 的 shl 只取低 6 位，
     * 移 63 位会得到负值，`coerceAtMost` 拦不住）。两个上限管的是两件事，都不能删。
     *
     * 与 WorkManager 自身的 backoff 是两层保险：WorkManager 决定"多久后再叫 Worker"，
     * 这里写入的 `nextRetryAt` 是给界面和排查用的可读事实（"下次重试时间"）。
     */
    internal fun backoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        return (INITIAL_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private companion object {
        const val TAG = "ProcessingJob"
        const val INITIAL_BACKOFF_MS = 30_000L

        /** 业务上限（1 小时）。当前调用序列最大 480s，见 [backoffMillis]。 */
        const val MAX_BACKOFF_MS = 60 * 60 * 1000L

        /** 位移上限（= 30s × 128 ≈ 64 分钟），只为防 `shl` 溢出，不是业务数值。 */
        const val MAX_BACKOFF_SHIFT = 7

        /** 记录在发请求前被删掉时的拦截原因（复核 F-9）。 */
        const val REASON_RECORD_GONE = "记录已被删除，内容未上传"

        /** 限流退避下限：即便服务端说 0 秒，也不立刻重发（避免把限流刷成雪崩）。 */
        const val MIN_RATE_LIMIT_DELAY_MS = 5_000L

        /** 限流退避上限：防服务端给出离谱值把任务永久挂起。 */
        const val MAX_RATE_LIMIT_DELAY_MS = 15 * 60 * 1000L
    }
}
