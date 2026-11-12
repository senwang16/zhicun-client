package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.ProcessingJobDao
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.repository.ProcessingJobRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [ProcessingJobRepository] 的 Room 实现。 */
@Singleton
class ProcessingJobRepositoryImpl @Inject constructor(
    private val processingJobDao: ProcessingJobDao,
) : ProcessingJobRepository {

    override suspend fun insert(job: ProcessingJob) = processingJobDao.insert(job.toEntity())

    override suspend fun getByRecordAndType(recordId: String, jobType: JobType): ProcessingJob? =
        processingJobDao.getByRecordAndType(recordId, jobType)?.toDomain()

    override suspend fun getByStatuses(statuses: List<JobStatus>): List<ProcessingJob> {
        // 空列表会让 SQL 变成 `IN ()`（语法错误），直接短路返回
        if (statuses.isEmpty()) return emptyList()
        // 只取已知 job_type（2026-11-10 Bug 4）：未知类型无法映射成 Worker（见 Converters.toJobType），
        // 让它在查询层就被排除，而不是把整条恢复链拖挂
        return processingJobDao.getByStatuses(statuses, JobType.entries.toList())
            .map { it.toDomain() }
    }

    override suspend fun updateStatus(job: ProcessingJob) = processingJobDao.updateStatus(
        id = job.id,
        status = job.status,
        attempts = job.attempts,
        // 轮次必须跟着状态一起落库（2026-11-05 审核 CN-01）：漏传会让等上游的上限永远读 0
        waitRounds = job.waitRounds,
        nextRetryAt = job.nextRetryAt,
        errorCategory = job.errorCategory,
        startedAt = job.startedAt,
        finishedAt = job.finishedAt,
    )

    override suspend fun updateStatusIfRunning(job: ProcessingJob, expectedAttempts: Int): Boolean =
        processingJobDao.updateStatusIfRunning(
            id = job.id,
            status = job.status,
            attempts = job.attempts,
            waitRounds = job.waitRounds,
            nextRetryAt = job.nextRetryAt,
            errorCategory = job.errorCategory,
            startedAt = job.startedAt,
            finishedAt = job.finishedAt,
            expectedAttempts = expectedAttempts,
        ) > 0
}
