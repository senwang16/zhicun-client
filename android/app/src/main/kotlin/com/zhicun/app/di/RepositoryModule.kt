package com.zhicun.app.di

import android.content.Context
import com.zhicun.app.data.repository.AssetRepositoryImpl
import com.zhicun.app.data.repository.CollectionRepositoryImpl
import com.zhicun.app.data.repository.DerivedContentRepositoryImpl
import com.zhicun.app.data.repository.EmbeddingRepositoryImpl
import com.zhicun.app.data.repository.FileDraftRepository
import com.zhicun.app.data.repository.ProcessingJobRepositoryImpl
import com.zhicun.app.data.repository.RecordRepositoryImpl
import com.zhicun.app.data.repository.SearchHistoryRepositoryImpl
import com.zhicun.app.data.repository.SearchRepositoryImpl
import com.zhicun.app.data.repository.SemanticIndexRepositoryImpl
import com.zhicun.app.data.repository.TagRepositoryImpl
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.DraftRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.SearchHistoryRepository
import com.zhicun.app.domain.repository.SearchRepository
import com.zhicun.app.domain.repository.SemanticIndexRepository
import com.zhicun.app.domain.repository.TagRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Repository 接口 → 实现绑定。
 *
 * presentation 层只注入 domain 接口（AGENTS.md §2.3），因此实现类永远不暴露给上层。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRecordRepository(impl: RecordRepositoryImpl): RecordRepository

    @Binds
    @Singleton
    abstract fun bindAssetRepository(impl: AssetRepositoryImpl): AssetRepository

    @Binds
    @Singleton
    abstract fun bindProcessingJobRepository(
        impl: ProcessingJobRepositoryImpl,
    ): ProcessingJobRepository

    /** M7：OCR / 网页提取的产物落这张表。 */
    @Binds
    @Singleton
    abstract fun bindDerivedContentRepository(
        impl: DerivedContentRepositoryImpl,
    ): DerivedContentRepository

    /** M20：合集成员关系与由成员变化触发的合集刷新。 */
    @Binds
    @Singleton
    abstract fun bindCollectionRepository(
        impl: CollectionRepositoryImpl,
    ): CollectionRepository

    /** M10：关键词检索（FTS4 + 子串两路合并）。 */
    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    /** M21：最近搜索（本机、去重、裁剪）。 */
    @Binds
    @Singleton
    abstract fun bindSearchHistoryRepository(
        impl: SearchHistoryRepositoryImpl,
    ): SearchHistoryRepository

    /** M11：AI 标签与用户标签分开存储，替换 AI 标签时不动用户标签。 */
    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    /** M12：向量存放（T-003 决策：复用 derived_contents，不建独立向量表）。 */
    @Binds
    @Singleton
    abstract fun bindEmbeddingRepository(impl: EmbeddingRepositoryImpl): EmbeddingRepository

    /** M12：向量检索（当前实现为全量载入 + 暴力余弦，接口留着以便换 ANN）。 */
    @Binds
    @Singleton
    abstract fun bindSemanticIndexRepository(
        impl: SemanticIndexRepositoryImpl,
    ): SemanticIndexRepository

    companion object {

        /**
         * 草稿仓储（M5）。
         *
         * 用 @Provides 而不是 @Binds：实现类持有的是 `File`，只有 DI 层知道草稿该放哪。
         * 这样实现类保持纯 JVM 可测（测试直接传临时目录），不必把 `Context` 渗进仓储。
         */
        @Provides
        @Singleton
        fun provideDraftRepository(@ApplicationContext context: Context): DraftRepository =
            FileDraftRepository(File(context.filesDir, "drafts/create-draft.txt"))
    }
}
