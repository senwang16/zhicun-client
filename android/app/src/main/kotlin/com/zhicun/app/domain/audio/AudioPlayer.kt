package com.zhicun.app.domain.audio

/**
 * 音频播放能力（domain 契约，data 层用 MediaPlayer 实现）。
 *
 * 详情页只关心"播到哪、是不是在播、放完了没有"。抽成接口后 ViewModel 的状态机
 * （播放/暂停/播完复位/文件丢失）可以在纯 JVM 下测，不需要真实音频设备。
 *
 * 实现约定：
 * - 同一实例只播一个文件；再次 [play] 会切换文件。
 * - [pause] 后调用 [resume] 从当前位置继续，而不是回到起点。
 * - [play] 遇到文件不存在或格式不支持时返回 `false`，不抛异常 ——
 *   详情页要展示"音频不可用"，而不是崩掉。
 */
interface AudioPlayer {

    /** 播放完成回调（在播放器线程回调，实现方应切回主线程或让调用方自行处理线程安全）。 */
    var onCompleted: (() -> Unit)?

    /**
     * 单例播放器当前装载的文件路径；null = 没有装载（stop/release/播放失败后）。
     *
     * 自然播完后仍保留路径，直到下一次 play/stop —— 调用方靠它区分
     * "我的文件播完了"与"另一个页面接管了播放器"（2026-10-24 deepseek 审查 P3）。
     */
    val currentPath: String?

    /** @return false = 无法播放（文件丢失 / 编解码失败） */
    fun play(path: String): Boolean

    fun pause()

    /**
     * 从上次暂停的位置继续播放（QODER P2-5：暂停后点继续不得从头开始）。
     * @return false = 没有可续播的暂停中播放器（例如从未播放过或已被释放）。
     */
    fun resume(): Boolean

    /** 停止并回到起点（切换记录、页面销毁时调用）。 */
    fun stop()

    fun isPlaying(): Boolean

    fun positionMs(): Long

    fun durationMs(): Long

    /** 释放底层资源（ViewModel.onCleared）。 */
    fun release()
}
