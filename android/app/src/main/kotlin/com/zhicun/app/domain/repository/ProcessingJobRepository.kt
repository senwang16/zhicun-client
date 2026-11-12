package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob

/**
 * 后台任务仓储。
 *
 * `recordId + jobType` 唯一（ARCHITECTURE §6.4），因此 [insert] 对重复提交会抛异常，
 * 调用方（M6 的调度逻辑）应先 [getByRecordAndType] 判重再决定插入还是更新。
 */
interface ProcessingJobRepository {

    suspend fun insert(job: ProcessingJob)

    suspend fun getByRecordAndType(recordId: String, jobType: JobType): ProcessingJob?

    /**
     * 按状态批量取任务（M6 审核整改：启动时补投用）。
     *
     * 单独开这个查询而不是"取全部再过滤"：任务表会随记录增长，
     * 启动路径只该读它真正要的那几行。
     */
    suspend fun getByStatuses(statuses: List<JobStatus>): List<ProcessingJob>

    suspend fun updateStatus(job: ProcessingJob)

    /**
     * 条件更新：任务仍是 RUNNING 且 attempts 等于 [expectedAttempts] 时才写入。
     *
     * @return false = 状态已被别人改过（用户点了重试 / 记录被删），调用方不应覆盖
     */
    suspend fun updateStatusIfRunning(job: ProcessingJob, expectedAttempts: Int): Boolean
}
