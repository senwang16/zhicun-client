package com.zhicun.app.domain.audio

import java.io.File

/**
 * 录音能力（domain 契约，data 层用 MediaRecorder 实现）。
 *
 * 抽接口的理由与 OCR 一致：`MediaRecorder` 必须跑在真实设备上（模拟器/单元测试里没有麦克风），
 * 而"录音中断要不要保留""太短的录音不建记录"是业务规则，值得用纯 JVM 单测穷举。
 *
 * 约定：
 * - [start] 之前必须已拿到 `RECORD_AUDIO` 权限；无权限时返回 `false`，不要抛异常
 *   （权限被拒是用户的选择，不是程序错误）。
 * - [stop] 返回已落盘的片段；录音被中断时同样返回**已经录到的部分**（PRD：来电打断不能丢内容）。
 * - [cancel] 丢弃并删除临时文件。
 */
interface VoiceRecorder {

    /** 录音被系统打断（来电、音频焦点被抢）。实现方在**主线程**回调。 */
    var onInterrupted: (() -> Unit)?

    /**
     * 录音器**自己**出错。实现方在**主线程**回调。
     *
     * 与 [onInterrupted] 的区别：后者是"外部抢走了音频"，录音本身是好的；
     * 这里是"录音已经废了"—— 麦克风被系统收回（Android 9+ 后台限制）、
     * 编码器故障、存储写满。此前没有任何回调，于是 UI 的计时条照常走、
     * 用户按停止时才在 [stop] 里发现文件是空的，长录音一次丢光。
     *
     * 实现方在回调**之前**已经把能救的部分封口落盘（见 [stop] 的语义），
     * 上层据此提示"录音中断，已保留前 N 秒"并让用户决定。
     */
    var onError: ((ErrorReason) -> Unit)?

    /** 录音失败的原因（只用于选择文案，不参与任何业务判断）。 */
    enum class ErrorReason {
        /** 麦克风被系统收回 / 音频输入被抢占（后台录音限制最常见的一种）。 */
        INPUT_LOST,

        /** 编码器或容器写入失败（编解码器不可用、存储写满）。 */
        ENCODER_FAILED,

        /** 其它未知错误（保留一个兜底，避免为每个厂商 bug 加一个枚举）。 */
        UNKNOWN,
    }

    /**
     * 开始录音，写入 [outputFile]（调用方负责目录与文件名）。
     *
     * @return false = 无法开始（无权限 / 麦克风被占用 / 编解码器不可用）
     */
    fun start(outputFile: File): Boolean

    fun pause()

    fun resume()

    /**
     * 停止并返回结果。
     *
     * @return null = 从未成功开始，或**录音已被判为不可用**（[onError] 已回调过且无可救内容）
     */
    fun stop(): RecordedClip?

    /** 丢弃当前录音并删除文件。 */
    fun cancel()

    /** 已录制时长（不含暂停区间），用于界面计时。 */
    fun elapsedMs(): Long

    /** 当前是否处于录音/暂停状态。 */
    fun isRecording(): Boolean

    /**
     * 把已经录到的部分封口，**但不 release、不删除文件**。
     *
     * 用于"页面被销毁 / 服务被回收，但录音还在进行"的场景：此时不该像 [cancel] 那样
     * 把音频删掉 —— 那是用户已经说出口的内容。调用方负责后续落盘或清理。
     *
     * @return 已封口的片段；没有可救的内容时返回 null
     */
    fun salvage(): RecordedClip? = stop()
}

/**
 * 一段录制完成的音频。
 *
 * [durationMs] 与 [sizeBytes] 一起用于判断"这是不是一段空录音"：
 * 只看时长挡不住设备在静音时的极短文件，只看体积又可能误杀低码率的长录音。
 */
data class RecordedClip(
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String = "audio/mp4",
    /**
     * 采集开始/结束的墙钟时间（epoch millis，2026-10-27，PRD v1.2 §4.2）。
     *
     * 与 [durationMs] 的区别：后者来自单调时钟、不含暂停，用于计时与"是否太短"；
     * 这里回答"用户什么时候按下、什么时候松手"，用户改系统时间也不影响时长判定。
     * 老实现/假实现可以不提供（null），保存时就不写这两列。
     */
    val captureStartedAt: Long? = null,
    val captureEndedAt: Long? = null,
)
