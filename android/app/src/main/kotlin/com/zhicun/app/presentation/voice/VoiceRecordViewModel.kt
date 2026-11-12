package com.zhicun.app.presentation.voice

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.audio.VoiceRecorder
import com.zhicun.app.domain.audio.VoiceRecordingPolicy
import com.zhicun.app.domain.usecase.SaveVoiceNoteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 录音页 ViewModel（M9）。
 *
 * ## 职责边界
 * 录音/停止/暂停这些**能力**在 [VoiceRecorder]（data 层，碰 MediaRecorder）；
 * "中断后保留还是丢弃""太短的不建记录""保存失败保留什么"这些**规则**全在这里。
 * 因此本类可以在 JVM 单测里用假录音器穷举所有分支，不需要真实麦克风。
 *
 * ## 为什么打断由 ViewModel 而不是录音器决定
 * 录音器只上报"焦点丢了"，不自己停 —— 停了就丢失"已经录到的部分"。
 * 这里先 [VoiceRecorder.stop] 把已录内容封口，再进入 [VoiceRecordUiState.Interrupted]，
 * 把选择权交还用户。
 *
 * ## 临时文件
 * 录在 `cacheDir/voice-tmp/`，保存成功后由 `SaveVoiceNoteUseCase` 删除。
 * 文件名只含 ASCII（时间戳），拼 `file://` 不需要 URL 编码。
 */
