package com.zhicun.app.domain.audio

/**
 * 语音录音的产品级约束（2026-11-01 用户拍板 D-2；2026-11-03 改为 5 分钟，自海外轨同步）。
 *
 * ## 为什么是 5 分钟
 * 转写走云端 ASR，一次请求要在客户端 `callTimeout` 120s、服务端上游超时 110s 内完成。
 * 5 分钟 @64kbps（`MediaRecorderVoiceRecorder.BIT_RATE`）≈ 2.4MB，
 * 转完只需 RTF < 0.37 —— 任何主流模型都能过。30 分钟则要 RTF < 0.061，
 * 几乎必然超时，而超时错误码 `PROVIDER_TEMPORARY_ERROR` 是**可重试**的，
 * 客户端会把几十 MB 音频重传 3 次才终态失败。
 *
 * 录音硬顶与 [TranscriptionPolicy] 的上限取同一个值：**录得下就转得了**，
 * 不存在"录完发现转不了"的窗口。两者必须相等，有单测钉住。
 *
 * ## 为什么还要 [TranscriptionPolicy] 的体积上限
 * 5 分钟是时间维度；同样 5 分钟的 WAV 44.1kHz 立体声约 52MB，仍会撞网关 20MB 墙。
 * 时间与体积两个条件**任一**不满足就不转写（音频照常保存与播放）。
 */
object VoiceRecordingPolicy {

    /** 单次录音的最长时长：5 分钟（到顶自动停止并保存）。 */
    const val MAX_DURATION_MS: Long = 5 * 60_000L
}