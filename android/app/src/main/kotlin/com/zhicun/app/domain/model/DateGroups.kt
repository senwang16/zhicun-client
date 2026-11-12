package com.zhicun.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * epochMillis → 按天分组的键（M19：收件箱按天折叠与日历筛选）。
 *
 * “一天”按**设备时区**切：用户心智里的 10 月 24 日就是本机日历上的 10 月 24 日，
 * 与记录落库时的 UTC 语义无关（现有展示层 `RecordFormatter` 也是设备时区，同一口径）。
 * minSdk 26 起 `java.time` 原生可用，无需 desugaring。
 *
 * 纯函数、无 Android 依赖，domain 层可直接单测（传入显式 [ZoneId] 钉住时区）。
 */
object DateGroups {

    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
