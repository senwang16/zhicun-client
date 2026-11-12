package com.zhicun.app.domain.audio

/**
 * 音频时长探测（domain 契约，data 层用 `MediaMetadataRetriever` 实现，自海外轨同步）。
 *
 * 抽接口的理由与 [VoiceRecorder] 一致：`MediaMetadataRetriever` 需要真实文件与
 * Android 运行时，而"探测失败要按不转写处理"是业务规则，值得用纯 JVM 单测覆盖。
 *
 * ## 为什么探测失败返回 null 而不是 0 或抛异常
 * null 表达"**未知**"，与"很短（0ms）"是两件事。转写判据对未知 fail-closed：
 * 放行一个未知时长的文件，最坏结果是用户等 2 分钟拿到超时并把几十 MB 重传 3 次；
 * 不转写的最坏结果只是"这段音频没有文字"，后者可接受得多。
 */
fun interface AudioDurationProbe {
    /**
     * 探测 [localPath] 的音频时长。
     *
     * @return 毫秒；文件不存在、不是音频、容器无法解析时返回 null（**不抛异常**）
     */
    suspend fun probe(localPath: String): Long?
}