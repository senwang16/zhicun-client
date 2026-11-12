package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.onboarding.OnboardingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OnboardingSettings] 的 SharedPreferences 实现（附录 T）。
 *
 * 与 [PreferencesFloatingBubbleSettings] 同一取向：低频读写、不缓存（引导只在
 * 首启与横幅关闭时碰这两个键，缓存反而引入"界面已关、prefs 还没写"的时序问题）。
 * 读取不做容错重试：Int/Boolean 的默认值（0 / false）恰好就是"未完成引导"的语义，
 * 坏值最坏也只是再看一次引导，无害。
 */
@Singleton
class PreferencesOnboardingSettings @Inject constructor(
    @ApplicationContext context: Context,
) : OnboardingSettings {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val completedVersion: Int
        get() = prefs.getInt(KEY_COMPLETED_VERSION, 0)

    override val bubbleGuideBannerDismissed: Boolean
        get() = prefs.getBoolean(KEY_BANNER_DISMISSED, false)

    override fun complete(version: Int) {
        prefs.edit { putInt(KEY_COMPLETED_VERSION, version) }
    }

    override fun dismissBubbleGuideBanner() {
        prefs.edit { putBoolean(KEY_BANNER_DISMISSED, true) }
    }

    private companion object {
        const val PREFS_NAME = "onboarding"
        const val KEY_COMPLETED_VERSION = "completed_version"
        const val KEY_BANNER_DISMISSED = "bubble_guide_banner_dismissed"
    }
}
