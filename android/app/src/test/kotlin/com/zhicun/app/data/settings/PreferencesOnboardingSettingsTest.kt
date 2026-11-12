package com.zhicun.app.data.settings

import com.zhicun.app.domain.onboarding.OnboardingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 引导设置的 SharedPreferences 实现（附录 T）。
 *
 * 重点三条：默认 0（未看过引导）、跨实例持久（导航图判定与横幅关闭是两个
 * 独立注入点，必须共享同一份值）、完成版本号的读写闭环。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesOnboardingSettingsTest {

    private fun newSettings() =
        PreferencesOnboardingSettings(RuntimeEnvironment.getApplication())

    @Test
    fun Defaults_versionZeroAndBannerNotDismissed() {
        val settings = newSettings()

        assertEquals("默认必须视为未看过引导", 0, settings.completedVersion)
        assertFalse("横幅默认可见（未被手动关闭）", settings.bubbleGuideBannerDismissed)
    }

    @Test
    fun Complete_persistsAcrossInstances() {
        newSettings().complete(OnboardingSettings.CURRENT_VERSION)

        assertEquals(
            OnboardingSettings.CURRENT_VERSION,
            newSettings().completedVersion,
        )
    }

    @Test
    fun DismissBanner_persistsAcrossInstances() {
        newSettings().dismissBubbleGuideBanner()

        assertTrue(newSettings().bubbleGuideBannerDismissed)
    }
}
