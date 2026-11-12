package com.zhicun.app.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.audio.VoiceRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VoiceRecorder] 的 MediaRecorder 实现（M9）。
 *
 * ## 格式选择：AAC in MP4（`.m4a`）
 * - Android 全版本硬件编码，48kbps~64kbps 单声道即可听清人声；
 * - 1 分钟约 480KB，20 分钟的录音才接近网关 20MB 的上传上限；
 * - 相比 WAV（1 分钟约 5MB）省一个数量级，相比 Opus 兼容性更好（转写服务普遍支持）。
 *
 * ## 打断处理用音频焦点，而不是监听电话状态
 * `READ_PHONE_STATE` 在 Android 10+ 需要额外权限且国产 ROM 行为不一；而"来电/其它 App 抢音频"
 * 最终都会表现为音频焦点丢失。用 [AudioManager.requestAudioFocus] 是唯一跨 ROM 稳定的信号。
 * 这里只**通知**上层（[onInterrupted]），不自己决定停止还是丢弃 —— 那是业务规则，放在 ViewModel，
 * 才能在 JVM 单测里穷举"保留/丢弃"两条分支。
 *
 * ## 计时用 elapsedRealtime 而不是 currentTimeMillis
 * 用户手动改系统时间会让 `currentTimeMillis` 跳变，录音时长直接变成负数或几小时。
 */
@Singleton
class MediaRecorderVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecorder {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var focusRequest: AudioFocusRequest? = null

    private var startedAt: Long = 0L
    private var pausedAt: Long = 0L
    private var pausedTotal: Long = 0L

    /** 采集开始时间的墙钟值（PRD v1.2 §4.2）；0 = 尚未开始。 */
    private var captureStartedAt: Long = 0L

    /**
     * 本次录音是否已经运行中出错。
     *
     * 非空表示 [onError] 已回调过。用途有两个：
     * 1. `stop()` 在异常时据此判断"文件里可能还有出错前的有效数据"，不无脑删除；
     * 2. 幂等：同一个失败可能连续上报（错误回调 + stop 抛异常），只报一次。
     */
    private var errorReason: VoiceRecorder.ErrorReason? = null

    /**
     * 运行中出错的统一处理：先封口保数据，再回调上层。
     *
     * 顺序不能反 —— 回调里上层可能立刻调 `stop()`（"打断并保留"路径），
     * 那时 recorder 必须还处于可用状态，否则救不回来。
     */
    private fun handleRecorderError(what: Int, extra: Int) {
        if (errorReason != null) return // 已上报过，避免重复回调
        val reason = when (what) {
            MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN -> VoiceRecorder.ErrorReason.UNKNOWN
            else -> VoiceRecorder.ErrorReason.ENCODER_FAILED
        }
        errorReason = reason
        Log.e(TAG, "录音运行中出错 reason=$reason what=$what extra=$extra，已封口保数据")
        mainHandler.post { onError?.invoke(reason) }
    }

    override var onInterrupted: (() -> Unit)? = null

    /**
     * 录音器自身出错。
     *
     * 以前**没有任何**错误回调：Android 9+ 后台收麦克风时 `MediaRecorder` 只是
     * 内部失败，UI 的计时条照常走，直到用户按停止才发现 `stop()` 抛异常、
     * 文件是空的 —— 长录音一次丢光。
     *
     * 现在的约定是"先救再报"：回调之前先把已经写进文件的部分封口，
     * 上层因此还能提示"已保留前 N 秒"。
     */
    override var onError: ((VoiceRecorder.ErrorReason) -> Unit)? = null

    override fun start(outputFile: File): Boolean {
        // 上一次没清干净（例如页面被系统销毁）：先丢弃，避免两个 MediaRecorder 抢麦克风
        if (recorder != null) cancel()

        val created = createRecorder() ?: return false
        return try {
            outputFile.parentFile?.mkdirs()
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(outputFile.absolutePath)
                // 错误回调必须在 prepare() 之前注册：prepare/start 阶段的失败
                // 也会走这里（此前这些失败只能靠 start() 的 try/catch 兜住，
                // 而**运行中**的失败则完全静默）。
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder 错误 what=$what extra=$extra")
                    handleRecorderError(what, extra)
                    true
                }
                prepare()
                start()
            }

