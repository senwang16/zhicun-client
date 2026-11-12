package com.zhicun.app.di

import com.zhicun.app.data.worker.WorkManagerBubbleRestoreScheduler
import com.zhicun.app.data.worker.WorkManagerProcessingScheduler
import com.zhicun.app.domain.worker.BubbleRestoreScheduler
import com.zhicun.app.domain.worker.ProcessingScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 后台任务调度绑定（M6）。
 *
 * 只绑定调度器；Worker 本身由 `HiltWorkerFactory` 经 `@HiltWorker` 构造，
 * 工厂实例由 androidx.hilt 提供并在 [com.zhicun.app.ZhiCunApplication] 中注入。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {

    @Binds
    @Singleton
    abstract fun bindProcessingScheduler(
        impl: WorkManagerProcessingScheduler,
    ): ProcessingScheduler

    /**
     * 开机恢复悬浮气泡的延迟重试（2026-11-10 Bug 2）。
     *
     * 独立于 [ProcessingScheduler]：它不属于任何记录的 AI 处理链，
     * 只是一条"系统暂时不允许后台起前台服务"的补救通道。
     */
    @Binds
    @Singleton
    abstract fun bindBubbleRestoreScheduler(
        impl: WorkManagerBubbleRestoreScheduler,
    ): BubbleRestoreScheduler
}
