package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.diagnostic.DiagnosticLog
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.SaveRecordResult
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * 保存一条记录（M4 系统分享与 M5 手动新建共用的入口用例）。
 *
 * 顺序是硬约束（ARCHITECTURE §5.1 / §6.2）：**先落记录，再复制附件，最后才排后台任务**。
 * 反过来的话，附件复制到一半被杀进程就会留下"有文件、没记录"的孤儿文件；
 * 而按这个顺序最坏结果是"有记录、附件缺失"，恰好就是 PRD 允许的状态
 * （保留记录并标注附件不可用）。
 *
 * M6 起在本用例末尾调用 [ScheduleProcessingUseCase]：调度放在这里而不是各入口页面，
 * 是为了让"分享"/"手动新建"/将来的"悬浮球"都自动获得同一套后台处理，
 * 不会因为某个入口忘了调而静默漏掉 OCR/网页提取。
 */
class SaveRecordUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val copyAsset: CopyAssetToPrivateDirUseCase,
    private val sensitiveDetector: SensitiveContentDetector,
    private val scheduleProcessing: ScheduleProcessingUseCase,
    private val diagnosticLog: DiagnosticLog,
) {

    /**
     * @param input 解析或组装好的待保存内容
     * @param captureSource 收录入口；决定 `Record.captureSource` 与纯文本记录的最终类型
     * @param now 时间戳；显式传入便于测试固定时间（默认取系统时钟）
     * @param captureStartedAt 采集开始时间（目前仅录音传入，见 PRD v1.2 §4.2）
     * @param captureEndedAt 采集结束时间
     * @param captureDurationMs 实际采集时长（不含暂停）
     * @throws IllegalArgumentException 内容为空时抛出（PRD §11 禁止创建空记录）
     */
    suspend operator fun invoke(
        input: RecordInput,
        captureSource: CaptureSource = CaptureSource.SYSTEM_SHARE,
        now: Long = System.currentTimeMillis(),
        captureStartedAt: Long? = null,
        captureEndedAt: Long? = null,
        captureDurationMs: Long? = null,
    ): SaveRecordResult {
        require(input.hasContent) { "记录内容为空，不得创建记录" }

        val recordId = UUID.randomUUID().toString()
        val record = buildRecord(
            input = input,
            captureSource = captureSource,
            recordId = recordId,
            now = now,
            captureStartedAt = captureStartedAt,
            captureEndedAt = captureEndedAt,
            captureDurationMs = captureDurationMs,
        )
        recordRepository.save(record)

        var saved = 0
        var failed = 0
        // 下标即用户的选择顺序：界面上第几个附件，在详情页与识别结果里就是第几个
        input.supportedUris.forEachIndexed { index, source ->
            val asset = copyAsset(recordId = recordId, source = source, orderIndex = index)
            if (asset.saveStatus == AssetSaveStatus.SAVED) saved++ else failed++
        }

        // 记录已经落库了，调度失败不能让用户看到"保存失败"（那会让人以为内容丢了）。
        // 失败原因由调度实现（WorkManagerProcessingScheduler）记录；domain 层不引 android.util.Log。
        //
        // 这里**不能**用 `runCatching`：它会连 `CancellationException` 一起吞掉，
        // 保存协程在调度阶段被取消时（用户离开页面 / 进程回收）取消信号会消失，
        // 调用方还会收到"保存成功"，生命周期语义就错了。必须显式放行取消。
        // （M5–M8 独立审核发现，2026-10-21 整改）
        try {
            scheduleProcessing(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 本地已落盘，保存结果照常返回；但失败原因必须留痕（AGENTS §2.1 禁止空 catch）。
            // 这里捕获的不只是调度器的入队异常，还有任务表读写等仓储异常——后者
            // 调度器日志覆盖不到，必须在此记录，否则成为静默失败（2026-10-22 审核 P2）。
            diagnosticLog.w(TAG, "后台任务调度失败，记录已保存 recordId=$recordId", e)
        }

        return SaveRecordResult(
            recordId = recordId,
            savedAssetCount = saved,
            failedAssetCount = failed,
            skippedAssetCount = input.unsupportedMimeTypes.size + input.excessUriCount,
        )
    }

    private fun buildRecord(
        input: RecordInput,
        captureSource: CaptureSource,
        recordId: String,
        now: Long,
        captureStartedAt: Long?,
        captureEndedAt: Long?,
        captureDurationMs: Long?,
    ): Record {
        val text = input.text?.trim()?.takeIf { it.isNotEmpty() }
        val sourceTitle = input.subject?.trim()?.takeIf { it.isNotEmpty() }
        val type = SharedContentClassifier.classify(input, captureSource)
        // 无论类型如何都保留原文里的 URL：PRD §5.1 要求"收到 URL 必须保留原 URL"，
        // 即使这条记录最终被判成 IMAGE/FILE（例如分享图片时附带了一句带链接的说明）。
        val sourceUrl = SharedContentClassifier.firstUrl(text)
        // 扫描范围必须覆盖**所有会被上传出去的文字**，而不只是正文（2026-10-24 全项目审查 H-1）。
        // 分享标题会被当作 SOURCE_TITLE 拼进摘要输入（ProcessingInputText.summaryInput），
        // 若只扫正文，"正文干净、标题里是证件号/卡号"的记录会以 CLEAR 状态把标题原样发到网关，
        // 绕过 R-2/F-5 那套"敏感内容需用户确认才上传"的闸门。
        val scan = sensitiveDetector.scan(listOfNotNull(text, sourceTitle).joinToString("\n"))

        return Record(
            id = recordId,
            type = type,
            captureSource = captureSource,
            rawText = text,
            sourceUrl = sourceUrl,
            sourceTitle = sourceTitle,
            sourceApp = input.sourceApp?.trim()?.takeIf { it.isNotEmpty() },
            captureStartedAt = captureStartedAt,
            captureEndedAt = captureEndedAt,
            captureDurationMs = captureDurationMs,
            createdAt = now,
            updatedAt = now,
            localStatus = LocalStatus.SAVED,
            // 分享进来的内容默认待处理；LOCAL_ONLY 留给"用户关闭了云端 AI"的场景（M6 判定）
            processingStatus = ProcessingStatus.PENDING,
            sensitivityStatus = sensitiveDetector.toSensitivityStatus(scan),
            recordRevision = 1,
            isDeleted = false,
        )
    }

    private companion object {
        const val TAG = "SaveRecord"
    }
}
