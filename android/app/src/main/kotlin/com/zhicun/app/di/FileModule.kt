package com.zhicun.app.di

import com.zhicun.app.data.file.ContentResolverAssetNameResolver
import com.zhicun.app.data.file.ContentResolverMimeTypeResolver
import com.zhicun.app.data.file.ExternalAssetImporterImpl
import com.zhicun.app.data.web.WebSnapshotStoreImpl
import com.zhicun.app.domain.file.AssetNameResolver
import com.zhicun.app.domain.file.ExternalAssetImporter
import com.zhicun.app.domain.file.MimeTypeResolver
import com.zhicun.app.domain.web.WebSnapshotStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 文件相关依赖。
 *
 * 实现类自己带 `@Singleton @Inject constructor`，这里只做"接口 → 实现"的绑定，
 * 具体用哪个实现由这一个文件决定（换实现不用改调用方）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FileModule {

    @Binds
    abstract fun bindExternalAssetImporter(impl: ExternalAssetImporterImpl): ExternalAssetImporter

    /** M5：手动新建时按 URI 查真实 MIME，供记录分类使用。 */
    @Binds
    abstract fun bindMimeTypeResolver(impl: ContentResolverMimeTypeResolver): MimeTypeResolver

    /** 多附件：新建页在保存之前就要显示原始文件名。 */
    @Binds
    abstract fun bindAssetNameResolver(impl: ContentResolverAssetNameResolver): AssetNameResolver

    /** C：网页快照落盘进 Asset 表，随导出（M15）/清理统一管理。 */
    @Binds
    abstract fun bindWebSnapshotStore(impl: WebSnapshotStoreImpl): WebSnapshotStore
}
