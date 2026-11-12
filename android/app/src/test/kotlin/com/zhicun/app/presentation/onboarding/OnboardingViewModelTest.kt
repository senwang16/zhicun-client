package com.zhicun.app.presentation.onboarding

import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.KeepAliveGateway
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.onboarding.OnboardingSettings
import com.zhicun.app.fake.FakeOnboardingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启引导 ViewModel 的判定与动作（附录 T）。
 *
 * 全部同步逻辑（SharedPreferences 无 IO、gateway 查询微秒级），纯 JVM 可测，
 * 不需要协程/turbine。判据真源说明：悬浮窗权限与电池白名单都走 gateway 直读，
 * 这里**不缓存、不复算** —— 与设置页自检区块天然同源。
 */
class OnboardingViewModelTest {

    // ------------------------------------------------------------ 起点判定

    @Test
    fun Start_firstRun_whenNothingCompleted() {
        val vm = createViewModel(settings = FakeOnboardingSettings(completedVersion = 0))

        assertEquals(OnboardingStart.FirstRun, vm.start.value)
    }

    @Test
    fun Start_completed_afterCurrentVersionRecorded() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(completedVersion = OnboardingSettings.CURRENT_VERSION),
        )

        assertEquals(OnboardingStart.Completed, vm.start.value)
    }

    /** 版本号只升不减：prefs 里的值比目标还新（回退安装）同样视为已完成，不拽用户回引导。 */
    @Test
    fun Start_completed_whenVersionHigherThanCurrent() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(completedVersion = OnboardingSettings.CURRENT_VERSION + 1),
        )

        assertEquals(OnboardingStart.Completed, vm.start.value)
    }

    @Test
    fun Complete_writesCurrentVersion() {
        val settings = FakeOnboardingSettings()
        val vm = createViewModel(settings = settings)

        vm.complete()

        assertEquals(listOf(OnboardingSettings.CURRENT_VERSION), settings.completions)
    }

    // ------------------------------------------------------------ 系统状态复查

    @Test
    fun Refresh_reflectsOverlayPermissionAndBattery() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(),
            hasOverlayPermission = true,
            batteryWhitelisted = true,
        )

        vm.refresh()

        val state = vm.uiState.value
        assertTrue(state.overlayGranted)
        assertTrue(state.batteryWhitelisted)
    }

    @Test
    fun Refresh_withoutPermission_marksBothIncomplete() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(),
            hasOverlayPermission = false,
            batteryWhitelisted = false,
        )

        vm.refresh()

        val state = vm.uiState.value
        assertFalse(state.overlayGranted)
        assertFalse(state.batteryWhitelisted)
    }

    // ------------------------------------------------------------ 收件箱横幅

    @Test
    fun Banner_visible_whenNoPermissionAndNotDismissed() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(bannerDismissed = false),
            hasOverlayPermission = false,
        )

        vm.refresh()

        assertTrue(vm.bubbleGuideBanner.value)
    }

    @Test
    fun Banner_hidden_whenPermissionGranted() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(bannerDismissed = false),
            hasOverlayPermission = true,
        )

        vm.refresh()

        assertFalse(vm.bubbleGuideBanner.value)
    }

    /** 用户手动关过就不再见 —— 横幅"只此一条"，关一次永久安静（直到重装）。 */
    @Test
    fun Banner_hidden_whenDismissed_evenWithoutPermission() {
        val vm = createViewModel(
            settings = FakeOnboardingSettings(bannerDismissed = true),
            hasOverlayPermission = false,
        )

        vm.refresh()

        assertFalse(vm.bubbleGuideBanner.value)
    }

    @Test
    fun DismissBanner_persistsToSettings() {
        val settings = FakeOnboardingSettings()
        val vm = createViewModel(settings = settings, hasOverlayPermission = false)

        vm.dismissBubbleGuideBanner()

        assertEquals(1, settings.bannerDismissals)
        assertFalse(vm.bubbleGuideBanner.value)
    }

    // ------------------------------------------------------------ 工具

    private fun createViewModel(
        settings: FakeOnboardingSettings,
        hasOverlayPermission: Boolean = false,
        batteryWhitelisted: Boolean = false,
    ): OnboardingViewModel = OnboardingViewModel(
        onboardingSettings = settings,
        bubbleGateway = FakeBubbleGateway(hasPermission = hasOverlayPermission),
        keepAliveGateway = FakeKeepAliveGateway(batteryWhitelisted = batteryWhitelisted),
    )

    private class FakeBubbleGateway(private val hasPermission: Boolean) : FloatingBubbleGateway {
        override fun hasPermission(): Boolean = hasPermission
        override fun sync(): Boolean = hasPermission
        override fun applyAlwaysVisible() = Unit
        override fun openPermissionSettings() = Unit
    }

    private class FakeKeepAliveGateway(private val batteryWhitelisted: Boolean) : KeepAliveGateway {
        override fun status(): KeepAliveStatus = KeepAliveStatus(
            batteryOptimizationIgnored = batteryWhitelisted,
            notificationsEnabled = true,
        )

        override fun openBatteryOptimizationSettings() = Unit

        override fun openNotificationSettings() = Unit
    }
}
