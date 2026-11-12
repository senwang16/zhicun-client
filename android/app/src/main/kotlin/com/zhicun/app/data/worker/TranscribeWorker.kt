package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.SpeechTranscriber
import com.zhicun.app.domain.audio.AudioDurationProbe
import com.zhicun.app.domain.audio.TranscriptionPolicy
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 语音转写（M9，T-006 决策：云端转写走网关；多附件起逐段处理）。
 *
 * 链路：`assets` 里的音频 → 逐段 [SpeechTranscriber] → 每段写一条
 * `recordId:TRANSCRIPTION:assetId` 派生内容。重跑只覆盖自己那条，不碰别的录音。
 *
 * ## 三条判定
 * - **没有音频附件**：记录是 `VOICE_NOTE` 却没有音频，属数据异常 → `Permanent`；
 * - **单段未落盘**：文件不在，重试也不会出现 → 写错误行并继续其它音频；
 * - **转写为空**：算成功并写入空串（PRD：静音录音、纯环境音很常见）。
 *   把它当失败会让这类记录永远挂红，还诱导用户反复重试。
 *
 * 另有一条**产品护栏**（2026-11-03）：`TranscriptionPolicy` 对时长（5 分钟）与体积
 * （20MB）双限制，任一超限就不发起上传；音频照常保存与播放，任务算成功而不是失败。
 * 只看时长会让高码率文件撞网关体积墙，只看体积会让低码率长音频撞转写超时墙。
 *
 * ## 每段上传前都复查一次闸门
 * 音频字节就是请求体，一次任务可能连发 N 次请求。判闸只做一次的话，中途
 * 用户关掉云端 / 把记录升为敏感 / 删掉记录，后面几段仍然会被上传（R-6 TOCTOU）。
 * 因此**每段发请求前**调 [ProcessingJobExecutor.recheckBeforeUpload]；被拦时返回
 * `Blocked`，已完成的音频结果保留 —— 它们本来就是那次合规上传的产物。
 *
 * ## 失败时的音频
 * 任何失败路径都**不碰** `Asset` 与音频文件：原音频必须始终可播放
 * （M9 验收标准）。转写只是"多一段文字"，不是记录的存在前提。
 *
 * 异常不在这里分类：网关异常（`GatewayTransientException : IOException` → 重试，
 * `GatewayRejectedException`/`GatewayNotConfiguredException` → 终态）由
 * `ProcessingJobExecutor` 统一判定，避免两处规则漂移。
 *
 * **费用口径**：每段音频一次 ASR 请求，多音频记录的耗时与费用按条数线性增长。
 */
