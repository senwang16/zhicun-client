package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob

/**
 * 后台任务表（对应 ARCHITECTURE.md §4.4）。
 *
 * 幂等性的数据库级保证：`(record_id, job_type)` 唯一索引 —— 同一记录的同类型任务永远只有一行，
 * Worker 重复提交只能命中已有行，不会产生第二条任务（ARCHITECTURE.md §6.4）。
 * 需要"重新跑一次"时更新已有行（attempts 递增、status 回到 QUEUED），而不是插入新行。
 */
@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["record_id", "job_type"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ProcessingJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "job_type")
    val jobType: JobType,

    @ColumnInfo(name = "status")
    val status: JobStatus,

    @ColumnInfo(name = "attempts")
    val attempts: Int,

    @ColumnInfo(name = "max_attempts")
    val maxAttempts: Int = ProcessingJob.DEFAULT_MAX_ATTEMPTS,

    /** 等上游的重排轮次（2026-10-23 审查 R-4）；见 `ProcessingJob.waitRounds`。 */
    @ColumnInfo(name = "wait_rounds", defaultValue = "0")
    val waitRounds: Int = 0,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long?,

    @ColumnInfo(name = "error_category")
    val errorCategory: String?,

    @ColumnInfo(name = "started_at")
    val startedAt: Long?,

    @ColumnInfo(name = "finished_at")
    val finishedAt: Long?,
)
