package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.CollectionMemberEntity
import com.zhicun.app.domain.model.CollectionMember

fun CollectionMemberEntity.toDomain(): CollectionMember = CollectionMember(
    collectionId = collectionId,
    recordId = recordId,
    position = position,
    addedAt = addedAt,
    snapshotFingerprint = snapshotFingerprint,
)
