package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.ProcessingJobEntity
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType

/** 后台任务表读写。 */
@Dao
interface ProcessingJobDao {

    /** 插入使用 ABORT：`(record_id, job_type)` 唯一索引会挡住幂等重复提交。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ProcessingJobEntity)

    @Query("SELECT * FROM processing_jobs WHERE record_id = :recordId AND job_type = :jobType LIMIT 1")
    suspend fun getByRecordAndType(recordId: String, jobType: JobType): ProcessingJobEntity?

    /**
     * 启动补投用：一次取回所有"未终结"的任务（M6 审核整改）。
     *
     * ## 为什么要带 `jobTypes`（2026-11-10 Bug 4）
     * `job_type` 由应用自己写，正常情况下不会出现未知值；但库里一旦有脏行
     * （手工 SQL、版本回退），读整批任务时枚举转换会抛异常，这条恢复链就整批失败 ——
     * 表现为"所有待处理任务都不再被补投"。把已知类型作为查询条件，
     * 让脏行**根本不进入**结果集，转换器保持严格（见 `Converters.toJobType`）。
     */
    @Query(
        "SELECT * FROM processing_jobs " +
            "WHERE status IN (:statuses) AND job_type IN (:jobTypes)",
    )
    suspend fun getByStatuses(
        statuses: List<JobStatus>,
        jobTypes: List<JobType>,
    ): List<ProcessingJobEntity>

    /**
     * 写回状态机字段。
     *
     * **`wait_rounds` 必须在这条 UPDATE 里**（2026-11-05 审核 CN-01）：执行器等上游时
     * 写回的是 `job.copy(waitRounds = rounds)`，SQL 漏掉这一列，库里的轮次就永远是 0。
     * 下一次唤醒读到的还是 0 ⇒ `rounds` 永远到不了 `MAX_WAIT_ROUNDS`，上游一旦卡住，
     * 任务会一轮接一轮地重排下去（每次写库 + 重建 FTS），耗电且界面永远"正在生成"。
     * 单测用的是内存假仓储（整对象覆盖），所以这条只有集成测试能钉住。
     */
    @Query(
        """
        UPDATE processing_jobs SET
            status = :status,
            attempts = :attempts,
            wait_rounds = :waitRounds,
            next_retry_at = :nextRetryAt,
            error_category = :errorCategory,
            started_at = :startedAt,
            finished_at = :finishedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: String,
        status: JobStatus,
        attempts: Int,
        /** 等上游的重排轮次（R-4）；漏写会让上限判定永远读到 0，任务无限排队。 */
        waitRounds: Int,
        nextRetryAt: Long?,
        errorCategory: String?,
        startedAt: Long?,
        finishedAt: Long?,
    )

    /**
     * 条件更新：只有任务**仍是 RUNNING 且 attempts 没变**时才写入（2026-11-05）。
     *
     * 为什么需要它：Worker 执行结束时用的是"执行开始那一刻"的任务快照。从取快照到回写
     * 之间，用户可能已经点了"重新生成"（把状态改成 QUEUED、attempts 清零），或者删了记录。
     * 无条件 UPDATE 会把用户刚重置的状态覆盖回去 —— 典型表现是"点了重试又跳回失败/处理中"。
     *
     * `wait_rounds` 同样必须写回（2026-11-05 审核 CN-01）：执行器的**所有**结果回写都走
     * 这条 CAS，漏掉它等于"等上游"的轮次永远不落库，`MAX_WAIT_ROUNDS` 形同虚设。
     *
     * @return 影响行数；0 = 状态已被别人改过，调用方**不应**再覆盖，也不该同步记录状态
     */
    @Query(
        """
        UPDATE processing_jobs SET
            status = :status,
            attempts = :attempts,
            wait_rounds = :waitRounds,
            next_retry_at = :nextRetryAt,
            error_category = :errorCategory,
            started_at = :startedAt,
            finished_at = :finishedAt
        WHERE id = :id AND status = 'RUNNING' AND attempts = :expectedAttempts
        """,
    )
    suspend fun updateStatusIfRunning(
        id: String,
        status: JobStatus,
        attempts: Int,
        waitRounds: Int,
        nextRetryAt: Long?,
        errorCategory: String?,
        startedAt: Long?,
        finishedAt: Long?,
        expectedAttempts: Int,
    ): Int
}
