package com.zhicun.app.di

import com.zhicun.app.data.web.InetHostAddressResolver
import com.zhicun.app.data.web.OkHttpImageFetcher
import com.zhicun.app.data.web.OkHttpWebPageLoader
import com.zhicun.app.domain.web.HostAddressResolver
import com.zhicun.app.domain.web.ImageFetcher
import com.zhicun.app.domain.web.SafeUrlChecker
import com.zhicun.app.domain.web.UrlChecker
import com.zhicun.app.domain.web.WebPageLoader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 网络层（M8）。
 *
 * `OkHttpClient` 必须**全局单例**：它内部维护连接池与线程池，每次 new 一个等于
 * 每次重开一套，任务一多就是线程爆炸。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** M8 验收要求：请求总时长不超过 10 秒。 */
    private const val TIMEOUT_SECONDS = 10L

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 总时长兜底：服务端一点点挤数据时，单次 read 都不会超时，只有 callTimeout 能拦住
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 重定向必须由我们自己跟：每一跳都要重新做 SSRF 校验（见 OkHttpWebPageLoader）
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideSafeUrlChecker(resolver: HostAddressResolver): UrlChecker = SafeUrlChecker(resolver)
}

/** 网络相关接口绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingsModule {

    @Binds
    abstract fun bindHostAddressResolver(impl: InetHostAddressResolver): HostAddressResolver

    @Binds
    abstract fun bindWebPageLoader(impl: OkHttpWebPageLoader): WebPageLoader

    /** C：og:image 抓取，与页面加载器同一套 SSRF/超时/上限标准。 */
    @Binds
    abstract fun bindImageFetcher(impl: OkHttpImageFetcher): ImageFetcher
}
