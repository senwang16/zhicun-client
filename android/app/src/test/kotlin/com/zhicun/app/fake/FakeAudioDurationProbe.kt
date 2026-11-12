package com.zhicun.app.fake

import com.zhicun.app.domain.audio.AudioDurationProbe

/**
 * 音频时长探测假实现（2026-11-03，自海外轨同步）。
 *
 * 默认返回 60 秒：既有转写用例的音频都按"可转"处理，不必每条都显式设时长。
 * 需要测"超长 / 探测失败"时改 [durationMs] 或按路径设 [byPath]。
 */
class FakeAudioDurationProbe(
    var durationMs: Long? = 60_000L,
) : AudioDurationProbe {

    /** 按文件路径覆盖返回值；未命中时用 [durationMs]。 */
    val byPath: MutableMap<String, Long?> = mutableMapOf()

    /** 探测过的路径，用于断言"单段录音优先用已记下的时长、没有重复探测"。 */
    val probedPaths: MutableList<String> = mutableListOf()

    override suspend fun probe(localPath: String): Long? {
        probedPaths += localPath
        return if (byPath.containsKey(localPath)) byPath[localPath] else durationMs
    }
}