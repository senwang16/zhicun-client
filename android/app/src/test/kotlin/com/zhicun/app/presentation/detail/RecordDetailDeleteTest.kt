package com.zhicun.app.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.AddUserTagUseCase
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.ConfirmCloudProcessingUseCase
import com.zhicun.app.domain.usecase.ConfirmWebFetchUseCase
import com.zhicun.app.domain.usecase.GetDerivedContentUseCase
import com.zhicun.app.domain.usecase.GetRecordByIdUseCase
import com.zhicun.app.domain.usecase.GetRelatedRecordsUseCase
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.usecase.UpdateRecordTextUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeAudioPlayer
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 详情页删除动作测试（2026-10-29）：删单个附件、最后一项内容的引导、删整条记录。
 *
 * 单列一个文件：`RecordDetailViewModelTest` 测的是读取路径，这里测的是写路径与事件流。
 */
@RunWith(RobolectricTestRunner::class)
class RecordDetailDeleteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 被测 ViewModel 与它背后那几个需要断言的假仓储。 */
    private class Harness(
        val viewModel: RecordDetailViewModel,
        val records: FakeRecordRepository,
        val assets: FakeAssetRepository,
    )

    private fun harness(
        record: Record,
        assets: List<Asset>,
        fileStore: FakeLocalFileStore = FakeLocalFileStore(),
        records: FakeRecordRepository = FakeRecordRepository(listOf(record)),
    ): Harness {
        val assetRepository = FakeAssetRepository(assets)
        val collections = FakeCollectionRepository()
        val tagRepository = FakeTagRepository()
        val derived = FakeDerivedContentRepository()
        val jobs = FakeProcessingJobRepository()
        val scheduler = FakeProcessingScheduler()
        val aiSettings = FakeAiSettings()
        val gateway = FakeAiGatewaySettings()
        val retry = RetryProcessingUseCase(records, jobs, scheduler, aiSettings, gateway)
        val confirmFetch = ConfirmWebFetchUseCase(records, jobs, scheduler)

        val viewModel = RecordDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("recordId" to record.id)),
            getRecordById = GetRecordByIdUseCase(records),
            getDerivedContent = GetDerivedContentUseCase(derived),
            assetRepository = assetRepository,
            recordRepository = records,
            collectionRepository = collections,
            retryProcessingUseCase = retry,
            updateRecordText = UpdateRecordTextUseCase(
                recordRepository = records,
                derivedContentRepository = derived,
                collectionRepository = collections,
                tagRepository = tagRepository,
                sensitiveDetector = SensitiveContentDetector(),
                retryProcessing = retry,
                diagnosticLog = FakeDiagnosticLog(),
            ),
            confirmCloudProcessing = ConfirmCloudProcessingUseCase(
                recordRepository = records,
                collectionRepository = collections,
                jobRepository = jobs,
                derivedContentRepository = derived,
                assetRepository = assetRepository,
                scheduler = scheduler,
                aiSettings = aiSettings,
                gatewaySettings = gateway,
                confirmWebFetch = confirmFetch,
            ),
            confirmWebFetch = confirmFetch,
            tagRepository = tagRepository,
            addUserTag = AddUserTagUseCase(tagRepository),
            archiveRecords = ArchiveRecordsUseCase(records),
            getRelatedRecords = GetRelatedRecordsUseCase(FakeEmbeddingRepository(), records),
            deleteAsset = testDeleteAssetUseCase(
                recordRepository = records,
                retryProcessing = retry,
                assetRepository = assetRepository,
                derivedContentRepository = derived,
                collectionRepository = collections,
                tagRepository = tagRepository,
                fileStore = fileStore,
            ),
            deleteRecords = testDeleteRecordsUseCase(
                recordRepository = records,
                assetRepository = assetRepository,
                collectionRepository = collections,
                scheduler = scheduler,
                fileStore = fileStore,
            ),
            audioPlayer = FakeAudioPlayer(),
            aiSettings = aiSettings,
            gatewaySettings = gateway,
            // 2026-10-29 修复 P2：转写区失败判据读任务表（空表 = 未失败）
            processingJobRepository = jobs,
        )
        return Harness(viewModel, records, assetRepository)
    }

    private fun RecordDetailViewModel.assetIds(): List<String> =
        (uiState.value as RecordDetailUiState.Content).assets.map { it.id }

    /** 两张图删掉一张：库里只剩另一张，界面收到"已删除"。 */
    @Test
    fun onDeleteAsset_removesRowAndEmitsDeleted() = runTest(mainDispatcherRule.dispatcher) {
        val record = testRecord(id = "r1", type = RecordType.IMAGE, rawText = null)
        val keep = testAsset(id = "a2", recordId = "r1", orderIndex = 1)
        val target = testAsset(id = "a1", recordId = "r1")
        val h = harness(record, listOf(target, keep), FakeLocalFileStore(listOf(target.localPath)))
        val events = mutableListOf<RecordDetailEvent>()
        val collector = launch { h.viewModel.events.collect { events += it } }

        h.viewModel.onDeleteAsset("a1")

        assertEquals(listOf<RecordDetailEvent>(RecordDetailEvent.AssetDeleted), events)
        assertEquals(listOf("a2"), h.viewModel.assetIds())
        assertEquals(listOf("a2"), h.assets.stored.map { it.id })
        collector.cancel()
    }

    /** 最后一项内容：不删，引导删整条记录（否则会留一条空记录）。 */
    @Test
    fun onDeleteAsset_lastContent_asksToDeleteWholeRecord() = runTest(mainDispatcherRule.dispatcher) {
        val record = testRecord(id = "r1", type = RecordType.IMAGE, rawText = null)
        val only = testAsset(id = "a1", recordId = "r1")
        val h = harness(record, listOf(only), FakeLocalFileStore(listOf(only.localPath)))
        val events = mutableListOf<RecordDetailEvent>()
        val collector = launch { h.viewModel.events.collect { events += it } }

        h.viewModel.onDeleteAsset("a1")

        assertEquals(listOf<RecordDetailEvent>(RecordDetailEvent.LastContent), events)
        assertEquals("最后一项不该被删掉", listOf("a1"), h.viewModel.assetIds())
        collector.cancel()
    }

    /** 文件删不掉（被占用）：数据库一行没动，界面提示失败、附件仍在。 */
    @Test
    fun onDeleteAsset_fileLocked_emitsFailedAndKeepsRow() = runTest(mainDispatcherRule.dispatcher) {
        val record = testRecord(id = "r1", type = RecordType.IMAGE, rawText = null)
        val target = testAsset(id = "a1", recordId = "r1")
        val keep = testAsset(id = "a2", recordId = "r1", orderIndex = 1)
        val fileStore = FakeLocalFileStore(listOf(target.localPath)).apply {
            undeletable += target.localPath
        }
        val h = harness(record, listOf(target, keep), fileStore)
        val events = mutableListOf<RecordDetailEvent>()
        val collector = launch { h.viewModel.events.collect { events += it } }

        h.viewModel.onDeleteAsset("a1")

        assertEquals(listOf<RecordDetailEvent>(RecordDetailEvent.AssetDeleteFailed), events)
        assertEquals("文件没删掉时附件行必须保留", listOf("a1", "a2"), h.viewModel.assetIds())
        collector.cancel()
    }

    /** 删除整条记录：软删除落地，界面收到"已删除"（随后由页面返回列表）。 */
    @Test
    fun onDeleteRecord_softDeletesAndEmitsDeleted() = runTest(mainDispatcherRule.dispatcher) {
        val record = testRecord(id = "r1", rawText = "内容")
        val h = harness(record, emptyList())
        val events = mutableListOf<RecordDetailEvent>()
        val collector = launch { h.viewModel.events.collect { events += it } }

        h.viewModel.onDeleteRecord()

        assertEquals(listOf<RecordDetailEvent>(RecordDetailEvent.RecordDeleted), events)
        // 假仓储的 softDeleteMany 立即把记录移出查询结果（生产是软删除 + 清理）
        assertNull("记录不该再从正常查询里出来", h.records.getById("r1"))
        collector.cancel()
    }

    @Test
    fun onDeleteRecord_failure_emitsFailed() = runTest(mainDispatcherRule.dispatcher) {
        val record = testRecord(id = "r1", rawText = "内容")
        val records = FakeRecordRepository(listOf(record)).apply {
            deleteFailure = IllegalStateException("db boom")
        }
        val h = harness(record, emptyList(), records = records)
        val events = mutableListOf<RecordDetailEvent>()
        val collector = launch { h.viewModel.events.collect { events += it } }

        h.viewModel.onDeleteRecord()

        assertEquals(listOf<RecordDetailEvent>(RecordDetailEvent.RecordDeleteFailed), events)
        collector.cancel()
    }
}
