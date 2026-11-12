package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhicun.app.domain.model.AssetSaveStatus

/**
 * 附件表（对应 ARCHITECTURE.md §4.2）。
 *
 * 一条记录可有多个附件；记录被物理删除时级联删除附件行（软删除不触发级联，
 * 由 M15 的清理流程负责删除文件后再物理删除）。
 */
@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["record_id"]),
        Index(value = ["record_id", "order_index"]),
    ],
)
data class AssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** App 私有目录绝对路径，禁止存相对路径（AGENTS.md §3）。 */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    /** SHA-256，仅用于避免重复处理，不用于自动去重记录（PRD §11）。 */
    @ColumnInfo(name = "file_hash")
    val fileHash: String?,

    /** 外部来源 URI，仅作追溯，不依赖其持续可用。 */
    @ColumnInfo(name = "original_uri")
    val originalUri: String?,

    @ColumnInfo(name = "save_status")
    val saveStatus: AssetSaveStatus,

    /** 系统给的原始文件名，仅用于展示；磁盘文件名仍是清洗后的版本（见 localPath）。 */
    @ColumnInfo(name = "file_name")
    val fileName: String? = null,

    /**
     * 保存时的选择顺序，0 起。
     *
     * v8 之前存量的附件全是 0：迁移无法恢复当时的顺序（旧代码按随机 UUID 排），
     * 只能退化成 `ORDER BY order_index, id`，至少保证同一台设备上的顺序稳定。
     * 默认值必须与迁移 SQL 里的 `DEFAULT 0` 一致，否则 Room 结构校验会失败。
     */
    @ColumnInfo(name = "order_index", defaultValue = "0")
    val orderIndex: Int = 0,
)