@HiltViewModel
class VoiceRecordViewModel @Inject constructor(
    private val recorder: VoiceRecorder,
    private val saveVoiceNote: SaveVoiceNoteUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<VoiceRecordUiState>(VoiceRecordUiState.Preparing)
    val uiState: StateFlow<VoiceRecordUiState> = _uiState.asStateFlow()

    private val _events = Channel<VoiceRecordEvent>(Channel.BUFFERED)
    val events: Flow<VoiceRecordEvent> = _events.receiveAsFlow()

    private var ticker: Job? = null

    /** 被打断时封口下来的片段，等用户在对话框里做选择。 */
    private var interruptedClip: RecordedClip? = null

    init {
        recorder.onInterrupted = { onInterrupted() }
        recorder.onError = { onRecorderError() }
    }

    /**
     * 权限已授予：进入"待开始"，**不自动开麦**（2026-10-24 用户反馈）。
     *
     * 用户点"语音"只代表想看录音页，不代表已经开始录了；
     * 让他在看到提示后自己按下开始按钮，退出也不会留下没人处理的临时文件。
     */
    fun onPermissionGranted() {
        // 回调可能在页面已经进入录音/保存之后才到（系统权限弹窗返回），不能把状态打回去
        if (_uiState.value !is VoiceRecordUiState.Preparing) return
        _uiState.value = VoiceRecordUiState.Ready
    }

    /** 用户在"待开始"状态按下开始录音。 */
    fun onStartClicked() {
        if (_uiState.value !is VoiceRecordUiState.Ready) return

        val target = File(tempDir(), "voice-${System.currentTimeMillis()}.m4a")
        if (!recorder.start(target)) {
            _uiState.value = VoiceRecordUiState.Failed(R.string.voice_start_failed)
            return
        }
        _uiState.value = VoiceRecordUiState.Recording(elapsedMs = 0L, paused = false)
        startTicker()
    }

    fun onPermissionDenied() {
        _uiState.value = VoiceRecordUiState.PermissionDenied
    }

    /** 暂停/继续。 */
    fun onTogglePause() {
        val current = _uiState.value as? VoiceRecordUiState.Recording ?: return
        val nextPaused = !current.paused
        if (nextPaused) recorder.pause() else recorder.resume()
        // 必须 `update { }` 而不是 `value = current.copy(...)`（AGENTS.md §2.2）：
        // `current` 是"点击那一刻"的快照，而 ticker 每 200ms 也在改同一个 StateFlow。
        // 拿快照整块覆盖会把 ticker 刚写进去的 elapsedMs 冲回旧值，反过来 ticker 也会
        // 把 paused 冲回 false（2026-11-05 审核 CN-03）。
        _uiState.update { state ->
            (state as? VoiceRecordUiState.Recording)?.copy(paused = nextPaused) ?: state
        }
    }

    /** 用户点"停止"：封口 → 判断是否太短 → 保存。 */
    fun onStopClicked() {
        if (_uiState.value !is VoiceRecordUiState.Recording) return
        val clip = finishRecording() ?: return
        persistOrReject(clip)
    }

    /**
     * 录音被系统打断。
     *
     * 幂等：音频焦点可能连续回调多次（来电 + 其它 App 同时抢），
     * 只有第一次生效，否则第二次会对着已经释放的 recorder 再 stop 一次。
     */
    fun onInterrupted() {
        val current = _uiState.value as? VoiceRecordUiState.Recording ?: return
        val clip = finishRecording() ?: return

        if (isTooShort(clip)) {
            clip.file.delete()
            _uiState.value = VoiceRecordUiState.TooShort
            return
        }
        interruptedClip = clip
        _uiState.value = VoiceRecordUiState.Interrupted(elapsedMs = clip.durationMs)
        Log.i(TAG, "录音被中断，已保留 ${current.elapsedMs}ms 的片段")
    }

    /** 打断对话框：保留并保存。 */
    fun onKeepInterrupted() {
        val clip = interruptedClip ?: return
        interruptedClip = null
        persistOrReject(clip)
    }
    /**
     * 录音器自身出错（麦克风被系统收回 / 编码器故障 / 存储写满）。
     *
     * 复用 [VoiceRecordUiState.Interrupted]：对用户而言"录音意外停了，前面这段存不存"
     * 与来电打断是同一件事，不该为故障多造一套界面。区别只在于出错时录音器**已经**
     * 无法继续，所以必须先封口（[VoiceRecorder.stop] 在错误态下按可救片段返回）。
     *
     * 一点都救不回来（封口返回 null）时如实报错，不伪装成"已保留"。
     */
    fun onRecorderError() {
        val current = _uiState.value as? VoiceRecordUiState.Recording ?: return
        ticker?.cancel()
        ticker = null
        val clip = recorder.stop()
        if (clip == null || isTooShort(clip)) {
            clip?.file?.delete()
            _uiState.value = VoiceRecordUiState.Failed(R.string.voice_stop_failed)
            return
        }
        interruptedClip = clip
        _uiState.value = VoiceRecordUiState.Interrupted(elapsedMs = clip.durationMs)
        Log.e(TAG, "录音器出错，已保留 ${clip.durationMs}ms 的片段")
    }

    /** 打断对话框：丢弃并退出。 */
    fun onDiscardInterrupted() {
        interruptedClip?.file?.delete()
        interruptedClip = null
        viewModelScope.launch { _events.send(VoiceRecordEvent.NavigateBack) }
    }

    /** 返回/取消：丢弃当前录音（用户明确表达了"不要"）。 */
    fun onCancel() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        interruptedClip?.file?.delete()
        interruptedClip = null
        viewModelScope.launch { _events.send(VoiceRecordEvent.NavigateBack) }
    }

    /** "录音太短"提示确认后退出。 */
    fun onTooShortAcknowledged() {
        viewModelScope.launch { _events.send(VoiceRecordEvent.NavigateBack) }
    }

    override fun onCleared() {
        super.onCleared()
        ticker?.cancel()
        // 页面被销毁但没走保存路径：释放麦克风，但**不许删掉已录内容**。
        // 之前这里直接 cancel()，而 cancel 会 stop + release + 删文件 ——
        // 用户切走应用、Activity 被回收时，已经说出口的内容被静默删除。
        releaseWithoutLosingAudio()
        recorder.onInterrupted = null
        // 录音器是单例：不还原 onError 会让下一次全屏录音再也收不到错误回调
        recorder.onError = null
    }

    // --- 内部 ---

    /**
     * 销毁时收尾：把未认领的音频从 cacheDir 挪到 filesDir，而不是删除。
     *
     * `cacheDir` 会被系统在空间紧张时清掉，而这份可能是用户唯一一份录音。
     * 抽成 internal 而不是写死在 [onCleared] 里 —— onCleared 是 protected，
     * 测试没法触发，而"销毁时不许删音频"正是这次修复的核心。
     */
    internal fun releaseWithoutLosingAudio() {
        val clip = interruptedClip
        interruptedClip = null
        val salvaged = clip ?: recorder.salvage()
        if (salvaged == null) {
            // 确实没有可救内容（从未开始 / 文件为空）才真正丢弃
            recorder.cancel()
            return
        }
        runCatching {
            val target = File(orphanDir(), salvaged.file.name)
            salvaged.file.copyTo(target, overwrite = true)
            salvaged.file.delete()
            Log.w(TAG, "页面销毁时保留未认领录音：${target.absolutePath}")
        }.onFailure { Log.e(TAG, "保留未认领录音失败", it) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (_uiState.value !is VoiceRecordUiState.Recording) break
                // 时长从录音器读，而不是累加 tick：界面卡顿/系统调度延迟不会让计时偏慢
                val elapsed = recorder.elapsedMs()
                // 只改 elapsedMs，其余字段从**当前**状态取（2026-11-05 审核 CN-03）：
                // 闭包里捕获的 `current` 是上一 tick 的快照，用它 copy 会把用户在这期间
                // 点的"暂停"覆盖回 false —— 表现为录音停了、界面还在计时。
                _uiState.update { state ->
                    (state as? VoiceRecordUiState.Recording)?.copy(elapsedMs = elapsed) ?: state
                }
                // 到顶自动收尾（VoiceRecordingPolicy.D-2）：走"用户点停止"的同一条
                // 保存路径，保证录音大小永远落在转写可接受的范围内（≤20MB）
                if (elapsed >= VoiceRecordingPolicy.MAX_DURATION_MS) {
                    onStopClicked()
                    return@launch
                }
            }
        }
    }

    /** 停止录音并做"是否有内容"的初判；null 表示封口失败（已切到失败态）。 */
    private fun finishRecording(): RecordedClip? {
        ticker?.cancel()
        ticker = null
        val clip = recorder.stop()
        if (clip == null) {
            _uiState.value = VoiceRecordUiState.Failed(R.string.voice_stop_failed)
            return null
        }
        return clip
    }

    private fun persistOrReject(clip: RecordedClip) {
        if (isTooShort(clip)) {
            clip.file.delete()
            _uiState.value = VoiceRecordUiState.TooShort
            return
        }
        _uiState.value = VoiceRecordUiState.Saving
        viewModelScope.launch {
            try {
                val result = saveVoiceNote(clip)
                // 记录已落库但音频复制失败：如实报错，不能导航到详情页后让用户发现没有声音
                if (!result.audioSaved) {
                    Log.e(TAG, "录音附件落盘失败 recordId=${result.recordId}")
                    _uiState.value = VoiceRecordUiState.Failed(R.string.voice_audio_save_failed)
                    return@launch
                }
                _events.send(VoiceRecordEvent.Saved(result.recordId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "保存录音失败", e)
                // 临时文件保留：用户还能再点一次"停止"吗？不能（录音已结束），
                // 但文件留在 cacheDir 里至少还有人工恢复的可能，不做静默删除
                _uiState.value = VoiceRecordUiState.Failed(R.string.voice_save_failed)
            }
        }
    }

    /**
     * 太短的判定：时长与体积**任一**不达标即视为空录音。
     *
     * 只看时长：某些设备静音时会产出 0 字节但时长正常的文件；
     * 只看体积：低码率录音 300ms 也可能超过 1KB，但那是有效内容。
     */
    private fun isTooShort(clip: RecordedClip): Boolean =
        clip.durationMs < MIN_DURATION_MS || clip.sizeBytes < MIN_BYTES

    private fun tempDir(): File = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }

    /**
     * 未认领录音的安置目录。
     *
     * 用 `filesDir` 而不是 `cacheDir`：后者会被系统在空间紧张时清掉，
     * 而这份音频可能是用户唯一一份。
     */
    private fun orphanDir(): File = File(context.filesDir, ORPHAN_DIR).apply { mkdirs() }

    /**
     * 常量对外暴露为 `internal`：`ORPHAN_DIR` 是"销毁时音频被安置到哪"的契约，
     * 必须能被测试直接断言 —— 把它藏在 `private companion` 里，就只剩"实现怎么写都算对"。
     */
    internal companion object {
        const val TAG = "VoiceRecord"
        const val TICK_MS = 200L
        const val MIN_DURATION_MS = 500L
        const val MIN_BYTES = 1024L
        const val TEMP_DIR = "voice-tmp"

        /** 未认领录音的安置目录（`filesDir` 下，不会被系统清缓存删掉）。 */
        const val ORPHAN_DIR = "orphaned-voice"
    }
}
