package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.Record

/**
 * 数据维护（M15）：只做"导出"与"清理"需要的两件事。
 *
 * ## 为什么不复用 RecordRepository
 * 清理要在**一个事务**里删掉一条记录以及它的附件、派生内容、任务、标签关联，
 * 还要保证"只删已标记删除的记录"。这是数据库维护语义，不是记录聚合的行为，
 * 挂在 `RecordRepository` 上会让那个接口承担两套职责（还会给所有使用方多出
 * 一批永远不该调的删除方法）。
 */
interface MaintenanceRepository {

    /**
     * 所有被标记删除、但数据还在的记录。
     *
     * 界面平时看不到它们（列表查询过滤了 `isDeleted`），但文件仍占着磁盘 ——
     * 这正是"清理"要处理的那批。
     */
    suspend fun getSoftDeletedRecords(): List<Record>

    /**
     * 连同附件行、派生内容、任务、标签关联一起永久删除。
     *
     * @return true = 真的删了；false = 记录不存在，**或未标记删除**（护栏，
     *   防止误把正常记录连根删掉，那对用户是不可恢复的数据损失）。
     */
    suspend fun purgeRecord(recordId: String): Boolean
}
