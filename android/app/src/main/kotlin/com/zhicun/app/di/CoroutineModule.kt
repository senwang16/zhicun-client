package com.zhicun.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级协程作用域（M6 审核整改：启动补投需要一个"活得和进程一样久"的 Scope）。
 *
 * 为什么不用 `GlobalScope`：AGENTS.md §8 明令禁止 —— 它没有生命周期归属，
 * 测试里也无法替换。这里显式提供一个带 `SupervisorJob` 的单例 Scope：
 * 子任务失败不会拖死其它任务，且注入点明确（谁在用一目了然）。
 *
 * 只给"进程级、无 UI 归属"的工作用（例如启动时补投后台任务）。
 * ViewModel 一律用 `viewModelScope`，页面一律用 `lifecycleScope`。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