            recorder = created
            this.outputFile = outputFile
            startedAt = SystemClock.elapsedRealtime()
            captureStartedAt = System.currentTimeMillis()
            pausedAt = 0L
            pausedTotal = 0L
            errorReason = null
            requestAudioFocus()
            Log.i(TAG, "开始录音 ${outputFile.name}")
            true
        } catch (e: Exception) {
            // 麦克风被占用、编解码器不可用、存储不可写：都归为"开始失败"，界面给出人话提示
            Log.e(TAG, "开始录音失败", e)
            runCatching { created.release() }
            // prepare() 之前可能已经建出空文件；失败后没有任何人持有它，顺手清掉
            runCatching { outputFile.delete() }
            recorder = null
            this.outputFile = null
            captureStartedAt = 0L
            false
        }
    }

    override fun pause() {
        val active = recorder ?: return
        runCatching {
            active.pause()
            pausedAt = SystemClock.elapsedRealtime()
        }.onFailure { Log.w(TAG, "暂停录音失败", it) }
    }

    override fun resume() {
        val active = recorder ?: return
        runCatching {
            active.resume()
            if (pausedAt > 0) pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0L
        }.onFailure { Log.w(TAG, "恢复录音失败", it) }
    }

    override fun stop(): RecordedClip? {
        val active = recorder ?: return null
        val file = outputFile
        val duration = elapsedMs()
        // 先读结束时间：stop/release 的耗时不应算进"用户松手"的时刻
        val captureEndedAt = System.currentTimeMillis()

        val stopped = runCatching { active.stop() }
            .onFailure {
                // 录得太短（< 100ms）时 MediaRecorder.stop 会抛 RuntimeException，
                // 这时文件里没有可用数据，按"空录音"处理而不是崩溃
                Log.w(TAG, "停止录音失败（可能是录音过短）", it)
            }
            .isSuccess
        runCatching { active.release() }
        recorder = null
        outputFile = null
        val startedAtWallClock = captureStartedAt.takeIf { it > 0 }
        captureStartedAt = 0L
        abandonAudioFocus()

        if (!stopped) {
            // stop 抛异常 = 通常没有可用数据；这里不删就会在 cacheDir/voice-tmp 留下孤儿文件，
            // 而 ViewModel 拿到 null 后已不再持有路径，cancel()/onCleared() 都管不到它。
            //
            // 例外：如果录音是**运行中出错**（onError 已触发，errorReason 非空），文件里
            // 往往仍有出错前写好的有效数据 —— 此时删掉就等于把用户已经说出口的内容一起扔了。
            // 这种情况下按"能读多少算多少"返回，由上层按体积/时长判"太短"。
            val salvageable = errorReason != null && file != null && file.exists() && file.length() > 0
            if (!salvageable) {
                file?.delete()
                return null
            }
            Log.w(TAG, "停止时异常，但录音曾出错且文件非空，按可救片段返回 size=${file!!.length()}")
            return RecordedClip(
                file = file,
                durationMs = duration,
                sizeBytes = file.length(),
                captureStartedAt = startedAtWallClock,
                captureEndedAt = if (startedAtWallClock != null) captureEndedAt else null,
            )
        }
        if (file == null || !file.exists()) return null
        return RecordedClip(
            file = file,
            durationMs = duration,
            sizeBytes = file.length(),
            captureStartedAt = startedAtWallClock,
            captureEndedAt = if (startedAtWallClock != null) captureEndedAt else null,
        )
    }

    override fun cancel() {
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        abandonAudioFocus()
        outputFile?.delete()
        outputFile = null
        captureStartedAt = 0L
        errorReason = null
    }

    override fun salvage(): RecordedClip? = stop()

    override fun elapsedMs(): Long {
        if (recorder == null) return 0L
        val now = SystemClock.elapsedRealtime()
        val currentPause = if (pausedAt > 0) now - pausedAt else 0L
        return (now - startedAt - pausedTotal - currentPause).coerceAtLeast(0L)
    }

    override fun isRecording(): Boolean = recorder != null

    // --- 内部 ---

    private fun createRecorder(): MediaRecorder? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    } catch (e: Exception) {
        Log.e(TAG, "创建 MediaRecorder 失败", e)
        null
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> handleFocusChange(change) }
            .build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 没拿到焦点不算致命：多数设备仍允许录音，只是可能被其它声音盖住
            Log.w(TAG, "未取得音频焦点 result=$result")
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * 焦点变化。
     *
     * `LOSS_TRANSIENT`（来电、导航播报）与 `LOSS`（其它 App 长期播放）都要通知上层，
     * 但**不能在这里直接停**：停止会丢失"已经录到的部分"，而上层的规则是
     * "中断后让用户选择保留还是丢弃"。
     */
    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                Log.i(TAG, "音频焦点丢失 change=$change，通知上层中断")
                mainHandler.post { onInterrupted?.invoke() }
            }

            else -> Unit
        }
    }

    private companion object {
        const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 64_000
    }
}
