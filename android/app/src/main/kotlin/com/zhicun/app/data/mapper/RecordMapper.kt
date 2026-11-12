package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.Record

/** [RecordEntity] ↔ [Record] 映射。可空字段保持原样，不做默认值填充（避免伪造用户数据）。 */
fun RecordEntity.toDomain(): Record = Record(
    id = id,
    type = type,
    captureSource = captureSource,
    rawText = rawText,
    sourceUrl = sourceUrl,
    sourceTitle = sourceTitle,
    sourceApp = sourceApp,
    captureStartedAt = captureStartedAt,
    captureEndedAt = captureEndedAt,
    captureDurationMs = captureDurationMs,
    createdAt = createdAt,
    updatedAt = updatedAt,
    localStatus = localStatus,
    processingStatus = processingStatus,
    sensitivityStatus = sensitivityStatus,
    recordRevision = recordRevision,
    isDeleted = isDeleted,
    isFavorite = isFavorite,
    isPinned = isPinned,
    archivedAt = archivedAt,
    cloudConfirmedRevision = cloudConfirmedRevision,
    cloudConfirmedScope = ContentPart.decode(cloudConfirmedScope),
    webFetchConfirmedRevision = webFetchConfirmedRevision,
)

fun Record.toEntity(): RecordEntity = RecordEntity(
    id = id,
    type = type,
    captureSource = captureSource,
    rawText = rawText,
    sourceUrl = sourceUrl,
    sourceTitle = sourceTitle,
    sourceApp = sourceApp,
    captureStartedAt = captureStartedAt,
    captureEndedAt = captureEndedAt,
    captureDurationMs = captureDurationMs,
    createdAt = createdAt,
    updatedAt = updatedAt,
    localStatus = localStatus,
    processingStatus = processingStatus,
    sensitivityStatus = sensitivityStatus,
    recordRevision = recordRevision,
    isDeleted = isDeleted,
    isFavorite = isFavorite,
    isPinned = isPinned,
    archivedAt = archivedAt,
    cloudConfirmedRevision = cloudConfirmedRevision,
    cloudConfirmedScope = ContentPart.encode(cloudConfirmedScope).takeIf { it.isNotEmpty() },
    webFetchConfirmedRevision = webFetchConfirmedRevision,
)
