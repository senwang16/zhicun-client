package com.zhicun.app.domain.model

/**
 * 后台处理任务（Domain 模型，对应 ARCHITECTURE.md §4.4 / PRD §9.5）。
 *
 * 幂等约定见 ARCHITECTURE.md §6.4：`recordId + jobType` 唯一确定一个任务，
 * Worker 执行前先读状态，已 DONE 则直接返回 success，不重复处理。
 */
data class ProcessingJob(
    val id: String,
    val recordId: String,
    val jobType: JobType,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * 已经为"等上游产出正文"重排过几次（2026-10-23 审查 R-4）。
     *
     * 等待**不消耗** [attempts]（转写可能长达 120 秒，按普通失败计数 3 次就被判死了，
     * 而它根本没出错）。代价是没有上限：上游若卡在 `QUEUED`，摘要会一轮接一轮地醒过来、
     * 永远醒下去，每次都写库并触发 FTS 重建。
     *
     * 因此单独记一个轮次，超过 [MAX_WAIT_ROUNDS] 就按失败收尾，让界面上出现可操作的重试。
     * 字段存在数据库里而不是内存里：进程被杀正是"上游永远不推进"的典型原因，
     * 计数跟着进程一起丢就失去意义了。
     *
     * **用户显式重试 / 确认 / 开关补投时必须复位成 0**（2026-10-23 复核 F-2）：
     * 否则等待超时后点"重新生成"会在第一轮就 `rounds=22 > 20` 立即再失败，重试完全无效。
     * 自动补投（`RecoverPendingJobsUseCase`）刻意**不**复位 —— 上限要能跨进程重启生效。
     */
    val waitRounds: Int = 0,
    val nextRetryAt: Long? = null,
    val errorCategory: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
) {
    companion object {
        /** 默认最大尝试次数（PRD §9.5 / M6 重试策略）。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /**
         * 等待上游的最多轮次（2026-10-23 审查 R-4，复核 F-2 改为 5）。
         *
         * 退避由 `WorkManager` 的 `BackoffPolicy.EXPONENTIAL` 决定：30s、60s、120s、240s、480s，
         * 五轮合计约 **15 分钟**。正常转写 1~2 分钟就能产出正文，15 分钟还等不到，
         * 说明上游出了别的问题，继续等只会耗电而不会变好。
         *
         * 原来定 20 轮，是误以为间隔固定 30 秒（那才是 10 分钟）；按指数退避算，
         * 20 轮的实际跨度是**几十小时**，等于没有上限。
         */
        const val MAX_WAIT_ROUNDS: Int = 5
    }
}

/** 可调度的后台任务类型。 */
enum class JobType {
    WEBPAGE_EXTRACT,
    OCR,
    TRANSCRIPTION,
    SUMMARIZE,
    EMBED,
}

/** 任务状态。重试耗尽后进入 FAILED，记录本身不受影响。 */
enum class JobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED,
}
