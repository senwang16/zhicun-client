package com.zhicun.app.di

import android.content.Context
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.dao.AssetDao
import com.zhicun.app.data.local.dao.CollectionDao
import com.zhicun.app.data.local.dao.DerivedContentDao
import com.zhicun.app.data.local.dao.EmbeddingMetaDao
import com.zhicun.app.data.local.dao.MaintenanceDao
import com.zhicun.app.data.local.dao.ProcessingJobDao
import com.zhicun.app.data.local.dao.RecordDao
import com.zhicun.app.data.local.dao.RecordFtsDao
import com.zhicun.app.data.local.dao.SearchHistoryDao
import com.zhicun.app.data.local.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库装配。
 *
 * 数据库实例必须是 @Singleton：多实例会各自持有连接与 FTS 回调状态，
 * 造成写入不可见、检索结果不一致等难查问题。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabaseFactory.create(context)

    @Provides
    fun provideRecordDao(database: AppDatabase): RecordDao = database.recordDao()

    @Provides
    fun provideAssetDao(database: AppDatabase): AssetDao = database.assetDao()

    @Provides
    fun provideDerivedContentDao(database: AppDatabase): DerivedContentDao =
        database.derivedContentDao()

    @Provides
    fun provideProcessingJobDao(database: AppDatabase): ProcessingJobDao =
        database.processingJobDao()

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()

    @Provides
    fun provideRecordFtsDao(database: AppDatabase): RecordFtsDao = database.recordFtsDao()

    /** 合集成员关系（M20）。 */
    @Provides
    fun provideCollectionDao(database: AppDatabase): CollectionDao = database.collectionDao()

    /** 维护用 DAO（M15）：全是物理删除语句，特意与上面的业务 DAO 分开提供。 */
    @Provides
    fun provideMaintenanceDao(database: AppDatabase): MaintenanceDao = database.maintenanceDao()

    /** M21：最近搜索（只存本机查询串）。 */
    @Provides
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    /** v10：向量元信息（开源改造 P1），与派生内容表里的向量本体成对读写。 */
    @Provides
    fun provideEmbeddingMetaDao(database: AppDatabase): EmbeddingMetaDao =
        database.embeddingMetaDao()
}
