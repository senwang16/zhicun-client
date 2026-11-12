package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.maintenance.LocalFileStore
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.MaintenanceRepository
import javax.inject.Inject

/**
 * 清理结果。界面据此说清"删了什么、还剩什么"。
 *
 * @param missingAssetFiles 数据库有行、磁盘上本来就没文件的数量（不算失败）
 * @param failedAssetFiles 文件在、但删不掉的数量（只有这种情况下磁盘空间没被释放）
 */
data class CleanupReport(
    val recordsDeleted: Int = 0,
    val assetsDeleted: Int = 0,
    val missingAssetFiles: Int = 0,
    val failedAssetFiles: Int = 0,
) {
    val hasWork: Boolean get() = recordsDeleted > 0

    val hasFailure: Boolean get() = failedAssetFiles > 0

    /**
     * 这一趟什么都没发生。
     *
     * 判据要连 `hasFailure` 一起看：只有 1 条记录、它的文件删不掉时，
     * `recordsDeleted` 与 `assetsDeleted` 都是 0，但它绝不是"没有需要清理的记录" ——
     * 磁盘空间没释放，界面必须如实说出来。
     */
    val nothingDone: Boolean
        get() = recordsDeleted == 0 && assetsDeleted == 0 && failedAssetFiles == 0
}

/**
 * 清理标记删除的记录（M15；M16 修正了失败时的收尾行为）。
 *
 * ## 顺序：先删文件，再删数据库行
 * 反过来的话，一旦删文件失败（占用、权限），数据库里已经不剩任何线索指向那些文件 ——
 * 它们会永远占着磁盘且**再也找不到**。
 *
 * ## 有文件删不掉时，这条记录的数据库行**不清**
 * `purgeRecord` 会把 asset 行一起删掉，删完就再没有线索指向那个残留文件。
 * 保留行的代价是列表里继续出现这条打不开的记录，换来的是**唯一的重试依据**：
 * 下次点清理会再试一次，一旦文件能删就正常清掉。
 * 已经删掉的其它文件不会重复计数 —— 下次进来 `fileStore.exists` 为 false，归入 `missingAssetFiles`。
 *
 * ## 为什么逐条处理而不是一个大事务
 * 一条记录的文件可能有很多（多图分享），整体事务会让数据库长时间持锁。
 * 每条记录内部仍是事务（[MaintenanceRepository.purgeRecord] 删多表），
 * 保证单条记录不会删一半。
 *
 * 注意：数据库层面的异常（磁盘满、库损坏）会**整体上抛**，由界面提示重试 ——
 * 此时文件已删完，重跑是幂等的，不会误伤数据。不在这里逐条吞掉异常，
 * 是因为那会把"库坏了"显示成"部分成功、可稍后再试"，让用户反复重试却永远清不干净。
 *
 * ## 护栏
 * `purgeRecord` 只删**已标记删除**的记录。清理是"用户已经点了删除"的收尾动作，
 * 绝不能变成"顺手删掉正常数据"—— 那对这个 App 是不可恢复的损失。
 */
class CleanupDeletedRecordsUseCase @Inject constructor(
    private val maintenanceRepository: MaintenanceRepository,
    private val assetRepository: AssetRepository,
    private val fileStore: LocalFileStore,
) {

    suspend operator fun invoke(onlyIds: Set<String>? = null): CleanupReport {
        val records = maintenanceRepository.getSoftDeletedRecords()
            // 删除流程只清"这次点掉的这几条"。不限定的话会把历史上所有待清理记录一起扫进去，
            // 报告里的条数就和用户选中的对不上 —— 他删 5 条，界面说"已删除 8 条"。
            .filter { onlyIds == null || it.id in onlyIds }
        var recordsDeleted = 0
        var assetsDeleted = 0
        var missing = 0
        var failed = 0

        records.forEach { record ->
            // 先把文件处理干净：即使数据库清理失败，重复执行也是幂等的
            var recordHadFailure = false
            assetRepository.getByRecordId(record.id).forEach { asset ->
                when {
                    !fileStore.exists(asset.localPath) -> missing++

                    fileStore.delete(asset.localPath) -> assetsDeleted++

                    // 删除失败（文件被占用 / 只读挂载）：记账，并且**这条记录的数据库行必须留着**
                    else -> {
                        failed++
                        recordHadFailure = true
                    }
                }
            }

            // 有文件删不掉就跳过行清理（M16 修正）。purgeRecord 会连 asset 行一起删，
            // 删完之后就再没有任何线索指向那个文件 —— 它永远占着磁盘，下次清理也查不到它，
            // 用户会看到"清理成功、空间没变"。留着行虽然难看（列表里还有这条打不开的记录），
            // 但它保住了唯一的重试依据：下次点清理会再试一次，一旦能删就正常清掉。
            //
            // 已经删掉的那部分文件不会白删：下次进来 `fileStore.exists` 为 false，记进 missing，不重复计数。
            if (!recordHadFailure && maintenanceRepository.purgeRecord(record.id)) recordsDeleted++
        }

        return CleanupReport(
            recordsDeleted = recordsDeleted,
            assetsDeleted = assetsDeleted,
            missingAssetFiles = missing,
            failedAssetFiles = failed,
        )
    }
}
