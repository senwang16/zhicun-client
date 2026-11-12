package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhicun.app.domain.model.TagSource

/**
 * 标签表（对应 PRD §9.4）。
 *
 * `name` 唯一：同一标签名不重复建行；AI 标签与用户标签的区分靠 `source`，
 * 因此同名标签若同时由用户与 AI 产生，会保留两条（source 不同）——
 * 这是刻意的，M11 要求"AI 重新处理不覆盖用户标签"。
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name", "source"], unique = true)],
)
data class TagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "source")
    val source: TagSource,

    /** 仅 AI 标签有置信度，用户标签为 null。 */
    @ColumnInfo(name = "confidence")
    val confidence: Double?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
