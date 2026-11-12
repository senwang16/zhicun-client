package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.model.RecordInput
import javax.inject.Inject

/**
 * 把一段刚录好的音频保存成记录（M9）。
 *
 * ## 为什么复用 [SaveRecordUseCase] 而不是自己写一遍落库
 * "先落记录、再复制附件、最后排后台任务"这个顺序是 M4 定下的硬约束，
 * 里面还包含敏感检测、类型推断、附件失败也保留记录等规则。另起一条保存路径，
 * 等于把这些规则复制一份，两份迟早会漂移（最终一定有一边忘了处理附件失败）。
 *
 * ## 临时文件的生命周期
 * 录制写在 `cacheDir/voice-tmp/`（系统可回收），保存成功后**立刻删除** ——
 * 内容已经在 `filesDir/assets/<recordId>/` 里有一份，留着临时文件只是双倍占用。
 * 文件名由本用例生成且只含 ASCII，因此拼 `file://` 无需 URL 编码。
 */
class SaveVoiceNoteUseCase @Inject constructor(
    private val saveRecord: SaveRecordUseCase,
) {

    /**
     * @return 新记录 id + 音频是否真的落盘（调用方据此决定能不能说"已保存"）
     * @throws IllegalArgumentException 音频为空（调用方应在此之前拦掉）
     */
    suspend operator fun invoke(
        clip: RecordedClip,
        now: Long = System.currentTimeMillis(),
        // 默认 MANUAL 保持既有调用行为不变；悬浮气泡录音传入 FLOATING（2026-10-26）。
        captureSource: CaptureSource = CaptureSource.MANUAL,
    ): SaveVoiceNoteResult {
        require(clip.file.exists() && clip.sizeBytes > 0) { "录音文件为空，不得创建记录" }

        val input = RecordInput(
            text = null,
            supportedUris = listOf(
                InputUri(
                    uri = "file://${clip.file.absolutePath}",
                    // 明确给类型：录制格式是固定的，没必要让 provider 再猜一次
                    mimeType = clip.mimeType,
                ),
            ),
        )

        val result = saveRecord(
            input = input,
            captureSource = captureSource,
            now = now,
            // PRD v1.2 §4.2：开始时间、结束时间与实际录音时长随记录一起落库
            captureStartedAt = clip.captureStartedAt,
            captureEndedAt = clip.captureEndedAt,
            captureDurationMs = clip.durationMs,
        )
        // 复制成功才删临时文件。必须同时满足"至少落盘一个附件"与"没有失败"：
        // MIME 不被支持时 failedAssetCount 仍是 0（只计入 skipped），只看失败数会把
        // 一条没有音频的记录报成"已保存"，进而删掉临时文件 —— 录音就真的没了。
        val audioSaved = result.savedAssetCount > 0 && result.failedAssetCount == 0
        if (audioSaved) {
            clip.file.delete()
        }
        return SaveVoiceNoteResult(recordId = result.recordId, audioSaved = audioSaved)
    }
}

/**
 * 录音保存结果（2026-10-27，PRD v1.2 §4.2 / §4.3）。
 *
 * 记录先落库，音频复制可能失败；两种情况都必须如实回传给界面 ——
 * 只有 [audioSaved] 为真才能提示"语音已保存"，否则用户点进详情页会是一条没有音频的记录。
 */
data class SaveVoiceNoteResult(
    val recordId: String,
    val audioSaved: Boolean,
)
