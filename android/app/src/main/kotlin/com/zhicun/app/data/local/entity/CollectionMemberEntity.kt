package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 合集与成员的关联表（M20，数据库 v7）。
 *
 * 只存关系、阅读顺序与生成快照，**不复制**成员内容：成员原记录被修改后，
 * 合集详情页读到的是最新内容；合集摘要本身仍是生成那一刻的快照。
 *
 * 复合主键 `(collection_id, record_id)` 保证同一成员不会重复加入同一合集。
 * 两个外键都是 `ON DELETE CASCADE`：
 * - 删除合集 → 成员关系消失，成员原记录不受影响；
 * - M15 物理清理成员 → 关系随之消失（软删除不触发级联，所以"已删除来源"仍能展示）。
 */
@Entity(
    tableName = "collection_members",
    primaryKeys = ["collection_id", "record_id"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // 反查"某条记录属于哪些合集"（成员内容变化时要刷新这些合集）
        Index(value = ["record_id"]),
        // 详情页按 position 顺序读取成员
        Index(value = ["collection_id", "position"]),
    ],
)
data class CollectionMemberEntity(
    @ColumnInfo(name = "collection_id")
    val collectionId: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    /** 合集内阅读顺序，从 0 开始（按成员 createdAt 升序）。 */
    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "added_at")
    val addedAt: Long,

    /**
     * 上次成功生成合集摘要时该成员的内容指纹；null = 从未参与过生成。
     *
     * 内容指纹由 `CollectionMemberFingerprint` 统一计算（recordRevision + 删除/归档 +
     * 正文类派生内容最新 processedAt），**不含收藏/置顶** —— 那两个动作只推
     * `updated_at`，不改变内容，不该让合集被判为过期。
     */
    @ColumnInfo(name = "snapshot_fingerprint")
    val snapshotFingerprint: String? = null,
)
