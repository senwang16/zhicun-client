package com.zhicun.app.fake

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.MaintenanceRepository
import kotlinx.coroutines.CompletableDeferred

/**
 * 数据维护仓储的假实现（M15）。
 *
 * 保留真实实现最关键的一条语义：**只删已标记删除的记录**。
 * 清理是不可恢复的动作，这个假实现不能比真实现更宽松 ——
 * 否则"顺手删掉正常数据"这类问题在测试里永远是绿的。
 *
 * @param softDeleted 已标记删除的记录（请用 `testRecord(isDeleted = true)` 构造）
 */
class FakeMaintenanceRepository(
    softDeleted: List<Record> = emptyList(),
) : MaintenanceRepository {

    val softDeletedRecords: MutableList<Record> = softDeleted.toMutableList()

    /** 这些 id 的 [purgeRecord] 返回 false：模拟护栏拦下正常记录、或记录已被别处删掉。 */
    val refusedIds: MutableSet<String> = mutableSetOf()

    /** 真正被清掉的记录 id，按顺序。 */
    val purged: MutableList<String> = mutableListOf()

    /** 置为非 null 时 [getSoftDeletedRecords] / [purgeRecord] 抛该异常。 */
    var failWith: Throwable? = null

    /** 清理回调：用来验证"先删文件、再删行"的顺序。 */
    var onPurge: ((String) -> Unit)? = null

    /** 置为非 null 时 [getSoftDeletedRecords] 会等它完成：用来制造"清理进行中"的中间态。 */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getSoftDeletedRecords(): List<Record> {
        gate?.await()
        failWith?.let { throw it }
        return softDeletedRecords.toList()
    }

    override suspend fun purgeRecord(recordId: String): Boolean {
        onPurge?.invoke(recordId)
        failWith?.let { throw it }
        if (recordId in refusedIds) return false
        if (!softDeletedRecords.removeAll { it.id == recordId }) return false
        purged += recordId
        return true
    }
}
