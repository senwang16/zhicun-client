package com.zhicun.app.fake

import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.FloatingBubbleSettings

/**
 * 内存版气泡网关（M13）。
 *
 * 刻意把"权限"和"开关"分开注入：设置页最容易出的错就是这两者的组合判断，
 * 测试必须能独立摆布它们（例如"开关开、权限没有"这种用户最常见的中间态）。
 *
 * @param granted 初始权限状态；测试中可直接改 `granted = true` 模拟"用户去系统设置授权后回来"
 */
class FakeFloatingBubbleGateway(
    var granted: Boolean = false,
    private val settings: FloatingBubbleSettings? = null,
) : FloatingBubbleGateway {

    var syncCount: Int = 0
    var openPermissionCount: Int = 0
    var applyAlwaysVisibleCount: Int = 0

    override fun hasPermission(): Boolean = granted

    /**
     * 真实实现的判据是 `开关 && 权限`（见 `FloatingBubbleController.shouldRun`）。
     * 这里保持一致，而不是无脑返回 true，否则"无权限时不该启动"的行为就测不出来。
     */
    override fun sync(): Boolean {
        syncCount++
        val enabled = settings?.enabled ?: true
        return enabled && granted
    }

    override fun openPermissionSettings() {
        openPermissionCount++
    }

    override fun applyAlwaysVisible() {
        applyAlwaysVisibleCount++
    }
}
