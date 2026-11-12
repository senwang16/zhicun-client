package com.zhicun.app.presentation.detail

import com.zhicun.app.domain.audio.AudioPlayer
import com.zhicun.app.domain.model.Asset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 详情页语音播放中枢（M9）—— 从 `RecordDetailViewModel` 抽出（AGENTS.md §2.1 单文件上限）。
 *
 * 纯移动、零行为变更：状态机（播放 / 暂停 / 播完复位 / 文件丢失 / 被另一页接管）
 * 与原先逐行等价。抽出来的好处是 ViewModel 只剩"页面数据 + 用户动作分发"。
 *
 * 线程与生命周期约定：
 * - 只挂调用方传进来的 scope（生产是 `viewModelScope`，测试是 `TestScope`），
 *   本类不自建 scope，也不用 `GlobalScope`（AGENTS.md §8）。
 * - ticker 的取消由 [release] 与 [stopTicker] 负责，[release] 必须在 `onCleared` 调用。
 */
internal class DetailAudioPlayback(
    private val audioPlayer: AudioPlayer,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    private var ticker: Job? = null

    /** 当前播放器正在播放的音频路径：暂停后据此判断"续播"还是"从头播"（QODER P2-5）。 */
    private var activeAudioPath: String? = null

    /**
     * 播完自动复位：停在"播放"按钮且进度归零，用户不需要手动再点一次。
     *
     * `onCompleted` 是播放器上的**单例可变属性**，后进详情页会覆盖前一页的回调，
     * 所以 [release] 必须把它置空，否则旧页面被回收后仍会收到回调。
     */
    fun attachCompletionReset() {
        audioPlayer.onCompleted = {
            ticker?.cancel()
            ticker = null
            activeAudioPath = null
            _state.update {
                VoicePlaybackState(
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = it.durationMs,
                )
            }
        }
    }

    /**
     * 播放 / 暂停语音（M9）。
     *
     * 只在当前记录确实是语音、且音频附件真的存在时才动手；
     * 音频文件丢失时把 [VoicePlaybackState.unavailable] 置起来，界面显示"不可用"，
     * 而不是点了没反应。
     *
     * @param assets 当前页附件（由 ViewModel 从 Content 状态取，保持单一数据源）
     * @param assetId 要点的是哪一段音频（2026-10-29 多音频）；`null` = 第一段，兼容老调用点
     */
    fun onPlayPauseClicked(assets: List<Asset>, assetId: String? = null) {
        val audio = if (assetId == null) {
            assets.firstOrNull { it.isAudio }
        } else {
            assets.firstOrNull { it.id == assetId && it.isAudio }
        }

        // 点到正在播的那一段 = 暂停；点到另一段 = 切换（同一时间只播一段）
        if (_state.value.isPlaying && (audio == null || audio.id == _state.value.activeAssetId)) {
            // 同一判据：只有播放器里装的仍是本页文件时才允许暂停，
            // 否则会把已经接管播放器的另一页暂停掉（deepseek 审查 P3 的同源路径）
            val ownsPlayer = activeAudioPath != null && audioPlayer.currentPath == activeAudioPath
            if (ownsPlayer) audioPlayer.pause()
            stopTicker()
            if (!ownsPlayer) activeAudioPath = null
            _state.update {
                it.copy(
                    isPlaying = false,
                    positionMs = if (ownsPlayer) audioPlayer.positionMs() else 0L,
                )
            }
            return
        }
        if (_state.value.isPlaying) {
            // 切换到另一段：先停掉上一段，避免两段同时出声
            val ownsPlayer = activeAudioPath != null && audioPlayer.currentPath == activeAudioPath
            if (ownsPlayer) audioPlayer.pause()
            stopTicker()
            activeAudioPath = null
        }

        if (audio == null || audio.localPath.isBlank()) {
            _state.update {
                VoicePlaybackState(
                    unavailable = true,
                    activeAssetId = audio?.id ?: assetId,
                )
            }
            return
        }

        // 暂停后再次点击 = 从上次位置续播；换个文件或从未播放过 = 从头播（QODER P2-5）。
        // 还必须确认单例播放器里装的仍是本页文件：叠栈时另一页可能已经接管，
        // 直接 resume() 会把别人的音频续播起来（2026-10-24 deepseek 审查 P3）。
        val resumed = activeAudioPath == audio.localPath &&
            audioPlayer.currentPath == audio.localPath &&
            audioPlayer.resume()
        if (!resumed && !audioPlayer.play(audio.localPath)) {
            _state.update {
                VoicePlaybackState(unavailable = true, activeAssetId = audio.id)
            }
            return
        }

        activeAudioPath = audio.localPath
        // 先把会变的量取出来，再进 update 块：块内可能被重入，读播放器要留快照
        val position = if (resumed) audioPlayer.positionMs() else 0L
        val duration = audioPlayer.durationMs()
        _state.update {
            VoicePlaybackState(
                isPlaying = true,
                positionMs = position,
                durationMs = duration,
                activeAssetId = audio.id,
            )
        }
        startTicker()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                delay(PLAYBACK_TICK_MS)
                val ownPath = activeAudioPath
                // 单例播放器被另一个详情页接管（play 会先停掉本页文件）：
                // 本页既不能镜像它的进度，也不能等它播完再复位 —— 直接回到未播放。
                if (ownPath != null && audioPlayer.currentPath != ownPath) {
                    activeAudioPath = null
                    _state.update {
                        VoicePlaybackState(
                            isPlaying = false,
                            positionMs = 0L,
                            durationMs = it.durationMs,
                        )
                    }
                    break
                }
                if (!audioPlayer.isPlaying()) {
                    // 兜底复位：播放器是单例，从本页跳到相关记录详情再返回时，
                    // onCompleted 回调可能已被另一页的 onCleared 置空 —— 没有回调通知
                    // 结束，界面会永远停在"播放中 + 冻结进度"（r4 审查 O-1）。
                    // ticker 是最后一个知道"已经不在播了"的人，由它收尾。
                    if (_state.value.isPlaying) {
                        activeAudioPath = null
                        _state.update {
                            VoicePlaybackState(
                                isPlaying = false,
                                positionMs = 0L,
                                durationMs = it.durationMs,
                            )
                        }
                    }
                    break
                }
                _state.update {
                    it.copy(
                        positionMs = audioPlayer.positionMs(),
                        durationMs = audioPlayer.durationMs(),
                    )
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 页面销毁：停 ticker、摘回调、释放播放器（漏掉会一直占着音频焦点，别的 App 播不出声）。 */
    fun release() {
        stopTicker()
        audioPlayer.onCompleted = null
        audioPlayer.release()
    }

    private companion object {
        const val PLAYBACK_TICK_MS = 300L
    }
}
