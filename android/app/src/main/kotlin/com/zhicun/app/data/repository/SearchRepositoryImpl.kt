package com.zhicun.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.zhicun.app.data.local.FtsSyncCallback
import com.zhicun.app.data.local.dao.RecordDao
import com.zhicun.app.data.local.dao.RecordFtsDao
import com.zhicun.app.domain.repository.SearchRepository
import com.zhicun.app.domain.search.SearchQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SearchRepository] 的 Room 实现（M10）。
 *
 * ## 两条检索路径为什么都要跑
 * - **FTS4 MATCH**：ASCII 单词（`compose`、`retrofit`）走索引，支持前缀与多词 AND，
 *   是唯一能用上索引的路径；
 * - **子串 LIKE**：中文必须靠它。FTS4 的 `simple` 分词把整段连续汉字当一个 token，
 *   "断联陪伴"里搜"陪伴"零命中，详见 [SearchQuery] 类注释。
 *
 * 两条都跑，取并集。顺序是"FTS 命中在前，仅子串命中的在后"：
 * FTS 命中意味着词边界对得上（更像是用户想要的），子串命中包含"词在句中"的宽泛情况。
 * 超过 `limit` 时按这个优先级截断。M12 引入语义分会重排，届时这里只需多一路来源。
 *
 * ## 空查询
 * 归一化后为空（或只有空白）直接返回空列表，绝不退化成"全表返回" ——
 * 搜索框空着时的语义是"不搜索"，不是"列出所有记录"。
 */
@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val recordFtsDao: RecordFtsDao,
    private val recordDao: RecordDao,
) : SearchRepository {

    override suspend fun searchIds(normalized: String, limit: Int): List<String> {
        if (normalized.isBlank() || limit <= 0) return emptyList()

        val ftsIds = SearchQuery.ftsExpression(normalized)?.let { expression ->
            recordFtsDao.searchRecordIds(
                SimpleSQLiteQuery(
                    """
                    SELECT record_id FROM ${FtsSyncCallback.TABLE}
                    WHERE ${FtsSyncCallback.TABLE} MATCH ?
                    ORDER BY rowid DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf<Any?>(expression, limit),
                ),
            )
        }.orEmpty()

        // 纯 ASCII 查询时子串匹配仍要跑：FTS 只认词边界，"compose" 搜不到 "decomposed"，
        // 而用户预期是能搜到的（PRD §7 搜索是"找到我记过的东西"，不是"精确词表匹配"）。
        val substringIds = recordDao
            .searchBySubstring(SearchQuery.likePattern(normalized), limit)
            .map { it.id }

        return (ftsIds + substringIds).distinct().take(limit)
    }
}
