package com.zhicun.app.fake

import com.zhicun.app.domain.floating.KeepAliveGateway
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.floating.StandbyBucket

/**
 * 内存版保活自检网关（2026-10-24）。
 *
 * 三个字段都可从外部改：设置页要展示的正是"这三项各自不满足"的不同组合，
 * 固定一份状态等于只测了其中一种。
 *
 * @param failOnRead 为 true 时 [status] 抛异常，用来验证"读不到就不显示，而不是崩页"
 */
class FakeKeepAliveGateway(
    var status: KeepAliveStatus = KeepAliveStatus(
        batteryOptimizationIgnored = true,
        notificationsEnabled = true,
        standbyBucket = StandbyBucket.ACTIVE,
    ),
    var failOnRead: Boolean = false,
) : KeepAliveGateway {

    var batterySettingsOpened: Int = 0
    var notificationSettingsOpened: Int = 0

    override fun status(): KeepAliveStatus {
        if (failOnRead) throw IllegalStateException("模拟读取失败")
        return status
    }

    override fun openBatteryOptimizationSettings() {
        batterySettingsOpened++
    }

    override fun openNotificationSettings() {
        notificationSettingsOpened++
    }
}
