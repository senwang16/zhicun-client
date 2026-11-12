package com.zhicun.app.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.RecordTag
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
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAudioPlayer
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 详情页"整理"动作测试（M16）：收藏 / 置顶 / 归档 / 标签增删。
 *
 * 单独一个文件而不是塞进 `RecordDetailViewModelTest`：那个文件已经在测读路径，
 * 这里测的是写路径，混在一起会让"哪一条挂了"变得难判断。
 */
@RunWith(RobolectricTestRunner::class)
class RecordDetailOrganizeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tagRepository = FakeTagRepository()

    private fun viewModel(
        record: Record = testRecord(id = "r1"),
        repository: FakeRecordRepository = FakeRecordRepository(listOf(record)),
    ): RecordDetailViewModel {
        // 2026-10-24：确认上传时会顺带调用抓取确认，两处依赖共用同一个实例
        val confirmFetch = ConfirmWebFetchUseCase(
            recordRepository = repository,
            jobRepository = FakeProcessingJobRepository(),
            scheduler = FakeProcessingScheduler(),
        )
        val retry = RetryProcessingUseCase(
            repository,
            FakeProcessingJobRepository(),
            FakeProcessingScheduler(),
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        )
        return RecordDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("recordId" to record.id)),
        getRecordById = GetRecordByIdUseCase(repository),
        getDerivedContent = GetDerivedContentUseCase(FakeDerivedContentRepository()),
        assetRepository = FakeAssetRepository(),
        recordRepository = repository,
        collectionRepository = FakeCollectionRepository(),
        retryProcessingUseCase = retry,
        updateRecordText = UpdateRecordTextUseCase(
            recordRepository = repository,
            derivedContentRepository = FakeDerivedContentRepository(),
            collectionRepository = FakeCollectionRepository(),
            tagRepository = tagRepository,
            sensitiveDetector = SensitiveContentDetector(),
            retryProcessing = retry,
            diagnosticLog = FakeDiagnosticLog(),
        ),
        confirmCloudProcessing = ConfirmCloudProcessingUseCase(
            recordRepository = repository,
            collectionRepository = FakeCollectionRepository(),
            jobRepository = FakeProcessingJobRepository(),
            derivedContentRepository = FakeDerivedContentRepository(),
            assetRepository = FakeAssetRepository(),
            scheduler = FakeProcessingScheduler(),
            aiSettings = FakeAiSettings(),
            gatewaySettings = FakeAiGatewaySettings(),
            confirmWebFetch = confirmFetch,
        ),
        // F-5：允许抓取网页与云端确认是两条独立路径
        confirmWebFetch = confirmFetch,
        tagRepository = tagRepository,
        addUserTag = AddUserTagUseCase(tagRepository),
        archiveRecords = ArchiveRecordsUseCase(repository),
        // M18：相关推荐用空向量库 —— 本类不关心它，空实现即可
        getRelatedRecords = GetRelatedRecordsUseCase(FakeEmbeddingRepository(), repository),
        // 2026-10-29：删除动作的依赖（装配见 DetailTestUseCases.kt）
        deleteAsset = testDeleteAssetUseCase(repository, retry),
        deleteRecords = testDeleteRecordsUseCase(repository),
        audioPlayer = FakeAudioPlayer(),
        aiSettings = FakeAiSettings(),
        gatewaySettings = FakeAiGatewaySettings(),
        // 2026-10-29 修复 P2：转写区失败判据读任务表（空表 = 未失败）
        processingJobRepository = FakeProcessingJobRepository(),
        )
    }

    private fun content(viewModel: RecordDetailViewModel): RecordDetailUiState.Content =
        viewModel.uiState.value as RecordDetailUiState.Content

    /** 已挂在该记录上的用户标签名（直接读假仓储，不绕经 uiState）。 */
    private fun userTagNames(recordId: String = "r1"): List<String> =
        tagRepository.associations.value
            .filter { it.recordId == recordId && it.tag.source == TagSource.USER }
            .map { it.tag.name }

    // ------------------------------------------------------------ 收藏 / 置顶

    @Test
    fun onToggleFavorite_setsFlagAndUpdatesRenderedRecord() = runTest {
        val viewModel = viewModel()

        viewModel.onToggleFavorite()

        assertTrue(content(viewModel).record.isFavorite)
        assertFalse("收藏不该顺手把记录置顶", content(viewModel).record.isPinned)
    }

    @Test
    fun onToggleFavoriteTwice_clearsFlag() = runTest {
        val viewModel = viewModel(record = testRecord(id = "r1", isFavorite = true))

        viewModel.onToggleFavorite()

        assertFalse(content(viewModel).record.isFavorite)
    }

    @Test
    fun onTogglePinned_setsFlag() = runTest {
        val viewModel = viewModel()

        viewModel.onTogglePinned()

        assertTrue(content(viewModel).record.isPinned)
    }

    /**
     * 收藏与置顶只动标记，**不该**让 `recordRevision` 变化。
     *
     * 那个字段用来判断"AI 派生结果是不是过期了"（M11）。如果打个星标就递增，
     * 已有的摘要与标签会被判为过期并触发重算 —— 白白烧一次云端调用。
     */
    @Test
    fun onToggleFavorite_doesNotBumpRecordRevision() = runTest {
        val viewModel = viewModel()
        val before = content(viewModel).record.recordRevision

        viewModel.onToggleFavorite()

        assertEquals(before, content(viewModel).record.recordRevision)
    }

    // ------------------------------------------------------------ 归档

    @Test
    fun onToggleArchived_archivesRecordAndShowsUnarchiveLabel() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "r1")))
        val viewModel = viewModel(repository = repository)

        viewModel.onToggleArchived()

        assertTrue(content(viewModel).record.isArchived)
        // 归档不是删除：记录本身与原文都还在（只是退出搜索与相关推荐）
        assertFalse(content(viewModel).record.isDeleted)
        assertTrue(repository.stored.value.single().isArchived)
    }

    @Test
    fun onToggleArchived_whenAlreadyArchived_restoresIt() = runTest {
        val record = testRecord(id = "r1", archivedAt = 5_000L)
        val repository = FakeRecordRepository(listOf(record))
        val viewModel = viewModel(record = record, repository = repository)

        viewModel.onToggleArchived()

        assertNull(content(viewModel).record.archivedAt)
        assertFalse("取消归档后应回到收件箱", repository.stored.value.single().isArchived)
    }

    // ------------------------------------------------------------ 标签

    @Test
    fun onAddTag_validName_addsTagAndClearsInput() = runTest {
        val viewModel = viewModel()

        viewModel.onTagInputChange("  读书清单  ")
        viewModel.onAddTag()

        assertEquals("", viewModel.organize.value.tagInput)
        assertNull(viewModel.organize.value.tagError)
        // 规范化后的名字（首尾空白已去掉）
        assertEquals(listOf("读书清单"), userTagNames())
    }

    @Test
    fun onAddTag_tooLong_keepsInputAndShowsError() = runTest {
        val viewModel = viewModel()
        val tooLong = "标".repeat(AddUserTagUseCase.MAX_NAME_LENGTH + 1)

        viewModel.onTagInputChange(tooLong)
        viewModel.onAddTag()

        assertEquals(tooLong, viewModel.organize.value.tagInput)
        assertEquals(TagInputError.TooLong, viewModel.organize.value.tagError)
        assertTrue("超长标签不该落库", userTagNames().isEmpty())
    }

    @Test
    fun onAddTag_duplicate_showsDuplicateError() = runTest {
        val viewModel = viewModel()
        viewModel.onTagInputChange("工作")
        viewModel.onAddTag()

        viewModel.onTagInputChange("工作")
        viewModel.onAddTag()

        assertEquals(TagInputError.Duplicate, viewModel.organize.value.tagError)
        assertEquals(1, userTagNames().size)
    }

    @Test
    fun onAddTag_blankInput_clearsInputWithoutError() = runTest {
        val viewModel = viewModel()

        viewModel.onTagInputChange("   ")
        viewModel.onAddTag()

        assertEquals("", viewModel.organize.value.tagInput)
        assertNull("空白输入不该标红：用户可能只是误触了回车", viewModel.organize.value.tagError)
    }

    /** 用户重新开始输入时，上一次的错误提示必须消失，否则输入框会一直红着。 */
    @Test
    fun onTagInputChange_clearsPreviousError() = runTest {
        val viewModel = viewModel()
        viewModel.onTagInputChange("标".repeat(AddUserTagUseCase.MAX_NAME_LENGTH + 1))
        viewModel.onAddTag()
        assertEquals(TagInputError.TooLong, viewModel.organize.value.tagError)

        viewModel.onTagInputChange("短标签")

        assertNull(viewModel.organize.value.tagError)
    }

    @Test
    fun onRemoveTag_detachesTagFromRecord() = runTest {
        tagRepository.associations.value = listOf(
            RecordTag(
                recordId = "r1",
                tag = Tag(
                    id = "user-工作",
                    name = "工作",
                    source = TagSource.USER,
                    confidence = null,
                    createdAt = 1_000L,
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.onRemoveTag("user-工作")

        assertTrue(userTagNames().isEmpty())
    }

    @Test
    fun onRemoveTag_blankId_isIgnored() = runTest {
        tagRepository.associations.value = listOf(
            RecordTag(
                recordId = "r1",
                tag = Tag(
                    id = "user-工作",
                    name = "工作",
                    source = TagSource.USER,
                    confidence = null,
                    createdAt = 1_000L,
                ),
            ),
        )
        val viewModel = viewModel()

        // 不该抛异常，也不该打库（空白 id 一定是上游 bug）
        viewModel.onRemoveTag("   ")

        assertEquals(listOf("工作"), userTagNames())
    }
}
