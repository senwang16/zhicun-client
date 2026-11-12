package com.zhicun.app.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转写上限判据（2026-11-03 用户拍板，自海外轨同步：5 分钟内可转写，超出不转写但仍保存）。
 *
 * ## 这组用例在钉什么
 * 1. **双限制**：时间与体积任一超限都不转写。只判其一会漏 —— 高码率文件撞体积墙、
 *    低码率长音频撞超时墙。
 * 2. **fail-closed**：时长未知（探测失败）时**不转写**。放行未知时长的最坏结果是
 *    用户等 2 分钟拿到超时、几十 MB 重传 3 次。
 * 3. **边界值**：恰好等于上限要放行（用户录满 5 分钟是正常场景，不能因此不转写）。
 * 4. **常量自洽**：录音硬顶必须等于转写上限，否则会出现"录得下但转不了"的窗口。
 */
class TranscriptionPolicyTest {

    @Test
    fun `audio within both limits is allowed`() {
        val verdict = TranscriptionPolicy.verdictFor(durationMs = 60_000L, sizeBytes = 1_000_000L)
        assertEquals(TranscriptionPolicy.Verdict.Allowed, verdict)
    }

    /**
     * 边界：**恰好等于**上限要放行。
     *
     * 用户按下录音、到点自动停，落库的时长就是 MAX_DURATION_MS 本身。
     * 如果这里用 `>=` 判，每一条录满的录音都不会转写 —— 恰好是最常见的那一类。
     */
    @Test
    fun `audio exactly at the limits is still allowed`() {
        val verdict = TranscriptionPolicy.verdictFor(
            durationMs = TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS,
            sizeBytes = TranscriptionPolicy.MAX_TRANSCRIBE_BYTES,
        )
        assertEquals(TranscriptionPolicy.Verdict.Allowed, verdict)
    }

    @Test
    fun `audio longer than the limit is rejected as too long`() {
        val verdict = TranscriptionPolicy.verdictFor(
            durationMs = TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS +
                TranscriptionPolicy.DURATION_TOLERANCE_MS + 1,
            sizeBytes = 1_024L,
        )
        assertEquals(
            TranscriptionPolicy.Verdict.Rejected(TranscriptionPolicy.Reason.TOO_LONG),
            verdict,
        )
    }

    /**
     * 录满 5 分钟后停表/封口会多出几十到几百毫秒：落在余量内必须放行。
     *
     * 否则每一条录满的录音都会被判"超长"，产品上的 5 分钟上限就成了"5 分钟反而不能转"。
     */
    @Test
    fun `recorder overshoot within tolerance is allowed`() {
        val verdict = TranscriptionPolicy.verdictFor(
            durationMs = TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS +
                TranscriptionPolicy.DURATION_TOLERANCE_MS,
            sizeBytes = 1_024L,
        )
        assertEquals(TranscriptionPolicy.Verdict.Allowed, verdict)
    }

    /** 体积超限先于时长判定：这种文件连上传都不该发起。 */
    @Test
    fun `audio larger than the limit is rejected as too large`() {
        val verdict = TranscriptionPolicy.verdictFor(
            durationMs = 10_000L,
            sizeBytes = TranscriptionPolicy.MAX_TRANSCRIBE_BYTES + 1,
        )
        assertEquals(
            TranscriptionPolicy.Verdict.Rejected(TranscriptionPolicy.Reason.TOO_LARGE),
            verdict,
        )
    }

    /**
     * 时长未知 → 不转写（fail-closed）。
     *
     * 与"很短"必须区分：null 是"读不出来"，0 是"确实没有内容"。
     */
    @Test
    fun `unknown duration fails closed`() {
        val verdict = TranscriptionPolicy.verdictFor(durationMs = null, sizeBytes = 1_024L)
        assertEquals(
            TranscriptionPolicy.Verdict.Rejected(TranscriptionPolicy.Reason.DURATION_UNKNOWN),
            verdict,
        )
    }

    /**
     * 录音硬顶必须**等于**转写上限。
     *
     * 两者不等的两种坏情况：
     * - 录音上限 > 转写上限：用户录了 8 分钟，存完发现"不支持转写"；
     * - 录音上限 < 转写上限：浪费了可用的转写额度，但至少不会骗用户。
     */
    @Test
    fun `recording cap equals transcription cap`() {
        assertEquals(
            "录音硬顶与转写上限必须相等，否则会出现「录得下但转不了」",
            TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS,
            VoiceRecordingPolicy.MAX_DURATION_MS,
        )
    }

    @Test
    fun `limits match the documented product values`() {
        assertEquals("用户拍板：5 分钟", 5 * 60_000L, TranscriptionPolicy.MAX_TRANSCRIBE_DURATION_MS)
        assertEquals(
            "与网关 maxBodyBytes 对齐：20MB",
            20L * 1024 * 1024,
            TranscriptionPolicy.MAX_TRANSCRIBE_BYTES,
        )
    }

    /** 判定必须对所有输入都有结论，不能抛异常（Worker 里直接调用）。 */
    @Test
    fun `verdict never throws for extreme inputs`() {
        val cases = listOf(
            0L to 0L,
            Long.MAX_VALUE to Long.MAX_VALUE,
            null to Long.MAX_VALUE,
        )
        cases.forEach { (duration, size) ->
            // 不抛异常即通过；返回值一定是 Verdict（sealed 只有两态，无需再断言）
            TranscriptionPolicy.verdictFor(duration, size)
        }
    }
}