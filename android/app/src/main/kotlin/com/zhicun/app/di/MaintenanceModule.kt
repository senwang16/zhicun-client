package com.zhicun.app.di

import com.zhicun.app.data.export.AndroidArchiveShareLauncher
import com.zhicun.app.data.export.ZipExportWriter
import com.zhicun.app.data.file.AndroidLocalFileStore
import com.zhicun.app.data.repository.MaintenanceRepositoryImpl
import com.zhicun.app.domain.export.ArchiveShareLauncher
import com.zhicun.app.domain.export.ExportArchiveWriter
import com.zhicun.app.domain.maintenance.LocalFileStore
import com.zhicun.app.domain.repository.MaintenanceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 导出与清理（M15）。
 *
 * 四个绑定都指向"和文件系统/系统 Intent 打交道"的实现，所以集中在这里：
 * 上层（use case / ViewModel）只看到 domain 的四个接口。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MaintenanceModule {

    /** 清理用的物理删除（集中在一个 DAO，避免被误调）。 */
    @Binds
    @Singleton
    abstract fun bindMaintenanceRepository(
        impl: MaintenanceRepositoryImpl,
    ): MaintenanceRepository

    /** 文件存在性 / 大小 / 删除。导出与清理共用。 */
    @Binds
    @Singleton
    abstract fun bindLocalFileStore(impl: AndroidLocalFileStore): LocalFileStore

    /** 写 ZIP（落在 cacheDir/exports，无需存储权限）。 */
    @Binds
    @Singleton
    abstract fun bindExportArchiveWriter(impl: ZipExportWriter): ExportArchiveWriter

    /** 分享面板（FileProvider content URI）。 */
    @Binds
    @Singleton
    abstract fun bindArchiveShareLauncher(
        impl: AndroidArchiveShareLauncher,
    ): ArchiveShareLauncher
}
