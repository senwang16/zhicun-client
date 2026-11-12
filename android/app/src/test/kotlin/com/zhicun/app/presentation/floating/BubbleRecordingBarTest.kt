package com.zhicun.app.presentation.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶部录音条计时格式（2026-10-26，spec §6.2-5）：纯函数，JVM 可穷举。
 */
class BubbleRecordingBarTest {

    @Test
    fun formatElapsed_underOneHour_isMinuteSecond() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:00", formatElapsed(999))
        assertEquals("00:01", formatElapsed(1_000))
        assertEquals("00:07", formatElapsed(7_000))
        assertEquals("01:00", formatElapsed(60_000))
        assertEquals("59:59", formatElapsed(3_599_000))
    }

    @Test
    fun formatElapsed_oneHourOrMore_isHourMinuteSecond() {
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        assertEquals("10:00:00", formatElapsed(36_000_000))
    }

    @Test
    fun formatElapsed_negative_clampsToZero() {
        assertEquals("00:00", formatElapsed(-5_000))
    }
}