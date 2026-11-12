package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zhicun.app.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史读写（M21）。
 *
 * ## 为什么不用 `INSERT ... ON CONFLICT DO UPDATE`
 * SQLite 的 upsert 语法要 3.24+，而 minSdk 26（Android 8.0）系统自带 SQLite 只有 3.18 左右。
 * 在低版本设备上这条 SQL 会在运行时直接报语法错误——Room 编译期不会替我们挡住。
 * 因此拆成「先 UPDATE，未命中再 INSERT」，两步都是 SQLite 3.18 就有的语法。
 *
 * ## 为什么必须是 `abstract class` 而不是 `interface`
 * 三步写入（touch / insert / trim）必须在一个事务里完成：协程可能在任意挂起点被取消，
 * 拆成三条独立语句时会出现"计数 +1 但时间戳没更新"或"新词没落库却先裁掉旧条目"。
 * Room 只对**非 abstract 方法**上的 `@Transaction` 生成事务包装，Kotlin 接口的默认方法
 * 在多平台/不同 KSP 版本下行为并不一致，因此这里用抽象类 + `open fun`。
 */
@Dao
abstract class SearchHistoryDao {

    /** 命中则更新展示串并累加计数；返回受影响行数（0 = 这一条还不存在）。 */
    @Query(
        """
        UPDATE search_history
           SET query = :query,
               last_searched_at = :now,
               search_count = search_count + 1
         WHERE query_key = :queryKey
        """,
    )
    abstract suspend fun touch(queryKey: String, query: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entry: SearchHistoryEntity): Long

    @Query("SELECT * FROM search_history ORDER BY last_searched_at DESC, search_count DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE query_key = :queryKey")
    abstract suspend fun deleteByKey(queryKey: String)

    @Query("DELETE FROM search_history")
    abstract suspend fun clear()

    /** 只保留最近 [keep] 条；其余按时间倒序裁掉（个人知识库量级，一次 DELETE 足够）。 */
    @Query(
        """
        DELETE FROM search_history
         WHERE query_key NOT IN (
             SELECT query_key FROM search_history
              ORDER BY last_searched_at DESC, search_count DESC
              LIMIT :keep
         )
        """,
    )
    abstract suspend fun trim(keep: Int)

    /**
     * 一次搜索的完整写入（2026-11-07 审核 P2-6）：命中则累加，未命中则插入，最后裁剪。
     *
     * 整体在一个事务里：被取消时要么全做、要么全不做，不会留下半截状态。
     */
    @Transaction
    open suspend fun record(queryKey: String, query: String, now: Long, keep: Int) {
        val updated = touch(queryKey, query, now)
        if (updated == 0) {
            insert(
                SearchHistoryEntity(
                    queryKey = queryKey,
                    query = query,
                    lastSearchedAt = now,
                    searchCount = 1,
                ),
            )
        }
        trim(keep)
    }
}