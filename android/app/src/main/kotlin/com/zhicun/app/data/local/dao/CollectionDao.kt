package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.CollectionMemberEntity

/**
 * 合集成员关系（M20）。
 *
 * 查询成员时**不过滤** `records.is_deleted` / `archived_at`：
 * 已删除来源要保留展示并打标，由上层根据记录字段决定怎么呈现。
 */
@Dao
interface CollectionDao {

    /** 按合集内阅读顺序读取成员关系（不联表，成员内容由上层按 id 读取）。 */
    @Query("SELECT * FROM collection_members WHERE collection_id = :collectionId ORDER BY position ASC")
    suspend fun getMembers(collectionId: String): List<CollectionMemberEntity>

    /** 反查某条记录属于哪些合集（成员内容/敏感状态变化时刷新合集）。 */
    @Query("SELECT * FROM collection_members WHERE record_id = :recordId")
    suspend fun getMemberships(recordId: String): List<CollectionMemberEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(members: List<CollectionMemberEntity>)

    /** 生成成功后更新单个成员的内容指纹。 */
    @Query(
        "UPDATE collection_members SET snapshot_fingerprint = :fingerprint " +
            "WHERE collection_id = :collectionId AND record_id = :recordId",
    )
    suspend fun updateSnapshot(collectionId: String, recordId: String, fingerprint: String)

    @Query("DELETE FROM collection_members WHERE collection_id = :collectionId")
    suspend fun deleteByCollection(collectionId: String)
}
