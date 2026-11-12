package com.zhicun.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * epochMillis → LocalDate 的边界（M19）。
 *
 * 显式传时区钉住行为：默认时区重载只断言"与 systemDefault 等价"，
 * 具体日期的边界全部用 UTC / Asia/Shanghai 两个固定时区验证。
 */
class DateGroupsTest {

    @Test
    fun ToLocalDate_sameInstant_inDifferentZonesFallsOnDifferentDays() {
        // 2026-10-24 23:30 UTC：UTC 还是 24 号，东八区已经是 25 号
        val millis = LocalDateTime.of(2026, 10, 24, 23, 30)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        assertEquals(LocalDate.of(2026, 10, 24), DateGroups.toLocalDate(millis, ZoneId.of("UTC")))
        assertEquals(
            LocalDate.of(2026, 10, 25),
            DateGroups.toLocalDate(millis, ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun ToLocalDate_lastMillisOfLocalDay_staysOnThatDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val lastMillis = LocalDateTime.of(2026, 10, 23, 23, 59, 59, 999_999_999)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(LocalDate.of(2026, 10, 23), DateGroups.toLocalDate(lastMillis, zone))
    }

    @Test
    fun ToLocalDate_firstMillisOfLocalDay_startsNewDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val firstMillis = LocalDateTime.of(2026, 10, 24, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(LocalDate.of(2026, 10, 24), DateGroups.toLocalDate(firstMillis, zone))
    }

    @Test
    fun ToLocalDate_defaultZone_matchesSystemDefault() {
        val millis = System.currentTimeMillis()

        assertEquals(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
            DateGroups.toLocalDate(millis),
        )
    }
}
