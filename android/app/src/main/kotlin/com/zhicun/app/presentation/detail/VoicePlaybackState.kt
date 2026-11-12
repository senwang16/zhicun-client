package com.zhicun.app.presentation.detail

/**
 * 详情页的语音播放状态（M9）。
 *
 * 与 [RecordDetailUiState] 分开：播放进度每 300ms 变一次，塞进 Content 会让
 * 整页跟着重组（原文、附件、派生内容全部重算一遍）。独立一个 Flow 后，
 * 只有播放器那一段会随进度刷新。
 */
data class VoicePlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 音频文件不可用（被清理、路径失效）——界面要如实说"播不了"，而不是静默无反应。 */
    val unavailable: Boolean = false,
    /**
     * 当前状态属于哪个音频附件（2026-10-29 多音频）。
     *
     * 一条记录可以有多段录音，每个子卡只认领属于自己的进度与"不可用"提示；
     * `null` = 没有活动播放（老调用点不传，行为与单音频时一致）。
     */
    val activeAssetId: String? = null,
)
