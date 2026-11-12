package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 全文检索入口（M2 建能力，M10 完整实现）。
 *
 * FTS 虚表由 [com.zhicun.app.data.local.FtsSyncCallback] 用原生 SQL 创建
 * （要挂同步触发器，Room 注解方式做不到），因此只能用 @RawQuery 访问。
 * 查询返回命中的 recordId，再由 RecordDao 取实体。
 *
 * 安全约定：查询串必须由调用方用 [androidx.sqlite.db.SimpleSQLiteQuery]
 * 以**绑定参数**方式构造，禁止把用户输入直接拼进 SQL（FTS 语法注入）。
 */
@Dao
interface RecordFtsDao {

    /**
     * FTS4 MATCH 检索，返回命中的 recordId。
     *
     * 排序用 `rowid DESC`（新入库的在前）而不是"相关度"：FTS4 没有 `bm25()`
     * （那是 FTS5 的函数），没有可用分数。真正的排序由 M12 的语义检索负责，
     * 这里只要**确定性**，避免同一查询两次结果顺序不同。
     */
    @RawQuery
    suspend fun searchRecordIds(query: SupportSQLiteQuery): List<String>
}
