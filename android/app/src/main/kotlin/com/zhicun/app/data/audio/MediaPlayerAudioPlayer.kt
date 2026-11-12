package com.zhicun.app.data.audio

import android.media.MediaPlayer
import android.util.Log
import com.zhicun.app.domain.audio.AudioPlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioPlayer] 的 MediaPlayer 实现（M9）。
 *
 * 为什么用 MediaPlayer 而不是 ExoPlayer：详情页只需要"播放/暂停/看进度"，
 * MediaPlayer 是系统自带、零依赖、minSdk 26 全覆盖；ExoPlayer 要多打包约 1MB 且
 * 只带来格式兼容与流式播放能力，本项目播放的是本地 m4a，用不上。
 *
 * 失败一律返回 false 而不是抛异常：音频文件可能在用户清理存储后消失，
 * 详情页需要的是"音频不可用"这句提示，不是一次崩溃。
 */
@Singleton
class MediaPlayerAudioPlayer @Inject constructor() : AudioPlayer {

    private var player: MediaPlayer? = null

    override var currentPath: String? = null
        private set

    override var onCompleted: (() -> Unit)? = null

    override fun play(path: String): Boolean {
        releaseCurrent()
        if (path.isBlank() || !File(path).exists()) {
            Log.w(TAG, "音频文件不存在，无法播放 path=$path")
            return false
        }
        return try {
            val created = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { onCompleted?.invoke() }
                prepare()
                start()
            }
            player = created
            currentPath = path
            true
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败 path=$path", e)
            releaseCurrent()
            false
        }
    }

    override fun pause() {
        runCatching {
            player?.takeIf { it.isPlaying }?.pause()
        }.onFailure { Log.w(TAG, "暂停播放失败", it) }
    }

    override fun resume(): Boolean {
        val current = player ?: return false
        return try {
            current.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "续播音频失败", e)
            false
        }
    }

    override fun stop() {
        releaseCurrent()
    }

    override fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }
        .getOrDefault(false)

    override fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }
        .getOrDefault(0L)

    override fun durationMs(): Long = runCatching { player?.duration?.toLong() ?: 0L }
        .getOrDefault(0L)

    override fun release() = releaseCurrent()

    private fun releaseCurrent() {
        player?.let { existing ->
            runCatching { existing.stop() }
            runCatching { existing.release() }
        }
        player = null
        currentPath = null
    }

    private companion object {
        const val TAG = "AudioPlayer"
    }
}
