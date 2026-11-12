package com.zhicun.app.fake

import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.audio.VoiceRecorder
import java.io.File

/**
 * 录音器假实现（M9）。
 *
 * 只模拟"状态与产物"，不碰麦克风：录音是否成功、录了多久、停止时返回什么，
 * 全部由测试决定。这样录音页状态机的每个分支都能在 JVM 上跑到。
 */
class FakeVoiceRecorder : VoiceRecorder {

    var startSucceeds: Boolean = true
    var elapsed: Long = 0L
    var stopResult: RecordedClip? = null

    var recording: Boolean = false
    var paused: Boolean = false
    var cancelCount: Int = 0

    /**
     * 每读一次 [elapsedMs] 就回调一次（null = 不回调）。
     *
     * 用来**确定性地**制造"读时长 → 用户操作 → 回写"的交错：ticker 读到时长之后、
     * 写回 UI 状态之前，测试在这里插入一次"点暂停"。真实设备上这个窗口只有几毫秒，
     * 靠 `advanceTimeBy` 是撞不上的 —— 那会写出"看起来在测竞态、其实永远绿"的假用例
     * （2026-11-05 审核 CN-03 的回归测试就是这么漏掉的）。
     */
    var onElapsedRead: (() -> Unit)? = null

    /** [salvage] 被调用的次数：用来断言"销毁时走的是 salvage 而不是 cancel"。 */
    var salvageCount: Int = 0
    val startedFiles: MutableList<File> = mutableListOf()

    override var onInterrupted: (() -> Unit)? = null

    override var onError: ((VoiceRecorder.ErrorReason) -> Unit)? = null

    override fun start(outputFile: File): Boolean {
        startedFiles += outputFile
        if (!startSucceeds) return false
        recording = true
        paused = false
        return true
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun stop(): RecordedClip? {
        if (!recording) return null
        recording = false
        paused = false
        return stopResult
    }

    override fun cancel() {
        cancelCount++
        recording = false
        paused = false
    }

    override fun elapsedMs(): Long {
        onElapsedRead?.invoke()
        return elapsed
    }

    override fun isRecording(): Boolean = recording

    /**
     * 封口：与 [stop] 相同的产物，但**不删文件**。
     *
     * 单独计数，测试据此区分"页面销毁走了 salvage"与"用户明确取消走了 cancel"。
     */
    override fun salvage(): RecordedClip? {
        salvageCount++
        return stop()
    }

    /** 模拟系统打断（来电 / 音频焦点丢失），回调主线程由实现方保证，这里直接调用。 */
    fun interrupt() {
        onInterrupted?.invoke()
    }

    /** 模拟录音器自身出错（麦克风被收回 / 编码器故障）。 */
    fun fail(reason: VoiceRecorder.ErrorReason = VoiceRecorder.ErrorReason.INPUT_LOST) {
        onError?.invoke(reason)
    }
}
