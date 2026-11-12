package com.zhicun.app.domain.worker

import com.zhicun.app.domain.model.JobType

/**
 * 把后台任务交给系统调度器（domain 契约，data 层用 WorkManager 实现）。
 *
 * 为什么要这层接口：`WorkManager` 是 Android 类型，UseCase 一旦直接引用它，
 * `ScheduleProcessingUseCase` 就再也无法在纯 JVM 下测试（AGENTS.md §2.3）。
 *
 * 调度语义要求：**同一 `recordId + jobType` 只能有一个在排队**（ARCHITECTURE §6.4 幂等）。
 */
interface ProcessingScheduler {

    /**
     * 系统自动调度：已有同名任务时**保留**它，不重复创建。
     *
     * 用于保存记录、冷启动补投这类"不是用户此刻发出的指令"的路径 ——
     * 保留下没有坏处，重复投递反而会让同一任务被执行两次。
     */
    fun enqueue(recordId: String, jobType: JobType)

    /**
     * 用户显式要求重新处理：**取消**同名任务（含正在运行 / 正在退避等待的），重新投递。
     *
     * 2026-11-05 修复：`enqueue` 底层是 `ExistingWorkPolicy.KEEP`，对"正在 RUNNING"
     * 或"退避中还没到点"的任务不替换。用户点"重新生成"时，如果那条任务正好卡在
     * 退避等待里，KEEP 会把新请求丢掉 —— 界面于是停在"处理中"，只能干等旧任务
     * 自己走完（实测出现过半小时）。用户的显式意图必须压过系统幂等策略。
     *
     * 默认实现退化为 [enqueue]：只影响没有真实队列的假实现，不会静默改变线上行为 ——
     * 真正的 WorkManager 实现必须 override 它。
     */
    fun reenqueue(recordId: String, jobType: JobType) {
        enqueue(recordId, jobType)
    }

    /**
     * 用户显式重试之外的"延迟重排"：按服务端要求等一段时间再执行。
     *
     * 目前唯一用途是 429 的 `Retry-After`（2026-11-05）。WorkManager 的
     * `Result.retry()` 只能走请求创建时定死的指数退避（30s 起），无法表达
     * "服务端说 120 秒后再来"；因此执行器把下一次执行排成一条**带 initialDelay
     * 的新任务**，Worker 本次直接返回 success，不再叠加 WorkManager 的退避。
     *
     * 默认实现忽略延迟、退化为 [reenqueue]：只影响没有真实队列的假实现。
     * 真正的 WorkManager 实现必须 override 它。
     */
    fun reenqueueAfter(recordId: String, jobType: JobType, delayMs: Long) {
        reenqueue(recordId, jobType)
    }

    /** 取消任务（记录被删除或用户关闭云端处理时使用）。 */
    fun cancel(recordId: String, jobType: JobType)
}

/**
 * 没有真实队列时的空实现（单测默认值）。
 *
 * 只用于 `ProcessingJobExecutor` 的手工构造路径；生产环境由 Hilt 注入
 * [com.zhicun.app.data.worker.WorkManagerProcessingScheduler]。
 */
object NoopProcessingScheduler : ProcessingScheduler {
    override fun enqueue(recordId: String, jobType: JobType) = Unit

    override fun cancel(recordId: String, jobType: JobType) = Unit
}
