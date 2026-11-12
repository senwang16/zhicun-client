package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhicun.app.domain.model.DerivedType

/**
 * 派生内容表（对应 ARCHITECTURE.md §4.3）。
 *
 * 与 records 严格分表：派生数据可重建、可覆盖，原始内容不可被覆盖。
 * 同一 `record_id + content_type + asset_id` 只保留最新一条结果（由 DAO 的 REPLACE 策略保证）；
 * `asset_id` 为空串时是记录级结果（摘要、向量等），非空时是该附件自己的识别/转写结果。
 */
@Entity(
    tableName = "derived_contents",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["record_id", "content_type", "asset_id"], unique = true),
    ],
)
data class DerivedContentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "content_type")
    val contentType: DerivedType,

    @ColumnInfo(name = "content")
    val content: String?,

    /** 处理链路版本号，便于识别旧结果、按需重跑。 */
    @ColumnInfo(name = "processor_version")
    val processorVersion: String,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long,

    @ColumnInfo(name = "error_message")
    val errorMessage: String?,

    /**
     * 附件 id；空串表示记录级派生内容。
     *
     * 用空串而不是 NULL：SQLite 的唯一索引不把 NULL 视为相等，
     * 若记录级行存 NULL，`(record_id, content_type, asset_id)` 这个唯一索引就挡不住
     * 同一条记录出现两条摘要。空串让「记录级一种类型一条」继续由数据库兜底。
     */
    @ColumnInfo(name = "asset_id", defaultValue = "''")
    val assetId: String = "",
)
