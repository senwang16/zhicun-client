package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.ProcessingJobEntity
import com.zhicun.app.domain.model.ProcessingJob

/** [ProcessingJobEntity] ↔ [ProcessingJob] 映射。队列中/未开始的任务时间戳为 null。 */
fun ProcessingJobEntity.toDomain(): ProcessingJob = ProcessingJob(
    id = id,
    recordId = recordId,
    jobType = jobType,
    status = status,
    attempts = attempts,
    maxAttempts = maxAttempts,
    waitRounds = waitRounds,
    nextRetryAt = nextRetryAt,
    errorCategory = errorCategory,
    startedAt = startedAt,
    finishedAt = finishedAt,
)

fun ProcessingJob.toEntity(): ProcessingJobEntity = ProcessingJobEntity(
    id = id,
    recordId = recordId,
    jobType = jobType,
    status = status,
    attempts = attempts,
    maxAttempts = maxAttempts,
    waitRounds = waitRounds,
    nextRetryAt = nextRetryAt,
    errorCategory = errorCategory,
    startedAt = startedAt,
    finishedAt = finishedAt,
)
