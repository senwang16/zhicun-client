package com.zhicun.app.presentation.voice

import androidx.annotation.StringRes

/**
 * 录音页状态（M9）。
 *
 * 用 sealed interface 而不是"布尔组合"：录音页有六个互斥阶段，
 * 其中"被打断"与"太短"都不是错误也不是成功，必须能各自带自己的界面与文案。
 * 布尔组合在这里会出现"isRecording=false && isSaving=false && hasError=true"
 * 这类无法对应到任何界面的非法态。
 */
sealed interface VoiceRecordUiState {

    /** 进入页面、权限确认中。 */
    data object Preparing : VoiceRecordUiState

    /**
     * 权限已就绪、还没开始录（2026-10-24 用户反馈）。
     *
     * 之前授权后立刻开麦：用户只是点进来看一眼，录音就已经跑起来了，
     * 退出还会留下一个临时文件。现在多一个"待开始"状态，由用户点按钮决定何时开录。
     */
    data object Ready : VoiceRecordUiState

    /** 正在录（[paused] 为 true 时是暂停态）。 */
    data class Recording(val elapsedMs: Long, val paused: Boolean) : VoiceRecordUiState

    /**
     * 录音被系统打断（来电、其它 App 抢占音频焦点）。
     *
     * 已录到的部分**已经停止并保留在临时文件里**，等用户选择保留还是丢弃 ——
     * 直接丢掉会让一次来电毁掉几分钟的口述，直接保留又可能在用户不知情时留下半截录音。
     */
    data class Interrupted(val elapsedMs: Long) : VoiceRecordUiState

    /** 保存中（附件复制 + 落库）。 */
    data object Saving : VoiceRecordUiState

    /** 录音太短/无内容：不创建记录（PRD：不产生无声记录）。 */
    data object TooShort : VoiceRecordUiState

    /** 麦克风权限被拒：不崩溃，给出说明与去设置的入口。 */
    data object PermissionDenied : VoiceRecordUiState

    /** 开始录音失败（麦克风被占用、编解码器不可用）。 */
    data class Failed(@param:StringRes val messageRes: Int) : VoiceRecordUiState
}

/** 一次性事件：导航由导航层决定。 */
sealed interface VoiceRecordEvent {

    /** 保存成功，参数是新记录 id（详情页可立即播放）。 */
    data class Saved(val recordId: String) : VoiceRecordEvent

    /** 放弃录音，退出页面。 */
    data object NavigateBack : VoiceRecordEvent
}
