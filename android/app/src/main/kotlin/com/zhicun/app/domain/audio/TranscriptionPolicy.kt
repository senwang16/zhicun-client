package com.zhicun.app.domain.audio

/**
 * 一条音频**能不能转写**的唯一判据（2026-11-03 用户拍板，自海外轨同步）。
 *
 * ## 为什么需要它
 * 转写走云端 ASR，一次请求要同时满足两个硬约束：
 * - **时间**：客户端 `callTimeout` 120s、服务端上游超时 110s。要在这段时间里转完，
 *   音频时长必须落在一个可预期的区间内；5 分钟是产品拍板的上限。
 * - **体积**：网关 `maxBodyBytes` 20MB。同样是 5 分钟，AAC 64kbps 约 2.4MB，
 *   而 WAV 44.1kHz 立体声约 52MB —— 只看时长会让后者在上传阶段就被 413。
 *
 * 两个条件**任一**不满足就不转写。只判其一都会漏：只判时长会让高码率文件撞体积墙，
 * 只判体积会让低码率长音频撞超时墙。
 *
 * ## 为什么不转写 ≠ 不保存
 * 用户的原话是"本地的允许他存，但是不做转写就行了"。音频照常落盘、照常可播放，
 * 只是不出文字。因此调用方拿到 [Verdict.Rejected] 时**不得**删除文件、
 * 不得把记录标成失败 —— 它是"这条音频不做转写"这个产品事实，不是错误。
 */
object TranscriptionPolicy {

    /**
     * 可转写的最长音频时长：5 分钟（2026-11-03 用户拍板，原 30 分钟）。
     *
     * 5 分钟 @64kbps ≈ 2.4MB，110s 内转完只需 RTF < 0.37，任何主流 ASR 都能过。
     * 这个值同时也是**录音页的硬顶**（[VoiceRecordingPolicy.MAX_DURATION_MS]），
     * 两者必须相等：否则会出现"录得下但转不了"的窗口。
     */
    const val MAX_TRANSCRIBE_DURATION_MS: Long = 5 * 60_000L

    /**
     * 可转写的最大音频体积：20MB（与网关 `maxBodyBytes` / 能力声明 `max_upload_bytes` 对齐）。
     *
     * 这是**网络层**的真实上限，不是产品选择。超过它必然 413，
     * 与其传完再被拒，不如本地就判定不转写。
     */
    const val MAX_TRANSCRIBE_BYTES: Long = 20L * 1024 * 1024

    /**
     * 录音"停表/封口"与最终文件时长之间的毫秒级误差余量（2026-11-05）。
     *
     * 录音页按 200ms tick 检查硬顶，`elapsed >= MAX` 时再调 stop；从判定到真正封口
     * 还会多出最多一个 tick + 编码收尾的时间，落库的 `captureDurationMs` 可能比
     * `MAX_TRANSCRIBE_DURATION_MS` 大几十到几百毫秒。若判据仍用严格的 `>`，**每一条
     * 录满 5 分钟的录音都会被判"超长、不转写"**。留 2 秒余量不影响上游 110s 预算
     * （5:02 @64kbps 仍约 2.4MB），但覆盖得了这个误差。
     */
    const val DURATION_TOLERANCE_MS: Long = 2_000L

    /** 判定结果：为什么能转 / 为什么不能。 */
    sealed interface Verdict {
        /** 可以转写。 */
        data object Allowed : Verdict

        /**
         * 不转写，但音频本身照常保存与播放。
         *
         * @property reason 面向用户的解释（写进界面，不进任务表 —— 这条路径根本不建任务）
         */
        data class Rejected(val reason: Reason) : Verdict
    }

    /** 不转写的具体原因。枚举而不是自由文本：界面按它取本地化文案。 */
    enum class Reason {
        /** 音频时长超过 [MAX_TRANSCRIBE_DURATION_MS]。 */
        TOO_LONG,

        /** 音频体积超过 [MAX_TRANSCRIBE_BYTES]。 */
        TOO_LARGE,

        /** 拿不到时长（文件损坏 / 容器不识别）：无法确认它在安全区间内，按不转写处理。 */
        DURATION_UNKNOWN,
    }

    /**
     * 判定一条音频能不能转写。
     *
     * @param durationMs 音频时长；null 表示**探测失败**（不是"很短"）。
     *   探测失败一律判 [Reason.DURATION_UNKNOWN] —— fail-closed：
     *   放行一个未知时长的文件，最坏结果是用户等 2 分钟拿到超时，
     *   而不放行的最坏结果只是"这段音频没有文字"，后者可接受得多。
     * @param sizeBytes 音频文件体积（本地落盘后的真实字节数）。
     */
    fun verdictFor(durationMs: Long?, sizeBytes: Long): Verdict = when {
        // 先判体积：它是确定值，且超出时连上传都不该发起
        sizeBytes > MAX_TRANSCRIBE_BYTES -> Verdict.Rejected(Reason.TOO_LARGE)
        durationMs == null -> Verdict.Rejected(Reason.DURATION_UNKNOWN)
        // 留出录音停表误差：录满 5 分钟是正常场景，不能因多出几百毫秒被拒
        durationMs > MAX_TRANSCRIBE_DURATION_MS + DURATION_TOLERANCE_MS ->
            Verdict.Rejected(Reason.TOO_LONG)

        else -> Verdict.Allowed
    }
}