@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
    private val assetRepository: AssetRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val transcriber: SpeechTranscriber,
    private val durationProbe: AudioDurationProbe,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
    private val sensitivityGuard: DerivedSensitivityGuard,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        // 转写同样是把内容发到云端（音频字节作 body），因此与摘要/向量共用同一道闸门。
        // M9 实现时漏了这一步，导致"疑似敏感的录音仍被上传"——M14 补上。
        // Blocked 而不是 Permanent：策略性不上传不是失败（QODER P2-1/P2-3）。
        CloudUploadGate.blockReason(aiSettings.cloudAiEnabled, gatewaySettings.isConfigured, record)
            ?.let { reason -> return ProcessorResult.Blocked(reason) }

        val audios = assetRepository.getByRecordId(record.id).filter { it.isAudio }
        if (audios.isEmpty()) {
            return ProcessorResult.Permanent("没有音频附件，跳过转写")
        }

        var transcribed = 0
        // 因超出转写上限而跳过的段数（2026-11-03 自海外轨同步）：这不是失败，
        // 是"这条音频不做转写"—— 音频照常保存、照常播放。
        var skippedByPolicy = 0
        audios.forEach { audio ->
            if (audio.saveStatus != AssetSaveStatus.SAVED) {
                writeRow(
                    recordId = record.id,
                    assetId = audio.id,
                    content = null,
                    errorMessage = "音频未成功落盘（${audio.saveStatus}）",
                )
                Log.w(TAG, "跳过未落盘音频 assetId=${audio.id} 状态=${audio.saveStatus}")
                return@forEach
            }

            // 时间 + 体积双限制（TranscriptionPolicy）：任一超限就不发起上传。
            // 单段录音优先用保存时记下的真实时长，避免每次转写都再解析一次容器；
            // 多段/导入音频探测文件头；探测失败按未知处理（fail-closed，不转写）。
            val durationMs = if (audios.size == 1) {
                record.captureDurationMs
            } else {
                null
            } ?: durationProbe.probe(audio.localPath)
            val verdict = TranscriptionPolicy.verdictFor(durationMs, audio.fileSize)
            if (verdict is TranscriptionPolicy.Verdict.Rejected) {
                Log.i(
                    TAG,
                    "音频超出转写上限，跳过 assetId=${audio.id} reason=${verdict.reason} " +
                        "durationMs=${durationMs ?: "unknown"} sizeBytes=${audio.fileSize}",
                )
                // 落一条"可解释"的行：界面据此说明"为什么没有文字"，
                // 而不是一直显示"正在转写…"。这不是失败行（content=null + errorMessage 是说明）。
                writeRow(
                    recordId = record.id,
                    assetId = audio.id,
                    content = null,
                    errorMessage = policyMessageOf(verdict.reason),
                )
                skippedByPolicy++
                return@forEach
            }

            // 音频即将作为请求体发出去：用新鲜记录再判一次闸（R-6 TOCTOU）。
            // 发出去的是音频本体，因此这一趟占用的内容块是 ATTACHMENT（R-2）：
            // 用户当初只确认了文字、没确认这段录音时，这里必须要求重新确认。
            executor.recheckBeforeUpload(record.id, setOf(ContentPart.ATTACHMENT))
                ?.let { return ProcessorResult.Blocked(it) }

            val text = transcriber.transcribe(audio.localPath, audio.mimeType)
            writeRow(recordId = record.id, assetId = audio.id, content = text, errorMessage = null)
            // 转写文本里可能含卡号/证件号：逐条复查后升级敏感标记，挡住摘要/向量的二次上传。
            // 注意：音频本身在转写前无法预检，那一次上传已发生（详见 DerivedSensitivityGuard 类注释）。
            sensitivityGuard.elevateIfSuspected(record.id, text)
            transcribed++
        }

        if (transcribed == 0) {
            // 全部因超限被跳过 → 成功（"没有文字"是预期结果，不是失败）。
            // 只有确实存在落盘失败的段时才报错，否则详情页会挂一条永远不会好的红色失败。
            if (skippedByPolicy == audios.size) {
                Log.i(TAG, "全部音频超出转写上限，按成功收尾 recordId=${record.id} 段数=$skippedByPolicy")
                return ProcessorResult.Success
            }
            return ProcessorResult.Permanent("${audios.size} 段音频均未成功落盘")
        }

        derivedContentRepository.deleteAssetRowsNotIn(
            recordId = record.id,
            type = DerivedType.TRANSCRIPTION,
            keepAssetIds = audios.map { it.id },
        )
        Log.i(TAG, "转写完成 recordId=${record.id} 段数=$transcribed/${audios.size}")
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
                id = DerivedContentKeys.idFor(recordId, DerivedType.TRANSCRIPTION, assetId),
                recordId = recordId,
                contentType = DerivedType.TRANSCRIPTION,
                content = content,
                processorVersion = PROCESSOR_VERSION,
                processedAt = System.currentTimeMillis(),
                errorMessage = errorMessage,
                assetId = assetId,
            ),
        )
    }

    /** 超限原因的说明文案（只用于详情页解释，不写进任务失败原因）。 */
    private fun policyMessageOf(reason: TranscriptionPolicy.Reason): String = when (reason) {
        TranscriptionPolicy.Reason.TOO_LONG ->
            "音频超过 ${TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS / 60_000} 分钟转写上限（已保存，可正常播放）"

        TranscriptionPolicy.Reason.TOO_LARGE ->
            "音频超过 ${TranscriptionPolicy.MAX_TRANSCRIBE_BYTES / 1024 / 1024}MB 转写上限（已保存，可正常播放）"

        TranscriptionPolicy.Reason.DURATION_UNKNOWN ->
            "无法读取音频时长，未转写（音频已保存，可正常播放）"
    }

    companion object {
        /**
         * 处理链路版本。
         *
         * 换 ASR 供应商或模型时改这里，`processorVersion` 就能区分旧结果，
         * 便于将来批量重跑（与 OCR 的约定一致）。
         */
        internal const val PROCESSOR_VERSION = "gateway-asr/v1"

        private const val TAG = "TranscribeWorker"
    }
}
