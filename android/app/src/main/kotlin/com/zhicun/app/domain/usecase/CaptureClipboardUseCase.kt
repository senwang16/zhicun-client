package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.clipboard.ClipboardReadResult
import com.zhicun.app.domain.clipboard.ClipboardReader
import com.zhicun.app.domain.clipboard.ClipboardText
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject

/**
 * 一次剪贴板收录的结论。
 */
sealed interface ClipboardCaptureResult {

    /** 已保存。[preview] 是给通知/Toast 用的一行摘要。 */
    data class Saved(val recordId: String, val preview: String) : ClipboardCaptureResult

    /**
     * 这条内容已经在收件箱里了，本次**没有**新建记录（2026-10-24 去重）。
     *
     * 与 [Saved] 分开两个分支而不是塞一个 `newlyCreated: Boolean`：界面文案完全不同
     * （"已存" vs "已经存过了，没重复保存"），布尔值迟早会在某个调用点被反过来用。
     */
    data class Duplicate(val recordId: String, val preview: String) : ClipboardCaptureResult

    /** 剪贴板为空、或只有空白。 */
    data object Empty : ClipboardCaptureResult

    /** 剪贴板里是图片 / 文件 / URI 等非文字内容。 */
    data object Unsupported : ClipboardCaptureResult

    /** 系统拒绝读取剪贴板，或读取过程抛异常。 */
    data object Unavailable : ClipboardCaptureResult
}

/**
 * 剪贴板收录（M13）：读剪贴板 → 归一化 → 判重 → 走 [SaveRecordUseCase] 落库。
 *
 * ## 为什么不直接调仓储
 * 保存链路有硬约束：先落 Record、再复制附件、最后排后台任务，以及敏感检测与类型推断。
 * 绕过它等于把 M4/M5 已经验证过的规则重写一遍，且日后加规则时这里会静默漏掉。
 * 因此复用同一个入口用例，只把 `captureSource` 标成 `CLIPBOARD`，
 * 这样"这条记录是从剪贴板来的"在库里可查。
 *
 * ## 为什么要判重（2026-10-24，2026-10-27 扩到跨来源）
 * 气泡点一次收一次，而剪贴板内容不会因为"存过了"就消失 —— 用户连点三下就会得到
 * 三条一模一样的记录。PRD v1.2 §2.4 要求与**已有记录**比对（不再限制来源）：
 * 用户从别的入口存过同一段文字，再从剪贴板存一次，同样是重复。
 * 判据只做 trim + 换行统一（见 [ClipboardText.dedupKey]），URL 参数与中间空格不动。
 *
 * 判重放在保存之前而不是"先存再删重复"：后者会让每个任务表、附件目录都白跑一遍，
 * 删除也可能失败，最终留下真正的重复数据。
 *
 * ## 空剪贴板 / 非文本 / 读不到为什么正常返回而不抛异常
 * 这三件事都是用户操作中可预期的结果，不是程序错误。抛异常会让调用方只能靠 catch
 * 区分它们与"保存失败"，而界面文案完全不同（空 → 手动记；非文本 → 不支持；
 * 读不到 → 系统限制；保存失败 → 重试或手动新建）。
 */
class CaptureClipboardUseCase @Inject constructor(
    private val clipboardReader: ClipboardReader,
    private val recordRepository: RecordRepository,
    private val saveRecord: SaveRecordUseCase,
) {

    suspend operator fun invoke(now: Long = System.currentTimeMillis()): ClipboardCaptureResult {
        val raw = when (val read = clipboardReader.read()) {
            is ClipboardReadResult.Text -> read.value
            ClipboardReadResult.Empty -> return ClipboardCaptureResult.Empty
            ClipboardReadResult.Unsupported -> return ClipboardCaptureResult.Unsupported
            ClipboardReadResult.Unavailable -> return ClipboardCaptureResult.Unavailable
        }

        val text = ClipboardText.normalize(raw)
            ?: return ClipboardCaptureResult.Empty

        val existingId = recordRepository.findDuplicate(
            text = ClipboardText.dedupKey(text),
        )
        val preview = ClipboardText.preview(text)
        if (existingId != null) {
            return ClipboardCaptureResult.Duplicate(recordId = existingId, preview = preview)
        }

        val result = saveRecord(
            input = RecordInput(text = text),
            captureSource = CaptureSource.CLIPBOARD,
            now = now,
        )
        return ClipboardCaptureResult.Saved(
            recordId = result.recordId,
            preview = preview,
        )
    }
}
