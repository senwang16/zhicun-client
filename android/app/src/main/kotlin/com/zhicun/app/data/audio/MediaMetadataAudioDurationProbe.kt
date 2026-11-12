package com.zhicun.app.data.audio

import android.media.MediaMetadataRetriever
import android.util.Log
import com.zhicun.app.domain.audio.AudioDurationProbe
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AudioDurationProbe] 的 `MediaMetadataRetriever` 实现（2026-11-03，自海外轨同步）。
 *
 * ## 为什么必须切到 IO 线程
 * `setDataSource` + `extractMetadata` 会真的打开文件并解析容器头。本地文件通常几毫秒，
 * 但损坏文件、外置存储已卸载、部分 ROM 的软解容器会让它卡到数百毫秒甚至更久。
 * 这个调用发生在转写任务的执行期（Worker 内），仍不应占着默认线程池。
 *
 * ## 为什么失败一律返回 null 而不是抛
 * 调用方（`TranscribeWorker`）的语义是"能转就转，不能转就只留音频"。
 * 探测失败不该让整次任务失败 —— 音频照样落盘、照样可播放，只是不转写。
 *
 * ## 为什么每次都要 new 一个 retriever 并 release
 * `MediaMetadataRetriever` 是有 native 资源的对象，跨文件复用需要 `setDataSource`
 * 反复重置，在部分 ROM 上会残留上一文件的元数据（典型表现：所有音频都报同一时长）。
 * 每次都新建 + `release()`，代价可忽略，换来的是不会串味。
 */
@Singleton
class MediaMetadataAudioDurationProbe @Inject constructor() : AudioDurationProbe {

    override suspend fun probe(localPath: String): Long? = withContext(Dispatchers.IO) {
        val file = File(localPath)
        // 先自己判存在：setDataSource 对缺失文件抛的是 IllegalArgumentException，
        // 与"容器不识别"混在一起就分不清日志里的失败原因了
        if (!file.exists() || !file.isFile) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val parsed = raw?.trim()?.toLongOrNull()
            // 0 与负数都不是有效时长（空文件、损坏容器常见），按未知处理
            parsed?.takeIf { it > 0 }
        } catch (e: Exception) {
            // 不抛给调用方：探测是附加信息，失败只影响"要不要转写"
            Log.w(TAG, "音频时长探测失败 file=${file.name} cause=${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
                .onFailure { Log.w(TAG, "释放 MediaMetadataRetriever 失败", it) }
        }
    }

    private companion object {
        const val TAG = "AudioDurationProbe"
    }
}