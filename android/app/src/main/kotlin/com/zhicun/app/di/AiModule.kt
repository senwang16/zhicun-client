package com.zhicun.app.di

import com.zhicun.app.data.remote.DirectAsrProvider
import com.zhicun.app.data.remote.DirectChatProvider
import com.zhicun.app.data.remote.DirectEmbedProvider
import com.zhicun.app.data.remote.RemoteContentEnricher
import com.zhicun.app.data.remote.RemoteEmbedder
import com.zhicun.app.data.remote.RemoteSpeechTranscriber
import com.zhicun.app.data.remote.RoutingContentEnricher
import com.zhicun.app.data.remote.RoutingEmbedder
import com.zhicun.app.data.remote.RoutingSpeechTranscriber
import com.zhicun.app.data.settings.KeystoreTokenCipher
import com.zhicun.app.data.settings.PreferencesAiProviderSettings
import com.zhicun.app.data.settings.TokenCipher
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.SpeechTranscriber
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AI 配置与密钥（M9 建立，开源改造 P0 重构）。
 *
 * 配置只有一个存储实现，暴露**两个接口视图**：
 * - [AiProviderSettings]：三组独立 Provider + 官方服务（设置页与路由用）；
 * - [AiGatewaySettings]：官方网关地址 + 令牌（官方协议实现用，语义见其 KDoc）。
 *
 * 两个 `@Binds` 指向同一个 [PreferencesAiProviderSettings] 实例（它带 `@Singleton`），
 * 因此"在设置页保存"与"发起请求时读到"必然是同一份数据。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindAiProviderSettings(impl: PreferencesAiProviderSettings): AiProviderSettings

    @Binds
    @Singleton
    abstract fun bindAiGatewaySettings(impl: PreferencesAiProviderSettings): AiGatewaySettings

    /** token / apiKey 加密（2026-10-22 审核 P1）：AndroidKeyStore AES/GCM，测试注入假实现。 */
    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher
}

/**
 * 运行时路由装配（开源改造 P0）。
 *
 * 三种能力各自取"官方实现 + 直连实现"，包一层 [RoutingContentEnricher] 等包装器，
 * 由包装器在**每次调用**时按当前配置选路 —— 用户在设置页切换后立即生效，不用重启。
 *
 * 为什么不在 [AiModule] 里用 `@Binds` 直接绑某一个实现：
 * - 绑官方实现 → 自定义模式永远走不到；
 * - 绑自定义实现 → 官方模式（默认安装包）永远走不到；
 * - 用限定符按编译期常量选 → 运行时切换做不到（Hilt 图是编译期固定的）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AiRoutingModule {

    @Provides
    @Singleton
    fun provideContentEnricher(
        settings: AiProviderSettings,
        official: RemoteContentEnricher,
        custom: DirectChatProvider,
    ): ContentEnricher = RoutingContentEnricher(settings, official, custom)

    @Provides
    @Singleton
    fun provideEmbedder(
        settings: AiProviderSettings,
        official: RemoteEmbedder,
        custom: DirectEmbedProvider,
    ): Embedder = RoutingEmbedder(settings, official, custom)

    @Provides
    @Singleton
    fun provideSpeechTranscriber(
        settings: AiProviderSettings,
        official: RemoteSpeechTranscriber,
        custom: DirectAsrProvider,
    ): SpeechTranscriber = RoutingSpeechTranscriber(settings, official, custom)
}