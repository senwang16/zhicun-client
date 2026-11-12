package com.zhicun.app.domain.floating

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台常驻条件的判据（2026-10-24）。
 *
 * 这里钉的是"哪一项不满足才算有问题"。它有真实后果：界面靠这一份判断
 * 决定要不要标红、要不要给"去设置"入口；判错的方向有两种，都很糟 ——
 * 漏报 = 用户不知道为什么气泡会没；误报 = 用户去改一个没坏的东西。
 */
class KeepAliveStatusTest {

    @Test
    fun hasKnownRestriction_isFalseWhenEverythingIsAllowed() {
        val status = KeepAliveStatus(
            batteryOptimizationIgnored = true,
            notificationsEnabled = true,
            standbyBucket = StandbyBucket.ACTIVE,
        )

        assertFalse(status.hasKnownRestriction)
    }

    @Test
    fun hasKnownRestriction_isTrueWhenBatteryWhitelistMissing() {
        val status = KeepAliveStatus(batteryOptimizationIgnored = false, notificationsEnabled = true)

        assertTrue(status.needsBatteryWhitelist)
        assertTrue(status.hasKnownRestriction)
    }

    @Test
    fun hasKnownRestriction_isTrueWhenNotificationsOff() {
        val status = KeepAliveStatus(batteryOptimizationIgnored = true, notificationsEnabled = false)

        assertTrue(status.notificationsOff)
        assertTrue(status.hasKnownRestriction)
    }

    @Test
    fun hasKnownRestriction_isTrueWhenStandbyRare() {
        val status = KeepAliveStatus(
            batteryOptimizationIgnored = true,
            notificationsEnabled = true,
            standbyBucket = StandbyBucket.RARE,
        )

        assertTrue(status.standbyRestricted)
        assertTrue(status.hasKnownRestriction)
    }

    @Test
    fun standby_frequentAndWorkingSet_areNotRestricted() {
        assertFalse(StandbyBucket.ACTIVE.isRestricted)
        assertFalse(StandbyBucket.WORKING_SET.isRestricted)
        assertFalse(StandbyBucket.FREQUENT.isRestricted)
        assertTrue(StandbyBucket.RARE.isRestricted)
        assertTrue(StandbyBucket.RESTRICTED.isRestricted)
    }

    /**
     * UNKNOWN 既不算受限，也不算已知。
     *
     * 这两件事必须分开：`isRestricted = false` 让"整体是否受限"保持为 false（不误报），
     * `isKnown = false` 让界面**不显示**这一行（不撒谎说"正常"）。
     */
    @Test
    fun standby_unknown_isNeitherRestrictedNorKnown() {
        assertFalse(StandbyBucket.UNKNOWN.isRestricted)
        assertFalse(StandbyBucket.UNKNOWN.isKnown)

        val status = KeepAliveStatus(
            batteryOptimizationIgnored = true,
            notificationsEnabled = true,
            standbyBucket = StandbyBucket.UNKNOWN,
        )
        assertFalse(status.hasKnownRestriction)
    }
}
