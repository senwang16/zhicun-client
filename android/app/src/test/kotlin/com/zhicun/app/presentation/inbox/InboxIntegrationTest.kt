package com.zhicun.app.presentation.inbox

import com.zhicun.app.data.file.AndroidLocalFileStore
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.repository.AssetRepositoryImpl
import com.zhicun.app.data.repository.EmbeddingRepositoryImpl
import com.zhicun.app.data.repository.MaintenanceRepositoryImpl
import com.zhicun.app.data.repository.TagRepositoryImpl
import com.zhicun.app.data.repository.DerivedContentRepositoryImpl
import com.zhicun.app.data.repository.ProcessingJobRepositoryImpl
import com.zhicun.app.data.repository.RecordRepositoryImpl
import com.zhicun.app.domain.inbox.InboxListSettings
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.ConfirmCloudProcessingUseCase
import com.zhicun.app.domain.usecase.ConfirmWebFetchUseCase
import com.zhicun.app.domain.usecase.GetDerivedContentUseCase
import com.zhicun.app.domain.usecase.GetRecordByIdUseCase
import com.zhicun.app.domain.usecase.AddUserTagUseCase
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.CreateCollectionUseCase
import com.zhicun.app.domain.usecase.DeleteAssetUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.FavoriteRecordsUseCase
import com.zhicun.app.domain.usecase.GetRecordsUseCase
import com.zhicun.app.domain.usecase.GetRelatedRecordsUseCase
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.domain.usecase.UpdateRecordTextUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAudioPlayer
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.detail.RecordDetailUiState
import com.zhicun.app.presentation.detail.RecordDetailViewModel
import com.zhicun.app.util.MainDispatcherRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 端到端数据链路测试（M3 验收："向 Room 插入测试数据后卡片正确显示"）。
 *
 * 链路是真的：Room in-memory DB → DAO → Repository → UseCase → ViewModel → UiState，
 * 全程没有假实现。最后一段（UiState → 卡片 DOM）由 [InboxScreenTest] 覆盖，
 * 两个测试拼起来就是完整的"数据库里的一条记录 → 屏幕上的一张卡"。
 *
 * 这里用 `runBlocking` 而不是 `runTest`：Room 的挂起查询跑在自己的执行器（真实线程）上，
 * `runTest` 的虚拟时间会在等待真实线程时直接推进到超时，造成假失败。
 */
