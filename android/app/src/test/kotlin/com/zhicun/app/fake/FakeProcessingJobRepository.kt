package com.zhicun.app.fake

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.repository.ProcessingJobRepository

/**
 * 后台任务仓储假实现（M6）。
 *
 * 行为对齐真实实现的两个关键约束：`(recordId, jobType)` 唯一（重复插入抛异常）、
 * `updateStatus` 按 id 覆盖整行。
 */
class FakeProcessingJobRepository : ProcessingJobRepository {

    val jobs: MutableMap<String, ProcessingJob> = linkedMapOf()

    /** 置为非 null 时 [insert] 抛该异常，用于测"重复插入必须被挡住"。 */
    var insertFailure: Throwable? = null

    override suspend fun insert(job: ProcessingJob) {
        insertFailure?.let { throw it }
        val duplicate = jobs.values.any {
            it.recordId == job.recordId && it.jobType == job.jobType
        }
        require(!duplicate) { "(recordId, jobType) 已存在，唯一索引应挡住重复插入" }
        jobs[job.id] = job
    }

    override suspend fun getByRecordAndType(recordId: String, jobType: JobType): ProcessingJob? =
        jobs.values.firstOrNull { it.recordId == recordId && it.jobType == jobType }

    /** 与真实 DAO 的 `WHERE status IN (...)` 等价（M6 审核整改的启动补投依赖它）。 */
    override suspend fun getByStatuses(statuses: List<JobStatus>): List<ProcessingJob> =
        jobs.values.filter { it.status in statuses }

    override suspend fun updateStatus(job: ProcessingJob) {
        jobs[job.id] = job
    }

    /**
     * 与真实 DAO 的 `WHERE status='RUNNING' AND attempts=:expected` 等价。
     *
     * 假实现必须**真的**做这个判断，否则"用户重试后旧 Worker 覆盖状态"的回归
     * 在测试里永远发现不了 —— 假实现比生产代码宽松，是最常见的一类假绿。
     */
    override suspend fun updateStatusIfRunning(job: ProcessingJob, expectedAttempts: Int): Boolean {
        val current = jobs[job.id] ?: return false
        if (current.status != JobStatus.RUNNING || current.attempts != expectedAttempts) return false
        jobs[job.id] = job
        return true
    }
}
