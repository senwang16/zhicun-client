package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject

/**
 * 批量归档 / 取消归档（M16）。
 *
 * ## 归档不是删除
 * 归档只把记录移出收件箱视图：数据、附件、派生内容都不动，导出包里也照旧包含，
 * 取消归档即恢复。但它**会退出检索** —— 归档后不再出现在全文搜索与相似推荐里
 * （2026-10-24 用户裁决；判据在 FTS 触发器与向量召回的 JOIN 条件里）。
 *
 * 真正删数据只有两条路：多选/详情页的删除（[DeleteRecordsUseCase]），以及设置页的清理（M15）。
 *
 * ## 为什么要 distinct + 过滤空白 id
 * 多选模式下用户可能先把一条记录选中、再取消再选（不同路径的 id 可能重复进来）。
 * `IN (...)` 里的重复 id 不会出错，但返回值是"受影响行数" —— 重复会让预期 5 条变 3 条，
 * 界面上的"已归档 5 条"就对不上了。空白 id 则一定是上游 bug，直接滤掉比让它去查库更好。
 */
class ArchiveRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
) {

    /**
     * 归档。
     *
     * @return 实际归档的条数（`0` 表示记录已被删除或都不存在）
     */
    suspend operator fun invoke(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Int = apply(ids, archivedAt = now, now = now)

    /**
     * 取消归档，把记录移回收件箱。
     *
     * 与 [invoke] 分开成两个入口而不是"传 null 就是取消"：调用方读代码时
     * `archive.restore(ids)` 比 `archive(ids, archivedAt = null)` 少一层猜测。
     */
    suspend fun restore(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Int = apply(ids, archivedAt = null, now = now)

    private suspend fun apply(ids: List<String>, archivedAt: Long?, now: Long): Int {
        val targets = ids.distinct().filter { it.isNotBlank() }
        // 守卫：空列表不要打数据库。Room 的 `IN ()` 虽然不报错，但会白跑一次 UPDATE，
        // 而 UPDATE 会触发列表 Flow 重新发射 —— 界面上表现为"什么都没做，列表闪了一下"
        if (targets.isEmpty()) return 0
        return recordRepository.setArchived(targets, archivedAt = archivedAt, updatedAt = now)
    }
}
