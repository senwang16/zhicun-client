package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 记录与标签的多对多关联表（PRD §9.4）。
 *
 * 复合主键 (record_id, tag_id) 保证同一记录不会重复关联同一标签。
 */
@Entity(
    tableName = "record_tag_cross_ref",
    primaryKeys = ["record_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"])],
)
data class RecordTagCrossRef(
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "tag_id")
    val tagId: String,
)
