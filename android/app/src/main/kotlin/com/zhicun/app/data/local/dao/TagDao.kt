package com.zhicun.app.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.domain.model.TagSource
import kotlinx.coroutines.flow.Flow

/** 标签表读写（M11 消费，M2 仅建能力）。 */
@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name = :name AND source = :source LIMIT 1")
    suspend fun getByNameAndSource(name: String, source: TagSource): TagEntity?

    /**
     * 某条记录的标签，按"挂到该记录上的先后"排序。
     *
     * 为什么不是 `ORDER BY t.created_at`：标签行是**跨记录复用**的（同名同源只有一行），
     * 所以 `tags.created_at` 是"这个标签第一次被创建"的时间，与本记录无关；
     * 而一批 AI 标签是在同一时刻写入的，`created_at` 全部相同 → 排序退化成随机，
     * AI 按相关度返回的顺序会被打乱。关联表的插入顺序（`rowid`）才是唯一的"每记录顺序"信号。
     */
    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN record_tag_cross_ref r ON r.tag_id = t.id
        WHERE r.record_id = :recordId
        ORDER BY r.rowid ASC
        """,
    )
    suspend fun getByRecordId(recordId: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachCrossRef(ref: RecordTagCrossRef)

    /**
     * 解除一条记录与某个标签的关联（M16）。
     *
     * 只删关联行，**不删 `tags` 表里的标签行**：同名标签可能还被别的记录挂着。
     */
    @Query("DELETE FROM record_tag_cross_ref WHERE record_id = :recordId AND tag_id = :tagId")
    suspend fun detachCrossRef(recordId: String, tagId: String): Int

    /** 重新生成 AI 标签前清空关联，避免旧标签残留（用户标签不受影响）。 */
    @Query(
        """
        DELETE FROM record_tag_cross_ref
        WHERE record_id = :recordId
          AND tag_id IN (SELECT id FROM tags WHERE source = :source)
        """,
    )
    suspend fun detachByRecordIdAndSource(recordId: String, source: TagSource)

    /**
     * 全量"记录-标签"（M11 收件箱卡片用）。
     *
     * 一次查回所有关联而不是"每张卡查一次"：列表滚动时 N+1 次查询会让首屏明显变慢，
     * 而且每次滚动都可能重查。个人知识库的标签总量很小，全量取回并不过分。
     */
    @Query(
        """
        SELECT t.*, x.record_id AS record_id
        FROM tags t
        INNER JOIN record_tag_cross_ref x ON x.tag_id = t.id
        ORDER BY x.rowid ASC
        """,
    )
    fun observeAllWithRecord(): Flow<List<TagWithRecordEntity>>

    /**
     * 标签使用概览（M21 搜索页"标签"区块）。
     *
     * ## 为什么 JOIN `records`
     * 现有 [observeAllWithRecord] 只按关联表取标签，不关心记录是否还可见；直接拿它
     * 聚合会把"已删除/已归档记录上的标签"也算进使用次数，点进去却搜不到东西。
     *
     * ## 为什么 `GROUP BY t.name`
     * AI 与用户可能各自建了一个同名标签（`(name, source)` 唯一）。对用户而言这是
     * 同一个分类，必须合并成一条；`COUNT(DISTINCT x.record_id)` 保证同一条记录
     * 同时挂 AI 与用户两个同名标签时只算一次。
     */
    @Query(
        """
        SELECT t.name AS name, COUNT(DISTINCT x.record_id) AS record_count
        FROM tags t
        INNER JOIN record_tag_cross_ref x ON x.tag_id = t.id
        INNER JOIN records r ON r.id = x.record_id
        WHERE r.is_deleted = 0 AND r.archived_at IS NULL
        GROUP BY t.name
        ORDER BY record_count DESC, t.name ASC
        LIMIT :limit
        """,
    )
    fun observeTagUsage(limit: Int): Flow<List<TagUsageRow>>

    /**
     * 按记录 id 批量取标签（M21 决策 3：结果页"结果标签"）。
     *
     * 一次取回整批结果的标签，而不是每张卡查一次：结果页最多 50 条，
     * N+1 查询在滚动或两段式更新时会被放大。
     */
    @Query(
        """
        SELECT t.*, r.record_id AS record_id
        FROM tags t
        INNER JOIN record_tag_cross_ref r ON r.tag_id = t.id
        WHERE r.record_id IN (:recordIds)
        ORDER BY r.rowid ASC
        """,
    )
    suspend fun getForRecords(recordIds: List<String>): List<TagWithRecordEntity>
}

/** 标签名 + 去重后的记录数（M21）。 */
data class TagUsageRow(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "record_count") val recordCount: Int,
)

/**
 * `tags` 行 + 关联的记录 id。
 *
 * 用 `@Embedded` 而不是再定义一套字段：标签表的列一旦变化（例如以后加 `weight`），
 * 这里不需要跟着改；`record_id` 是 JOIN 时显式取的别名列。
 */
data class TagWithRecordEntity(
    @Embedded val tag: TagEntity,
    @ColumnInfo(name = "record_id") val recordId: String,
)
