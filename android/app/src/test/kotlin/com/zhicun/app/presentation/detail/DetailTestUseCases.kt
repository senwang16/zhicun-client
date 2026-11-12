package com.zhicun.app.presentation.detail

import com.zhicun.app.domain.maintenance.LocalFileStore
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.DeleteAssetUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.worker.ProcessingScheduler
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeTagRepository

/**
 * 详情页测试用的删除用例装配（2026-10-29）。
 *
 * 详情页 ViewModel 新增了两个删除用例依赖，三个测试文件都要构造它们。
 * 集中在这里，改构造参数时只改一处 —— 否则同一段装配会被抄三遍并逐渐走样。
 */
internal fun testDeleteAssetUseCase(
    recordRepository: RecordRepository,
    retryProcessing: RetryProcessingUseCase,
    assetRepository: AssetRepository = FakeAssetRepository(),
    derivedContentRepository: DerivedContentRepository = FakeDerivedContentRepository(),
    collectionRepository: CollectionRepository = FakeCollectionRepository(),
    tagRepository: TagRepository = FakeTagRepository(),
    fileStore: LocalFileStore = FakeLocalFileStore(),
): DeleteAssetUseCase = DeleteAssetUseCase(
    recordRepository = recordRepository,
    assetRepository = assetRepository,
    derivedContentRepository = derivedContentRepository,
    collectionRepository = collectionRepository,
    tagRepository = tagRepository,
    retryProcessing = retryProcessing,
    fileStore = fileStore,
    diagnosticLog = FakeDiagnosticLog(),
)

internal fun testDeleteRecordsUseCase(
    recordRepository: RecordRepository,
    assetRepository: AssetRepository = FakeAssetRepository(),
    collectionRepository: CollectionRepository = FakeCollectionRepository(),
    scheduler: ProcessingScheduler = FakeProcessingScheduler(),
    fileStore: LocalFileStore = FakeLocalFileStore(),
): DeleteRecordsUseCase = DeleteRecordsUseCase(
    recordRepository = recordRepository,
    collectionRepository = collectionRepository,
    cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
        maintenanceRepository = FakeMaintenanceRepository(),
        assetRepository = assetRepository,
        fileStore = fileStore,
    ),
    scheduler = scheduler,
)
