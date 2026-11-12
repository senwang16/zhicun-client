package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.ocr.OcrEngine
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 图片 OCR（M7；多附件起逐张处理）。
 *
 * 只做"取图片 → 逐张识别 → 每张写一条派生内容"，幂等与重试全部留给
 * [ProcessingJobExecutor]：本类被调到时，任务一定处于"该执行"的状态。
 *
 * ## 逐张处理，而不是只认第一张
 * 一条记录可以有多个图片附件（2026-10-29 多附件改造）。每张的结果用
 * `recordId:OCR_TEXT:assetId` 落库，重跑只覆盖自己那条，不碰别的附件。
 * 串行不并发：多张 4K 图并发容易顶到内存峰值，而单张本地 OCR 只有几十毫秒。
 *
 * ## 三条判定
 * - **没有图片附件**：记录本应有图却没有，属数据异常，重试无意义 → `Permanent`；
 * - **单张未落盘**（`FAILED` / `MISSING`）：文件不在，重试也不会出现。写一条错误行、
 *   继续处理其余图片 —— 一张坏图不该让整条记录的所有结果都消失；
 * - **图片读不出来**：`OcrEngine` 抛 `IOException`，**不在这里 catch** —— 交给
 *   Executor 的异常分类统一判为"可重试"，已写成功的行下次 REPLACE 覆盖，天然幂等。
 *
 * 全部图片都没落盘时返回 `Permanent`：任务确实没有任何产出，界面该显示失败。
 *
 * 识别成功但文字为空（纯色图、风景照）同样算成功：写入空串、任务 DONE，
 * 详情页显示"未识别到文字"。把它当失败会让一张无字截图永远挂着红色状态。
 *
 * 收尾统一走 [DerivedContentRepository.deleteAssetRowsNotIn]：清掉老版本遗留的
 * 记录级 OCR 行与"附件已被删除"的行，详情页不会同时显示出旧结果和新结果。
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
    private val assetRepository: AssetRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val ocrEngine: OcrEngine,
    private val sensitivityGuard: DerivedSensitivityGuard,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        val allImages = assetRepository.getByRecordId(record.id)
            .filter { it.mimeType.startsWith(IMAGE_MIME_PREFIX) }
        if (allImages.isEmpty()) {
            return ProcessorResult.Permanent("没有图片附件，跳过 OCR")
        }
        val images = allImages.take(MAX_IMAGES)
        if (allImages.size > MAX_IMAGES) {
            // 超出的图片不处理，行也在收尾时被清掉：单记录 20 张已远超正常截图场景，
            // 继续堆只会让一批 4K 图把任务拖到 WorkManager 超时。
            Log.w(TAG, "图片数超过上限，仅处理前 $MAX_IMAGES 张 recordId=${record.id}")
        }

        var recognized = 0
        images.forEach { image ->
            if (image.saveStatus != AssetSaveStatus.SAVED) {
                writeRow(
                    recordId = record.id,
                    assetId = image.id,
                    content = null,
                    errorMessage = "图片未成功落盘（${image.saveStatus}）",
                )
                Log.w(TAG, "跳过未落盘图片 assetId=${image.id} 状态=${image.saveStatus}")
                return@forEach
            }

            val text = ocrEngine.recognize(image.localPath)
            writeRow(recordId = record.id, assetId = image.id, content = text, errorMessage = null)
            // 截图里可能是身份证/卡号：OCR 文本落库后复查，命中则升级敏感标记，
            // 让后续 Summarize / Embed 的门禁把它拦下（QODER P1-1）。逐张复查，
            // 任何一张命中都足以让整条记录进入"待确认"。
            sensitivityGuard.elevateIfSuspected(record.id, text)
            recognized++
        }

        if (recognized == 0) {
            return ProcessorResult.Permanent("${images.size} 张图片均未成功落盘")
        }

        derivedContentRepository.deleteAssetRowsNotIn(
            recordId = record.id,
            type = DerivedType.OCR_TEXT,
            keepAssetIds = images.map { it.id },
        )
        Log.i(TAG, "OCR 结果已写入 recordId=${record.id} 张数=$recognized/${images.size}")
        return ProcessorResult.Success
    }

    private suspend fun writeRow(
        recordId: String,
        assetId: String,
        content: String?,
        errorMessage: String?,
    ) {
        derivedContentRepository.upsert(
            DerivedContent(
                id = DerivedContentKeys.idFor(recordId, DerivedType.OCR_TEXT, assetId),
                recordId = recordId,
                contentType = DerivedType.OCR_TEXT,
                content = content,
                processorVersion = PROCESSOR_VERSION,
                processedAt = System.currentTimeMillis(),
                errorMessage = errorMessage,
                assetId = assetId,
            ),
        )
    }

    companion object {
        internal const val IMAGE_MIME_PREFIX = "image/"

        /** 单条记录参与 OCR 的图片上限（超出跳过并留日志）。 */
        internal const val MAX_IMAGES = 20

        /**
         * 处理链路版本。
         *
         * 换模型或改预处理（缩放、灰度）时改这个值，`DerivedContent.processorVersion`
         * 就能区分"旧版本产出的结果"，将来可据此批量重跑（M10 之后）。
         */
        internal const val PROCESSOR_VERSION = "mlkit-text-chinese/16.0.1"

        private const val TAG = "OcrWorker"
    }
}
