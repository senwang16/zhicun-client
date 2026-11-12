package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.domain.model.Tag

/** [TagEntity] ↔ [Tag] 映射。用户标签的 `confidence` 恒为 null。 */
fun TagEntity.toDomain(): Tag = Tag(
    id = id,
    name = name,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id,
    name = name,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
)
