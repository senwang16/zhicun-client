package com.zhicun.app.di

import com.zhicun.app.data.platform.AndroidFeedbackLauncher
import com.zhicun.app.data.settings.AppLocaleManager
import com.zhicun.app.data.settings.PreferencesAiSettings
import com.zhicun.app.data.settings.PreferencesLanguage
import com.zhicun.app.data.settings.PreferencesMultiAssetRerunSettings
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.FeedbackLauncher
import com.zhicun.app.domain.settings.LanguagePreference
import com.zhicun.app.domain.settings.MultiAssetRerunSettings
import com.zhicun.app.domain.settings.UiLanguageSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 应用级设置（M14）。
 *
 * 与悬浮入口（[FloatingModule]）分开放：这里的开关决定的是"内容要不要出设备"，
 * 属于隐私策略而不是某个功能的偏好，混在一起会让后来者以为两者同级。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    /** 云端 AI 处理开关：调度侧（`ScheduleProcessingUseCase`）与设置页共用同一份。 */
    @Binds
    @Singleton
    abstract fun bindAiSettings(impl: PreferencesAiSettings): AiSettings

    /** 意见反馈入口。 */
    @Binds
    @Singleton
    abstract fun bindFeedbackLauncher(impl: AndroidFeedbackLauncher): FeedbackLauncher

    /** v8 多附件补跑的一次性标记（D1）。 */
    @Binds
    @Singleton
    abstract fun bindMultiAssetRerunSettings(
        impl: PreferencesMultiAssetRerunSettings,
    ): MultiAssetRerunSettings

    /**
     * 界面语言偏好（P11）。
     *
     * Worker / 各 ViewModel 只读这一份快照；真正的"当前界面语言"出口是
     * [UiLanguageSettings]（`AppLocaleManager`），它还要同步系统"应用语言"。
     */
    @Binds
    @Singleton
    abstract fun bindLanguagePreference(impl: PreferencesLanguage): LanguagePreference

    @Binds
    @Singleton
    abstract fun bindUiLanguageSettings(impl: AppLocaleManager): UiLanguageSettings
}
