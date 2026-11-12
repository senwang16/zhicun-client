package com.zhicun.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.zhicun.app.di.ApplicationScope
import com.zhicun.app.domain.export.ExportArchiveWriter
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.RecoverPendingJobsUseCase
import com.zhicun.app.domain.usecase.RerunMultiAssetProcessingUseCase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智存 App 的 Application 入口。
 *
 * `@HiltAndroidApp` 会在编译期生成 `Hilt_ZhiCunApplication` 基类并装载 DI 容器，
 * 是全局依赖注入（T-002 决策：Google Hilt）的唯一入口。
 *
 * 同时实现 [Configuration.Provider]（M6）把 `HiltWorkerFactory` 交给 WorkManager，
 * Worker 因此可以 `@AssistedInject` 直接注入仓储/UseCase，不必在 Worker 里手搓
 * `EntryPointAccessors`。对应的 `WorkManagerInitializer` 已在 AndroidManifest 中移除 ——
 * 否则默认初始化会先跑，产出的 Worker 拿不到 Hilt 依赖，一执行就崩。
 *
 * 冷启动时补投未完成的后台任务（[RecoverPendingJobsUseCase]，M6 审核整改）：
 * 调度失败或进程被杀留下的 `QUEUED`/`RUNNING` 任务，若用户此后不再保存任何内容，
 * 就没有任何代码会去碰它们 —— 后台处理会永久停摆。放在 Application 而不是某个页面，
 * 是因为这条恢复逻辑必须与"用户打开了哪个页面"无关。
 *
 * ## 这条恢复逻辑会被跑两次以上的场景（2026-10-24 全项目审查 L-5）
 * `onCreate` 不只发生在"用户点图标"，WorkManager 拉起后台进程执行任务、系统恢复
 * ContentProvider 等都会新建一次 Application，因此这里每个进程生命周期都会跑一次：
 * - 用户冷启动，进程随后被 WorkManager 复用执行任务 → 只有一次；
 * - WorkManager 先拉起进程跑任务，用户再点图标 → 同进程复用，也只有一次；
 * - 进程被系统回收后又被拉起 → 新进程，再跑一次。
 *
 * 重复执行是安全且必要的（`enqueue` 用 `ExistingWorkPolicy.KEEP`，已在排的任务不会被重复执行），
 * 唯一需要注意的是**不能在这里做"复位 RUNNING 之外的破坏性动作"**：进程刚起来时看到的所有
 * `RUNNING` 都是上一个进程的遗物，复位它们永远是对的。
 */
@HiltAndroidApp
class ZhiCunApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var recoverPendingJobs: RecoverPendingJobsUseCase

    @Inject
    lateinit var cleanupDeletedRecords: CleanupDeletedRecordsUseCase

    @Inject
    lateinit var exportArchiveWriter: ExportArchiveWriter

    @Inject
    lateinit var rerunMultiAssetProcessing: RerunMultiAssetProcessingUseCase

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            // 先补清"已确认删除但没清完"的记录（2026-10-24 删除功能）。
            //
            // 为什么会留下这种东西：删除 = 软删除（一条 UPDATE，很快）→ 取消任务 → 物理清理。
            // 中间任何一步进程被杀/被强停，记录就停在"列表里看不见、磁盘还占着"的状态，
            // 而用户根本不知道有这回事 —— 他以为已经删掉了。
            //
            // 全量清理是幂等的：没有待清理记录时它只查一次空表。
            // 文件删除是阻塞 IO，必须切到 IO 线程，不能占 Default 池。
            try {
                val cleaned = withContext(Dispatchers.IO) { cleanupDeletedRecords() }
                if (cleaned.hasWork) {
                    Log.i(
                        TAG,
                        "启动补清已删除记录 ${cleaned.recordsDeleted} 条、附件 ${cleaned.assetsDeleted} 个",
                    )
                }
                if (cleaned.hasFailure) {
                    Log.w(
                        TAG,
                        "启动补清有 ${cleaned.failedAssetFiles} 个附件删不掉，保留记录待下次重试",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 与补投一致：清理失败不能影响启动，下次冷启动还会再试
                Log.e(TAG, "启动补清已删除记录失败", e)
            }

            // 导出包是全部记录的明文副本，只服务于"刚导出就要分享"那一次动作；
            // 重试分享的引用只活在 ViewModel 内存里，新进程里不可能还有用
            // （2026-11-01 审核 S-4，自海外轨移植）。
            try {
                exportArchiveWriter.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "启动清空导出包失败", e)
            }

            try {
                val recovered = recoverPendingJobs()
                if (recovered > 0) Log.i(TAG, "启动补投后台任务 $recovered 个")
            } catch (e: CancellationException) {
                // 进程销毁时的正常取消，原样抛出，不能当成错误记录
                throw e
            } catch (e: Exception) {
                // 补投失败不能影响启动：下次冷启动会再试一遍
                Log.e(TAG, "启动补投后台任务失败", e)
            }

            // DB v8：老记录此前只处理第一个附件，升级后补跑一次逐附件 OCR / 转写（D1）。
            // 放在补投之后：这里刚把任务复位成 QUEUED，紧接着的 enqueue 才不会被漏掉。
            // 一次性标记只在全部入队后写；进程中途被杀只是下次再扫一遍，重跑是覆盖式幂等的。
            try {
                val enqueued = rerunMultiAssetProcessing()
                if (enqueued > 0) Log.i(TAG, "多附件老记录补跑 $enqueued 条")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "多附件老记录补跑失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "ZhiCunApplication"
    }
}
