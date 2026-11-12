package com.zhicun.app.di

import com.zhicun.app.data.settings.PreferencesOnboardingSettings
import com.zhicun.app.domain.onboarding.OnboardingSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 首启引导的绑定（附录 T）。
 *
 * 独立成模块而不是塞进 [FloatingModule] / [SettingsModule]：引导状态横跨
 * 导航起点判定、引导页、收件箱横幅三处，不属于其中任何一域。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {

    /** 引导完成版本与横幅关闭标记：导航图、引导页、收件箱横幅三处共享同一份 prefs。 */
    @Binds
    @Singleton
    abstract fun bindOnboardingSettings(impl: PreferencesOnboardingSettings): OnboardingSettings
}
