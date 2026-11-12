package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.AssetEntity
import com.zhicun.app.domain.model.Asset

/** [AssetEntity] ↔ [Asset] 映射。`fileHash` / `originalUri` / `fileName` 允许为空。 */
fun AssetEntity.toDomain(): Asset = Asset(
    id = id,
    recordId = recordId,
    mimeType = mimeType,
    localPath = localPath,
    fileSize = fileSize,
    fileHash = fileHash,
    originalUri = originalUri,
    saveStatus = saveStatus,
    fileName = fileName,
    orderIndex = orderIndex,
)

fun Asset.toEntity(): AssetEntity = AssetEntity(
    id = id,
    recordId = recordId,
    mimeType = mimeType,
    localPath = localPath,
    fileSize = fileSize,
    fileHash = fileHash,
    originalUri = originalUri,
    saveStatus = saveStatus,
    fileName = fileName,
    orderIndex = orderIndex,
)
