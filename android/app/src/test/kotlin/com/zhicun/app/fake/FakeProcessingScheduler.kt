package com.zhicun.app.fake

import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.worker.ProcessingScheduler

/** 调度器假实现（M6）：只记录"提交了什么"，不真的碰 WorkManager。 */
class FakeProcessingScheduler : ProcessingScheduler {

    /** 按提交顺序记录 (recordId, jobType)。 */
    val enqueued: MutableList<Pair<String, JobType>> = mutableListOf()

    /**
     * 用户显式重试（REPLACE）的提交记录。
     *
     * 与 [enqueued] 分开记，才能断言"这次到底走的是保留还是替换"——
     * 两条路径底层都算"提交了任务"，只看 [enqueued] 区分不出来。
     */
    val reenqueued: MutableList<Pair<String, JobType>> = mutableListOf()

    /** 延迟重排记录：(recordId, jobType, delayMs)。限流路径断言"按 Retry-After 排期"。 */
    val reenqueuedAfter: MutableList<Triple<String, JobType, Long>> = mutableListOf()

    val cancelled: MutableList<Pair<String, JobType>> = mutableListOf()

    /**
     * 每次取消时回调。删除流程要断言"取消发生在清理之前"，
     * 光看 [cancelled] 这个集合是看不出相对顺序的。
     */
    var onCancel: ((Pair<String, JobType>) -> Unit)? = null

    /** 置为 true 时 [enqueue] 抛异常，用于测"调度失败不能让保存失败"。 */
    var failOnEnqueue: Boolean = false

    override fun enqueue(recordId: String, jobType: JobType) {
        if (failOnEnqueue) throw IllegalStateException("模拟 WorkManager 未初始化")
        enqueued += recordId to jobType
    }

    override fun reenqueue(recordId: String, jobType: JobType) {
        if (failOnEnqueue) throw IllegalStateException("模拟 WorkManager 未初始化")
        reenqueued += recordId to jobType
        enqueued += recordId to jobType
    }

    override fun reenqueueAfter(recordId: String, jobType: JobType, delayMs: Long) {
        if (failOnEnqueue) throw IllegalStateException("模拟 WorkManager 未初始化")
        reenqueuedAfter += Triple(recordId, jobType, delayMs)
        enqueued += recordId to jobType
    }

    override fun cancel(recordId: String, jobType: JobType) {
        val pair = recordId to jobType
        cancelled += pair
        onCancel?.invoke(pair)
    }
}
