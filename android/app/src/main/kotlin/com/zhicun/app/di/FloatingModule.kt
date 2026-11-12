package com.zhicun.app.di

import com.zhicun.app.data.clipboard.AndroidClipboardReader
import com.zhicun.app.data.platform.AndroidFloatingBubbleGateway
import com.zhicun.app.data.platform.AndroidKeepAliveGateway
import com.zhicun.app.data.settings.PreferencesFloatingBubbleSettings
import com.zhicun.app.domain.clipboard.ClipboardReader
import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.domain.floating.KeepAliveGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 悬浮入口相关的绑定（M13）。
 *
 * 三个接口都属于"跨页面、跨进程生命周期"的东西（服务要用开关、设置页要用网关、
 * 中转页要用剪贴板），因此统一装在 `SingletonComponent` 里，不做页面作用域。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FloatingModule {

    /** 气泡开关与位置：服务、设置页、MainActivity 三处共享同一份 prefs。 */
    @Binds
    @Singleton
    abstract fun bindFloatingBubbleSettings(
        impl: PreferencesFloatingBubbleSettings,
    ): FloatingBubbleSettings

    /** 启停门面：把 `canDrawOverlays` / `startForegroundService` 挡在 domain 之外。 */
    @Binds
    @Singleton
    abstract fun bindFloatingBubbleGateway(
        impl: AndroidFloatingBubbleGateway,
    ): FloatingBubbleGateway

    /** 剪贴板读取：M13 的"存剪贴板"快捷动作。 */
    @Binds
    @Singleton
    abstract fun bindClipboardReader(impl: AndroidClipboardReader): ClipboardReader

    /** 后台常驻自检：电池优化 / 通知 / App Standby 三项系统状态的只读查询。 */
    @Binds
    @Singleton
    abstract fun bindKeepAliveGateway(impl: AndroidKeepAliveGateway): KeepAliveGateway
}
