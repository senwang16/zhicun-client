package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 搜索历史（M21，2026-11-06 "搜索优化"）。
 *
 * ## 为什么用 Room 而不是 SharedPreferences
 * 历史需要"按时间倒序取前 N 条 + 去重 + 超限裁剪"三个操作，SQL 一句话能表达；
 * 而项目已把 Room 定为本地单一真源（ARCHITECTURE §6.3），再开一套 JSON 存储
 * 会让"清空/删除"多一条容易漏的路径。
 *
 * ## query_key 与 query 分开
 * `query_key` 是归一化后的**小写**串，只用于去重（"Compose" 与 "compose" 算同一条）；
 * `query` 保留用户最近一次的原始写法用于展示。中文没有大小写，这个差异只影响英文查询。
 *
 * ## 隐私
 * 搜索词属于用户内容：只存本机、不上传、不写日志，也不进入导出包。
 */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["last_searched_at"])],
)
data class SearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "query_key")
    val queryKey: String,

    @ColumnInfo(name = "query")
    val query: String,

    @ColumnInfo(name = "last_searched_at")
    val lastSearchedAt: Long,

    @ColumnInfo(name = "search_count")
    val searchCount: Int,
)