package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.domain.worker.ProcessingScheduler
import javax.inject.Inject

/**
 * 删除结果。界面据此如实说"删掉了几条、还剩几条没清干净"。
 *
 * @param requested 用户确认要删的条数（已去重、已滤空）
 * @param deleted 文件与数据库行都清掉的条数
 */
data class DeleteReport(
    val requested: Int,
    val deleted: Int,
) {

    /**
     * 没能清干净的条数。
     *
     * 常见原因是**附件文件正被别的应用占用**（播放器还开着那段录音、PDF 阅读器没退出），
     * 此时记录已经不可见、只是磁盘还占着，下次清理会再试一次。
     */
    val incomplete: Int get() = (requested - deleted).coerceAtLeast(0)

    val hasIncomplete: Boolean get() = incomplete > 0
}

/**
 * 删除记录（2026-10-24 用户可见的删除入口）。
 *
 * ## 为什么是"软删除 + 立即清理"，而不是一条 DELETE
 * 物理清理的规则早就定在 [CleanupDeletedRecordsUseCase] 里，而且那套规则有它的道理：
 * **先删文件、再删数据库行**（反过来的话，文件删不掉时库里就再没有线索指向它们），
 * 有文件删不掉时**保留行**当作下次重试的依据。删除功能如果另写一份删表逻辑，
 * 就是同一件事的第二套判据 —— 迟早出现"设置页清理保留行、删除入口不保留"这种分裂。
 *
 * ## 顺序
 * 1. **软删除**：一条 `UPDATE`，列表立刻消失，用户马上看到结果；
 * 2. **取消后台任务**：见下；
 * 3. **物理清理**：复用清理用例，只清这一批（`onlyIds`）。
 *
 * ## 为什么必须取消任务
 * `ProcessingJobExecutor` 读记录用的是 `getById`，它**刻意不过滤** `is_deleted`
 * （详情页要靠它显示"已删除"横幅）。所以不取消的话，一条正在做 OCR/摘要的记录
 * 被删掉之后，Worker 仍会跑完并往 `derived_contents` 写结果 —— 而清理事务可能刚好
 * 在它之前结束，于是留下一行指向不存在记录的孤儿派生内容。
 *
 * 取消动作放在软删除之后：软删除是用户意图的落地，必须最先完成；取消只是防御，
 * 晚几毫秒不影响正确性（真正的兜底是 Worker 侧的那道判断）。
 *
 * ## 已知的不一致
 * 软删除成功、清理抛异常时（磁盘满、库损坏），异常向上抛给界面显示"删除失败"，
 * 但记录其实已经不可见了。这个方向是安全的：用户要删的东西确实从视图里消失了，
 * 只是空间还没释放，设置页的清理会接手。
 */
class DeleteRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val collectionRepository: CollectionRepository,
    private val cleanupDeletedRecords: CleanupDeletedRecordsUseCase,
    private val scheduler: ProcessingScheduler,
) {

    /**
     * @param ids 用户勾选的记录 id（来自多选顶栏）
     * @param now 统一的时间戳，测试要能控制
     * @return 删成了几条、还剩几条
     */
    suspend operator fun invoke(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
    ): DeleteReport {
        // 去重 + 滤空：多选时同一条记录可能经不同路径进来两次（先选、取消、再选）。
        // 重复 id 不会让 UPDATE 出错，但会让"删了 5 条还是 3 条"对不上。
        val targets = ids.distinct().filter { it.isNotBlank() }
        // 空列表不要打数据库：`IN ()` 不报错，但会白跑一次 UPDATE 并让列表 Flow 发射一次，
        // 界面上表现为"什么都没做，列表闪了一下"
        if (targets.isEmpty()) return DeleteReport(requested = 0, deleted = 0)

        // 先取类型：取消任务要知道这条记录会排哪些 jobType。
        // 用 getById 而不是 getByIds —— 后者已经排除了归档记录，而归档箱里也能删。
        val records = targets.mapNotNull { recordRepository.getById(it) }

        recordRepository.softDeleteMany(targets, now)

        // 被删的记录可能是某个合集的成员（M20）：它退出重新生成，合集的敏感并集与
        // 云端确认都要跟着变。必须在物理清理之前做 —— 清理会级联删掉成员关系，
        // 那时就反查不到它属于哪个合集了。
        records.forEach { collectionRepository.refreshForMemberChange(it.id, now) }

        records.forEach { record ->
            ProcessingJobTargets.of(record.type).forEach { jobType ->
                scheduler.cancel(record.id, jobType)
            }
        }

        val report = cleanupDeletedRecords(onlyIds = targets.toSet())

        return DeleteReport(requested = targets.size, deleted = report.recordsDeleted)
    }
}
