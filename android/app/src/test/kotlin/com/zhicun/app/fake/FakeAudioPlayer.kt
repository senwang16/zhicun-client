package com.zhicun.app.fake

import com.zhicun.app.domain.audio.AudioPlayer

/**
 * 播放器假实现（M9）。
 *
 * 只记录"谁被要求播什么"并允许测试推进进度，不碰真实音频设备。
 * `play` 的返回值可配：用于覆盖"音频文件丢了"这条分支。
 */
class FakeAudioPlayer : AudioPlayer {

    val playedPaths: MutableList<String> = mutableListOf()

    /** [resume] 被调用的次数，用于断言"暂停后再点走的是续播而不是重播"。 */
    var resumeCalls: Int = 0
        private set

    var playSucceeds: Boolean = true
    var playing: Boolean = false
    var position: Long = 0L
    var duration: Long = 0L
    var released: Boolean = false

    override var onCompleted: (() -> Unit)? = null

    override var currentPath: String? = null
        private set

    override fun play(path: String): Boolean {
        playedPaths += path
        if (!playSucceeds) return false
        currentPath = path
        playing = true
        position = 0L
        return true
    }

    override fun pause() {
        playing = false
    }

    override fun resume(): Boolean {
        resumeCalls += 1
        playing = true
        return true
    }

    override fun stop() {
        playing = false
        position = 0L
        currentPath = null
    }

    override fun isPlaying(): Boolean = playing

    override fun positionMs(): Long = position

    override fun durationMs(): Long = duration

    override fun release() {
        released = true
        playing = false
        currentPath = null
    }

    /** 模拟播完：回调 + 自身状态复位（真实实现里 MediaPlayer 不会重置 position）。 */
    fun complete() {
        playing = false
        position = 0L
        onCompleted?.invoke()
    }
}
