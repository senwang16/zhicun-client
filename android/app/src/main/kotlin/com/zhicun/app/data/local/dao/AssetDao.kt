package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.AssetEntity
import com.zhicun.app.domain.model.AssetSaveStatus

/** 附件表读写。 */
@Dao
interface AssetDao {

    /**
     * 同 id 覆盖整行（REPLACE）。外部导入的附件 id 是 UUID（永不冲突，等效纯插入）；
     * 网页快照（C）用确定性 id `<recordId>:snapshot` / `<recordId>:og_image`，
     * 任务重跑时靠它覆盖同一行——不能用 ABORT（重试会撞主键崩任务），
     * 也不能只 updateSaveStatus（mime/size/hash 都会留在旧值）。
     * REPLACE 会换 rowid：assets 表的 PK 是字符串 id，没有任何表按 rowid 引用附件，安全。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: AssetEntity)

    /**
     * 按用户选择顺序返回附件。
     *
     * v8 之前按随机 UUID 排序，多图时每次打开详情页顺序都可能变；现在以 `order_index`
     * 为准，仍带 `id` 兜底，保证老数据（order_index 全为 0）在任何设备上都稳定。
     */
    @Query("SELECT * FROM assets WHERE record_id = :recordId ORDER BY order_index ASC, id ASC")
    suspend fun getByRecordId(recordId: String): List<AssetEntity>

    @Query("UPDATE assets SET save_status = :status WHERE id = :id")
    suspend fun updateSaveStatus(id: String, status: AssetSaveStatus)

    /** 删除附件行（2026-10-29 删除单个附件；派生内容由调用方一并清理）。 */
    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 找出"图片 ≥ 2 或音频 ≥ 2"的记录（v8 升级后一次性重跑用）。
     *
     * 排除网页快照：`<recordId>:snapshot` / `<recordId>:og_image` 是抓取链路自己写的，
     * 一条网页记录天然带 2 个"附件"，若不排除，升级后会把所有网页记录当成多附件重抓一遍。
     * 判据是确定性 id 后缀，与 [com.zhicun.app.data.web.WebSnapshotStoreImpl] 的约定一致。
     */
    @Query(
        """
        SELECT record_id FROM assets
        WHERE id NOT LIKE '%:snapshot' AND id NOT LIKE '%:og_image'
        GROUP BY record_id
        HAVING SUM(CASE WHEN mime_type LIKE 'image/%' THEN 1 ELSE 0 END) >= 2
            OR SUM(CASE WHEN mime_type LIKE 'audio/%' THEN 1 ELSE 0 END) >= 2
        """,
    )
    suspend fun getRecordIdsWithMultipleMedia(): List<String>
}
