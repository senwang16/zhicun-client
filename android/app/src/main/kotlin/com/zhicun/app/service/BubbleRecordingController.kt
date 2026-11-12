package com.zhicun.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat

import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import com.zhicun.app.R
import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.audio.VoiceRecorder
import com.zhicun.app.domain.audio.VoiceRecordingPolicy
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.usecase.SaveVoiceNoteUseCase
import com.zhicun.app.presentation.floating.BubbleRecordingBar
import com.zhicun.app.presentation.theme.AppTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 悬浮气泡的「长按录音」编排（2026-10-26）。
 *
 * ## 职责
 * 长按判定成立后开始录音、抬手后停止并保存；录音计时与一次性提示显示在**屏幕顶部**的
 * 独立窗口（[BubbleRecordingBar]），不画在气泡上。气泡只通过 [recording] 状态变色。
 *
 * ## 保存链路
 * 走既定的「先本地落盘、再排队云端」——直接复用 [SaveVoiceNoteUseCase]，
 * 来源标记为 [CaptureSource.FLOATING]，云端闸门不新增旁路。
 *
 * ## 过短阈值
 * 1 秒（本组件口径），与全屏录音页的 500ms 有意**不统一**（2026-10-26 产品决策）。
 *
 * ## 与全屏录音页的互斥
 * 全屏录音页在录时，长按气泡不抢占麦克风，只提示"正在录音中"；反向（录音中回录音页）
 * 实际不会发生——录音期间用户手指按在气泡上，无法再去点录音页。
 */