@RunWith(RobolectricTestRunner::class)
class InboxIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AppDatabase
    private lateinit var recordRepository: RecordRepositoryImpl
    private lateinit var collectionRepository: com.zhicun.app.data.repository.CollectionRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        recordRepository = RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        )
        collectionRepository = com.zhicun.app.data.repository.CollectionRepositoryImpl(
            database = database,
            recordDao = database.recordDao(),
            collectionDao = database.collectionDao(),
            derivedContentDao = database.derivedContentDao(),
            sensitiveDetector = SensitiveContentDetector(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * 收件箱 ViewModel 的唯一构造处。
     *
     * 之前四个用例各写一份构造，加一个依赖就要改四处（而且漏一处就是编译错误，
     * 不是测试失败 —— 容易被当成"顺手改一下"）。这里集中一次，用例只关心数据。
     *
     * 依赖尽量用真实现：内存 Room + 真仓储 + 真文件操作。`ProcessingScheduler` 是唯一
     * 的假实现（它背后是 WorkManager，单元测试里没有），删除路径下它只负责"取消任务"，
     * 而本文件不验证任务取消（那由 `DeleteRecordsUseCaseTest` 覆盖）。
     */
    private fun inboxViewModel(): InboxViewModel {
        val cleanup = CleanupDeletedRecordsUseCase(
            maintenanceRepository = MaintenanceRepositoryImpl(database.maintenanceDao()),
            assetRepository = AssetRepositoryImpl(database.assetDao()),
            fileStore = AndroidLocalFileStore(),
        )
        return InboxViewModel(
            getRecords = GetRecordsUseCase(recordRepository),
            tagRepository = TagRepositoryImpl(database.tagDao()),
            archiveRecords = ArchiveRecordsUseCase(recordRepository),
            favoriteRecords = FavoriteRecordsUseCase(recordRepository),
            deleteRecords = DeleteRecordsUseCase(
                recordRepository = recordRepository,
                collectionRepository = collectionRepository,
                cleanupDeletedRecords = cleanup,
                scheduler = FakeProcessingScheduler(),
            ),
            // M19：折叠偏好。集成测试不关心持久化，空实现即可（默认全展开）
            listSettings = object : InboxListSettings {
                override val collapsedDays: Set<java.time.LocalDate> = emptySet()
                override fun saveCollapsedDays(days: Set<java.time.LocalDate>) = Unit
            },
            // M20：合并为合集。链路保持真实，调度器仍是唯一的假实现
            createCollection = CreateCollectionUseCase(
                recordRepository = recordRepository,
                collectionRepository = collectionRepository,
                scheduleProcessing = ScheduleProcessingUseCase(
                    jobRepository = ProcessingJobRepositoryImpl(database.processingJobDao()),
                    scheduler = FakeProcessingScheduler(),
                    recordRepository = recordRepository,
                    aiSettings = FakeAiSettings(),
                    gatewaySettings = FakeAiGatewaySettings(),
                ),
                sensitiveDetector = SensitiveContentDetector(),
                diagnosticLog = FakeDiagnosticLog(),
            ),
        )
    }

    @Test
    fun Inbox_roomWithTwoRecords_exposesContentNewestFirst() = runBlocking {
        recordRepository.save(testRecord(id = "older", createdAt = 1_000L, rawText = "较早的记录"))
        recordRepository.save(testRecord(id = "newer", createdAt = 2_000L, rawText = "较新的记录"))

        val viewModel = inboxViewModel()
        val state = withTimeout(TIMEOUT_MILLIS) {
            viewModel.uiState.first { it is InboxUiState.Content }
        }

        assertTrue(state is InboxUiState.Content)
        assertEquals(
            listOf("newer", "older"),
            (state as InboxUiState.Content).records.map { it.id },
        )
    }

    @Test
    fun Inbox_softDeletedRecord_isNotExposed() = runBlocking {
        recordRepository.save(testRecord(id = "keep", createdAt = 1_000L))
        recordRepository.save(testRecord(id = "gone", createdAt = 2_000L))

        recordRepository.softDelete(id = "gone", updatedAt = 3_000L)

        val viewModel = inboxViewModel()
        val state = withTimeout(TIMEOUT_MILLIS) {
            viewModel.uiState.first { it is InboxUiState.Content }
        } as InboxUiState.Content
        assertEquals(listOf("keep"), state.records.map { it.id })
    }

    @Test
    fun Inbox_emptyRoom_exposesEmptyState() = runBlocking {
        val viewModel = inboxViewModel()

        val state = withTimeout(TIMEOUT_MILLIS) {
            viewModel.uiState.first { it is InboxUiState.Empty || it is InboxUiState.Content }
        }

        assertEquals(InboxUiState.Empty, state)
    }

    @Test
    fun RecordDetail_recordSavedInRoom_exposesContent() = runBlocking {
        recordRepository.save(testRecord(id = "r1", rawText = "详情页原文"))

        // 2026-10-24：确认上传时会顺带调用抓取确认，两处依赖共用同一个实例
        val confirmFetch = ConfirmWebFetchUseCase(
            recordRepository = recordRepository,
            jobRepository = ProcessingJobRepositoryImpl(database.processingJobDao()),
            scheduler = FakeProcessingScheduler(),
        )
        val conflictFreeRetry = RetryProcessingUseCase(
            recordRepository = recordRepository,
            jobRepository = ProcessingJobRepositoryImpl(database.processingJobDao()),
            scheduler = FakeProcessingScheduler(),
            aiSettings = FakeAiSettings(),
            gatewaySettings = FakeAiGatewaySettings(),
        )
        val viewModel = RecordDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("recordId" to "r1")),
            getRecordById = GetRecordByIdUseCase(recordRepository),
            getDerivedContent = GetDerivedContentUseCase(
                DerivedContentRepositoryImpl(database.derivedContentDao()),
            ),
            assetRepository = AssetRepositoryImpl(database.assetDao()),
            collectionRepository = collectionRepository,
            retryProcessingUseCase = conflictFreeRetry,
            updateRecordText = UpdateRecordTextUseCase(
                recordRepository = recordRepository,
                derivedContentRepository = DerivedContentRepositoryImpl(database.derivedContentDao()),
                collectionRepository = collectionRepository,
                tagRepository = TagRepositoryImpl(database.tagDao()),
                sensitiveDetector = SensitiveContentDetector(),
                retryProcessing = conflictFreeRetry,
                diagnosticLog = FakeDiagnosticLog(),
            ),
            // M11：确认上传与标签读取
            confirmCloudProcessing = ConfirmCloudProcessingUseCase(
                recordRepository = recordRepository,
                collectionRepository = collectionRepository,
                jobRepository = ProcessingJobRepositoryImpl(database.processingJobDao()),
                derivedContentRepository = DerivedContentRepositoryImpl(database.derivedContentDao()),
                assetRepository = AssetRepositoryImpl(database.assetDao()),
                scheduler = FakeProcessingScheduler(),
                aiSettings = FakeAiSettings(),
                gatewaySettings = FakeAiGatewaySettings(),
                confirmWebFetch = confirmFetch,
            ),
            confirmWebFetch = confirmFetch,
            tagRepository = TagRepositoryImpl(database.tagDao()),
            // M16：整理能力
            recordRepository = recordRepository,
            addUserTag = AddUserTagUseCase(TagRepositoryImpl(database.tagDao())),
            archiveRecords = ArchiveRecordsUseCase(recordRepository),
            // M18：相关推荐用真实实现 + 空向量库（库里没有向量 → 分区为空）
            getRelatedRecords = GetRelatedRecordsUseCase(
                EmbeddingRepositoryImpl(
                    database.derivedContentDao(),
                    database.embeddingMetaDao(),
                ),
                recordRepository,
            ),
            // 2026-10-29：删除动作的依赖（本用例不触发删除，真实仓储 + 假文件即可）
            deleteAsset = DeleteAssetUseCase(
                recordRepository = recordRepository,
                assetRepository = AssetRepositoryImpl(database.assetDao()),
                derivedContentRepository = DerivedContentRepositoryImpl(database.derivedContentDao()),
                collectionRepository = collectionRepository,
                tagRepository = TagRepositoryImpl(database.tagDao()),
                retryProcessing = conflictFreeRetry,
                fileStore = FakeLocalFileStore(),
                diagnosticLog = FakeDiagnosticLog(),
            ),
            deleteRecords = DeleteRecordsUseCase(
                recordRepository = recordRepository,
                collectionRepository = collectionRepository,
                cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
                    maintenanceRepository = MaintenanceRepositoryImpl(database.maintenanceDao()),
                    assetRepository = AssetRepositoryImpl(database.assetDao()),
                    fileStore = FakeLocalFileStore(),
                ),
                scheduler = FakeProcessingScheduler(),
            ),
            // M9：详情页多了播放器依赖；留言记录不播音频，用假实现即可
            audioPlayer = FakeAudioPlayer(),
            // R-5：详情页读门禁状态决定"未上传"文案
            aiSettings = FakeAiSettings(),
            gatewaySettings = FakeAiGatewaySettings(),
            // 2026-10-29 修复 P2：转写区失败判据读任务表
            processingJobRepository = ProcessingJobRepositoryImpl(database.processingJobDao()),
        )

        val state = withTimeout(TIMEOUT_MILLIS) {
            viewModel.uiState.first { it !is RecordDetailUiState.Loading }
        }

        assertTrue("应为 Content，实际 $state", state is RecordDetailUiState.Content)
        assertEquals("详情页原文", (state as RecordDetailUiState.Content).record.rawText)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
