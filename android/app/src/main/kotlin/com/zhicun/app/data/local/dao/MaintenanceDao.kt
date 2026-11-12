package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.zhicun.app.data.local.entity.RecordEntity

/**
 * 数据维护（M15）：清理标记删除的记录。
 *
 * ## 为什么单开一个 DAO
 * 这些语句全是**物理删除**，是其它模块永远不该调的东西。混进 `RecordDao` /
 * `AssetDao` 会让"误调一次"变得太容易 —— 而这个 App 里没有云端备份，
 * 删错就是不可恢复。集中在这里，谁想用都得显式引一个语义明确的东西。
 *
 * ## 为什么删除条件里带 `is_deleted = 1`
 * 护栏写在 SQL 里，而不是依赖调用方先查一遍。少一层判空就少一个"顺手删掉正常数据"的机会：
 * 记录不存在或未标记删除时这条语句影响 0 行，[purgeRecord] 直接返回 false、
 * 后面的子表删除全部不执行。
 */
@Dao
interface MaintenanceDao {

    /** 已标记删除、但数据仍在的记录（界面看不到，磁盘还占着）。 */
    @Query("SELECT * FROM records WHERE is_deleted = 1 ORDER BY updated_at ASC")
    suspend fun getSoftDeletedRecords(): List<RecordEntity>

    @Query("DELETE FROM records WHERE id = :recordId AND is_deleted = 1")
    suspend fun deleteRecordRow(recordId: String): Int

    @Query("DELETE FROM assets WHERE record_id = :recordId")
    suspend fun deleteAssets(recordId: String): Int

    @Query("DELETE FROM derived_contents WHERE record_id = :recordId")
    suspend fun deleteDerivedContents(recordId: String): Int

    @Query("DELETE FROM processing_jobs WHERE record_id = :recordId")
    suspend fun deleteProcessingJobs(recordId: String): Int

    @Query("DELETE FROM record_tag_cross_ref WHERE record_id = :recordId")
    suspend fun deleteTagLinks(recordId: String): Int

    /**
     * 一条记录连同它的全部附属行。
     *
     * 事务保证"要么全删、要么全不删"：删到一半失败会留下没有记录的孤儿附件行，
     * 既不显示也无从清理。
     *
     * @return false = 记录不存在或未标记删除，什么都没删
     */
    @Transaction
    suspend fun purgeRecord(recordId: String): Boolean {
        if (deleteRecordRow(recordId) == 0) return false
        deleteAssets(recordId)
        deleteDerivedContents(recordId)
        deleteProcessingJobs(recordId)
        deleteTagLinks(recordId)
        return true
    }
}