class BubbleRecordingController(
    private val service: FloatingBubbleService,
    private val recorder: VoiceRecorder,
    private val saveVoiceNote: SaveVoiceNoteUseCase,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 录音中：气泡据此变红（不显示计时数字，计时在顶部条）。 */
    val recording = mutableStateOf(false)

    /** 已录时长（毫秒）：供顶部条刷新。 */
    val elapsedMs = mutableStateOf(0L)

    /** 顶部条的一次性提示文案；录音中为 null。 */
    private val message = mutableStateOf<String?>(null)

    private var barView: ComposeView? = null
    private var ticker: Job? = null
    private var hideMessageJob: Job? = null

    /**
     * 录音前的 [VoiceRecorder.onInterrupted] 持有者。
     *
     * 录音器是单例，全屏录音页也会注册同一个回调。气泡录音临时接管后必须**还原**
     * 而不是置空：否则用户回到仍存活的全屏录音页时，来电打断将不再被通知。
     */
    private var previousInterruptHandler: (() -> Unit)? = null

    /**
     * 录音前的 [VoiceRecorder.onError] 持有者。
     *
     * 与 [previousInterruptHandler] 同理且**必须成对处理**：录音器是单例，
     * 只接管不还原会让全屏录音页再也收不到"录音器已废"的通知 ——
     * 那正是"长录音一次丢光"那条修复的关键回调。
     */
    private var previousErrorHandler: ((VoiceRecorder.ErrorReason) -> Unit)? = null

    // ------------------------------------------------------------ 生命周期

    /** 长按判定成立：开始录音。 */
    fun start() {
        if (recording.value) return
        // 「长按录音」开关为关时静默忽略（手势仍不会触发点击，符合 spec AC-2.15）
        if (!service.settings.longPressRecord) return

        if (!hasMicPermission(service)) {
            showMessage(service.getString(R.string.floating_record_need_permission))
            requestMicPermission()
            return
        }
        if (recorder.isRecording()) {
            showMessage(service.getString(R.string.floating_record_busy))
            return
        }

        val target = File(tempDir(service), "voice-${System.currentTimeMillis()}.m4a")
        // 先升级前台服务类型，再开麦：Android 14 起后台访问麦克风要求 FGS 已带
        // microphone 类型，反过来会让 MediaRecorder.start 直接失败（PRD v1.2 §4.3）。
        try {
            service.startFloatingForeground(withMicrophone = true)
        } catch (e: Exception) {
            Log.e(TAG, "前台服务升级 microphone 类型失败", e)
            showMessage(service.getString(R.string.floating_record_start_failed))
            return
        }
        previousInterruptHandler = recorder.onInterrupted
        previousErrorHandler = recorder.onError
        if (!recorder.start(target)) {
            restoreRecorderHandlers()
            downgradeForeground()
            showMessage(service.getString(R.string.floating_record_start_failed))
            return
        }
        // 来电 / 音频焦点被抢时停止并保存：不注册的话 [onInterrupted] 永远不会被调用，
        // 悬浮录音会一直占着麦克风（PRD v1.2 §4.3）。
        recorder.onInterrupted = { onInterrupted() }
        // 录音器自身出错（麦克风被收回等）：与打断走同一条收尾路径 ——
        // 先封口保数据再提示，不能让悬浮录音无声无息地失败
        recorder.onError = { onRecorderError() }
        message.value = null
        elapsedMs.value = 0L
        recording.value = true
        ensureBar()
        startTicker()
        Log.i(TAG, "悬浮录音开始")
    }

    /** 抬手：停止录音并按结果保存 / 提示太短。 */
    fun stop() {
        if (!recording.value) return
        stopTicker()
        recording.value = false
        restoreRecorderHandlers()
        // 先停录音再降类型：顺序反了会让麦克风在录音仍持有时被系统收回
        val clip = recorder.stop()
        downgradeForeground()
        if (clip == null) {
            showMessage(service.getString(R.string.floating_record_save_failed))
            return
        }
        persist(clip, interrupted = false)
    }

    /** 录音被系统打断（来电 / 音频焦点被抢）。 */
    fun onInterrupted() {
        if (!recording.value) return
        stopTicker()
        recording.value = false
        restoreRecorderHandlers()
        val clip = recorder.stop()
        downgradeForeground()
        if (clip == null) {
            // 录音已结束但没拿到可用的音频：顶部条必须收尾（2026-10-28 修复）。
            // 只置 recording=false 而不移除窗口，会留下一个既没有“录音中”也没有文案的空胶囊。
            showMessage(service.getString(R.string.floating_record_save_failed))
            return
        }
        persist(clip, interrupted = true)
    }

    /**
     * 录音器自身出错（麦克风被系统收回 / 编码器故障）。
     *
     * 与 [onInterrupted] 走同一条收尾路径：封口 → 降级前台 → 保存可救片段。
     * 差别只在提示语——"被打断"是外部原因，"出错"要让用户知道设备/系统这一侧出了问题。
     */
    fun onRecorderError() {
        if (!recording.value) return
        stopTicker()
        recording.value = false
        restoreRecorderHandlers()
        val clip = recorder.stop()
        downgradeForeground()
        if (clip == null) {
            showMessage(service.getString(R.string.floating_record_save_failed))
            return
        }
        persist(clip, interrupted = true)
    }

    /** 服务销毁：释放麦克风、移除窗口、结束协程。 */
    fun onDestroy() {
        stopTicker()
        hideMessageJob?.cancel()
        hideMessageJob = null
        if (recording.value) {
            recording.value = false
            // 服务被系统回收/强停时没有界面可提示，但**已录到的内容不能删**。
            // 与全屏录音页同口径：salvage 封口后移到 filesDir/orphaned-voice
            // （cacheDir 会被系统清掉，而这份可能是唯一一份）。
            preserveUnclaimedAudio(recorder.salvage())
        }
        restoreRecorderHandlers()
        removeBar()
        scope.cancel()
    }

    /**
     * 把"录音已结束但没人认领"的文件从 cacheDir 挪到 filesDir。
     *
     * 销毁路径没有机会建记录/弹提示，最低限度是**保住音频**：
     * 用户下次录音/清理时至少还能在 `orphaned-voice` 找到它。
     * 复制失败时保留 cacheDir 原文件（不删），下次启动仍可能被人工恢复。
     */
    private fun preserveUnclaimedAudio(clip: RecordedClip?) {
        if (clip == null) {
            // 确实没有可救内容（从未开始 / 文件为空）才真正丢弃
            recorder.cancel()
            return
        }
        runCatching {
            val target = File(orphanDir(), "floating-${System.currentTimeMillis()}-${clip.file.name}")
            clip.file.copyTo(target, overwrite = false)
            clip.file.delete()
            Log.w(TAG, "悬浮录音销毁时保留未认领音频：${target.absolutePath}")
        }.onFailure { Log.e(TAG, "保留悬浮录音失败，原文件仍在 cacheDir", it) }
    }

    /** 还原录音前的回调（全屏录音页可能还在后台存活）。 */
    private fun restoreRecorderHandlers() {
        recorder.onInterrupted = previousInterruptHandler
        previousInterruptHandler = null
        recorder.onError = previousErrorHandler
        previousErrorHandler = null
    }

    // ------------------------------------------------------------ 保存

    private fun persist(clip: com.zhicun.app.domain.audio.RecordedClip, interrupted: Boolean) {
        if (clip.durationMs < MIN_DURATION_MS || clip.sizeBytes < MIN_BYTES) {
            // 过短：不建记录，但必须明确提示（否则用户以为功能坏了）
            clip.file.delete()
            showMessage(service.getString(R.string.floating_record_too_short))
            return
        }
        // 计时条先收起（录音已结束），再异步落盘并提示结果
        hideBarIfNotRecording()
        scope.launch {
            try {
                val result = saveVoiceNote(clip, captureSource = CaptureSource.FLOATING)
                // 记录建了但音频没落盘：不能提示"语音已保存"（PRD v1.2 §4.2 / §4.3）
                if (!result.audioSaved) {
                    Log.e(TAG, "录音附件落盘失败 recordId=${result.recordId}")
                    showMessage(service.getString(R.string.floating_record_audio_failed))
                    return@launch
                }
                showMessage(
                    service.getString(
                        if (interrupted) {
                            R.string.floating_record_interrupted_saved
                        } else {
                            R.string.floating_record_saved
                        },
                    ),
                )
            } catch (e: CancellationException) {
                // 服务销毁导致的取消必须继续上抛，不能被当成"保存失败"吞掉
                throw e
            } catch (e: Exception) {
                // 保存失败：保留临时文件（用户还有人工恢复的可能），如实提示，不谎报"已保存"
                Log.e(TAG, "悬浮录音保存失败", e)
                showMessage(service.getString(R.string.floating_record_save_failed))
            }
        }
    }

    // ------------------------------------------------------------ 计时

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val elapsed = recorder.elapsedMs()
                elapsedMs.value = elapsed
                // 到顶自动收尾（VoiceRecordingPolicy.D-2）：与抬手停止完全同一条
                // 保存路径，保证悬浮录音的大小永远落在转写可接受的范围内（≤20MB）
                if (elapsed >= VoiceRecordingPolicy.MAX_DURATION_MS) {
                    stop()
                    return@launch
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 录音结束：前台服务类型降回 specialUse（不降会一直占用 microphone 类型）。 */
    private fun downgradeForeground() {
        try {
            service.startFloatingForeground(withMicrophone = false)
        } catch (e: Exception) {
            // 降级失败不影响已保存的音频，但必须留痕（否则服务可能一直占用麦克风类型）
            Log.e(TAG, "前台服务类型降级失败", e)
        }
    }

    // ------------------------------------------------------------ 顶部条窗口

    private fun ensureBar() {
        if (barView != null) return
        val view = ComposeView(service)
        service.composeHost.installOn(view)
        view.setContent {
            AppTheme {
                BubbleRecordingBar(
                    recording = recording.value,
                    elapsedMs = elapsedMs.value,
                    message = message.value,
                )
            }
        }
        service.windowManager.addViewSafely(view, barLayoutParams(service)) { service.stopSelf() }
        barView = view
    }

    private fun removeBar() {
        barView?.let { service.windowManager.removeViewSafely(it) }
        barView = null
    }

    private fun hideBarIfNotRecording() {
        if (!recording.value) removeBar()
    }

    /** 顶部居中、触摸穿透的窗口参数（每次读当前状态栏高度，旋转/分屏后仍贴顶）。 */
    private fun barLayoutParams(ctx: Context): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点；NOT_TOUCHABLE 让点击穿透到下层应用（spec AC-3.6）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 水平居中由系统完成，因此旋转/分屏后无需自己算 x，也不会停在旧坐标
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx(ctx) + topMarginPx(ctx)
        }

    // ------------------------------------------------------------ 一次性提示

    private fun showMessage(text: String) {
        message.value = text
        ensureBar()
        // 点消失后移除：若此刻正在录音，则保留（顶部条仍是录音态）
        hideMessageJob?.cancel()
        hideMessageJob = scope.launch {
            delay(MESSAGE_MS)
            message.value = null
            hideBarIfNotRecording()
        }
    }

    // ------------------------------------------------------------ 权限 / 工具

    private fun requestMicPermission() {
        val intent = Intent(service, MicPermissionProxyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "拉起麦克风权限中转页失败", e)
        }
    }

    private fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, MIC_PERMISSION) == PackageManager.PERMISSION_GRANTED


    private fun tempDir(ctx: Context): File = File(ctx.cacheDir, TEMP_DIR).apply { mkdirs() }

    /**
     * 未认领录音的安置目录（`filesDir` 下，不会被系统清缓存删掉）。
     *
     * 名称与全屏录音页的 `orphaned-voice` 保持一致：两处产物落在同一处，
     * 将来要做一个"恢复未认领录音"的入口时不用再猜目录。
     */
    private fun orphanDir(): File = File(service.filesDir, ORPHAN_DIR).apply { mkdirs() }

    private fun statusBarHeightPx(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    private fun topMarginPx(ctx: Context): Int = (8 * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BubbleRecording"

        /** 本组件"太短"阈值：1 秒（与全屏录音页的 500ms 有意不统一）。 */
        const val MIN_DURATION_MS = 1000L
        private const val MIN_BYTES = 1024L
        private const val TICK_MS = 200L
        private const val MESSAGE_MS = 2500L
        private const val TEMP_DIR = "voice-tmp"

        /** 未认领录音的安置目录（与全屏录音页一致）。 */
        private const val ORPHAN_DIR = "orphaned-voice"
        const val MIC_PERMISSION = android.Manifest.permission.RECORD_AUDIO
    }
}