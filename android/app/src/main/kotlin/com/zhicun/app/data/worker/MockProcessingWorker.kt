package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 空转的 Worker：不处理任何内容，只把任务标记为完成（M6）。
 *
 * 存在的意义是**让调度链路可以被验证**：M6 落地时 OCR（M7）与网页提取（M8）都还没有实现，
 * 没有它就无法证明"保存记录 → 任务入库 → WorkManager 执行 → 状态回写"这条链是通的。
 *
 * M7/M8 接入真实 Worker 后，`WorkManagerProcessingScheduler` 的任务类型映射会指向具体实现，
 * 这个类保留给调试与链路自检（不在 release 路径上被调度）。
 */
@HiltWorker
class MockProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        Log.i(TAG, "Mock 处理完成 recordId=${record.id}（M6 调度链路自检）")
        return ProcessorResult.Success
    }

    private companion object {
        const val TAG = "MockWorker"
    }
}
