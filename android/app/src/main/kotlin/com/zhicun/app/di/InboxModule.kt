package com.zhicun.app.di

import com.zhicun.app.data.settings.PreferencesInboxListSettings
import com.zhicun.app.domain.inbox.InboxListSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 收件箱列表偏好（M19）。
 *
 * 独立成模块而不是塞进 [SettingsModule]：那里放的是“内容要不要出设备”的隐私策略，
 * 折叠状态是纯展示偏好，混在一起会让后来者以为两者同级（[SettingsModule] 注释同理）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InboxModule {

    /** 折叠状态：InboxViewModel 一处读写，与悬浮/引导等域互不共享。 */
    @Binds
    @Singleton
    abstract fun bindInboxListSettings(impl: PreferencesInboxListSettings): InboxListSettings
}
