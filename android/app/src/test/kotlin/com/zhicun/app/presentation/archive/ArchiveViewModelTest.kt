package com.zhicun.app.presentation.archive

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.GetArchivedRecordsUseCase
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
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
 * 归档箱 ViewModel 单元测试（M16）。
 *
 * 重点不是"能不能列出归档记录"（那是 `RecordRepositoryImplTest` 的事），
 * 而是**恢复这条路径与收件箱归档是否严格互逆**：
 * 归档后从收件箱消失、恢复后回到收件箱、且 archive 时间戳被清掉。
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        repository: FakeRecordRepository,
        maintenance: FakeMaintenanceRepository = FakeMaintenanceRepository(),
    ): ArchiveViewModel {
        // 软删除要同步给清理侧（同收件箱测试）：两个假实现各有各的存储，
        // 不接起来的话删除用例软删除后清理看不到任何待清理记录，测试会一路绿灯却什么都没验证。
        repository.onSoftDelete = { ids ->
            ids.forEach { id ->
                maintenance.softDeletedRecords += testRecord(id = id, isDeleted = true)
            }
        }
        return ArchiveViewModel(
            getArchived = GetArchivedRecordsUseCase(repository),
            recordRepository = repository,
            tagRepository = FakeTagRepository(),
            archiveRecords = ArchiveRecordsUseCase(repository),
            deleteRecords = DeleteRecordsUseCase(
                recordRepository = repository,
                collectionRepository = FakeCollectionRepository(),
                cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
                    maintenanceRepository = maintenance,
                    assetRepository = FakeAssetRepository(),
                    fileStore = FakeLocalFileStore(),
                ),
                scheduler = FakeProcessingScheduler(),
            ),
        )
    }

    @Test
    fun ArchiveViewModel_beforeSubscribe_keepsLoadingInitialValue() {
        val viewModel = viewModel(FakeRecordRepository())

        assertEquals(ArchiveUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun ArchiveViewModel_noArchivedRecord_emitsEmpty() = runTest {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "inbox"))))

        viewModel.uiState.test {
            assertEquals(ArchiveUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ArchiveViewModel_onlyArchivedRecordsAreListed() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "inbox"),
                testRecord(id = "old", archivedAt = 1_000L),
                testRecord(id = "recent", archivedAt = 9_000L),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val state = awaitItem() as ArchiveUiState.Content
            // 按归档时间倒序：最近归档的排前面
            assertEquals(listOf("recent", "old"), state.records.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ArchiveViewModel_repositoryThrows_emitsError() = runTest {
        val repository = FakeRecordRepository().apply {
            failWith = IllegalStateException("database is locked")
        }
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue("应为 Error，实际 $state", state is ArchiveUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ 选中

    @Test
    fun ArchiveViewModel_startSelection_activatesAndSelects() {
        val viewModel = viewModel(FakeRecordRepository())

        viewModel.startSelection("a")

        assertTrue(viewModel.selection.value.active)
        assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
        assertTrue(viewModel.selection.value.canAct)
    }

    @Test
    fun ArchiveViewModel_toggleSelected_whenNotActive_isIgnored() {
        val viewModel = viewModel(FakeRecordRepository())

        viewModel.toggleSelected("a")

        assertTrue(viewModel.selection.value.selectedIds.isEmpty())
    }

    @Test
    fun ArchiveViewModel_enterSelectionMode_selectsNothingAndDisablesRestore() {
        val viewModel = viewModel(FakeRecordRepository())

        viewModel.enterSelectionMode()

        assertTrue(viewModel.selection.value.active)
        assertFalse(viewModel.selection.value.canAct)
    }

    // ------------------------------------------------------------ 恢复

    @Test
    fun ArchiveViewModel_restoreSelected_putsRecordsBackIntoInboxAndExitsSelectionMode() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "a", archivedAt = 1_000L),
                testRecord(id = "b", archivedAt = 2_000L),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            assertEquals(2, (awaitItem() as ArchiveUiState.Content).records.size)

            viewModel.startSelection("a")
            viewModel.restoreSelected()

            // a 回到收件箱，归档箱里只剩 b
            val state = awaitItem() as ArchiveUiState.Content
            assertEquals(listOf("b"), state.records.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }

        // 恢复是归档的逆操作：archivedAt 必须被清成 null，否则记录会"两边都在"
        val restored = repository.stored.value.first { it.id == "a" }
        assertNull(restored.archivedAt)
        assertFalse(restored.isArchived)
        assertFalse("恢复成功后应退出选择模式", viewModel.selection.value.active)
        assertEquals(ArchiveFeedback.Restored(1), viewModel.feedback.value)
    }

    @Test
    fun ArchiveViewModel_restoreSelected_keepsRecordContentIntact() = runTest {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "a", rawText = "一段不该丢的原文", archivedAt = 1_000L)),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            viewModel.startSelection("a")
            viewModel.restoreSelected()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("一段不该丢的原文", repository.stored.value.single().rawText)
    }

    @Test
    fun ArchiveViewModel_restoreSelected_withoutSelection_doesNothing() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a", archivedAt = 1_000L)))
        val viewModel = viewModel(repository)
        viewModel.enterSelectionMode()

        viewModel.restoreSelected()

        assertTrue(repository.stored.value.single().isArchived)
        assertNull(viewModel.feedback.value)
    }

    @Test
    fun ArchiveViewModel_restoreSelected_repositoryThrows_reportsFailureAndKeepsSelection() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a", archivedAt = 1_000L))).apply {
            archiveFailure = IllegalStateException("disk I/O error")
        }
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            viewModel.startSelection("a")
            viewModel.restoreSelected()

            assertEquals(ArchiveFeedback.RestoreFailed, viewModel.feedback.value)
            assertTrue("失败时保留勾选，用户可直接重试", viewModel.selection.value.active)
            assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ArchiveViewModel_onFeedbackShown_clearsOneShotFeedback() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a", archivedAt = 1_000L)))
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            viewModel.startSelection("a")
            viewModel.restoreSelected()
            assertTrue(viewModel.feedback.value != null)

            viewModel.onFeedbackShown()

            assertNull(viewModel.feedback.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------- 归档箱内搜索（2026-10-24）

    /**
     * `onQueryChange` 会连续触发两次发射：第一次是"新查询词 + 还没切完的旧记录流"，
     * 第二次才是搜索结果。断言必须等到满足条件的那一次，不能假设第一次就是终态。
     */
    private suspend fun ReceiveTurbine<ArchiveUiState>.awaitContent(
        predicate: (ArchiveUiState.Content) -> Boolean,
    ): ArchiveUiState.Content {
        while (true) {
            val state = awaitItem()
            if (state is ArchiveUiState.Content && predicate(state)) return state
        }
    }

    /**
     * 输入搜索词后列表切换成搜索结果；清空后回到全量。
     *
     * 搜索词走仓储而不是内存过滤：归档录音的正文在派生表里，
     * `Record` 上没有 —— 内存过滤对录音永远零命中（详见仓储的 KDoc）。
     */
    @Test
    fun ArchiveViewModel_searchQuery_filtersAndRestoresFullList() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "a1", rawText = "归档的会议记录", archivedAt = 1_000L),
                testRecord(id = "a2", rawText = "购物清单", archivedAt = 2_000L),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val initial = awaitItem() as ArchiveUiState.Content
            assertEquals(2, initial.records.size)
            assertEquals("未搜索时 query 必须是空串", "", initial.query)

            viewModel.onQueryChange("会议")

            val filtered = awaitContent { it.records.map { r -> r.id } == listOf("a1") }
            assertEquals("会议", filtered.query)

            viewModel.onQueryChange("")

            val restored = awaitContent { it.records.size == 2 && it.query.isEmpty() }
            assertEquals(2, restored.records.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 搜索无结果必须是 Content（带 query），**不是** Empty：
     * Empty 的文案是"归档箱是空的"，会让用户以为归档记录全没了。
     */
    @Test
    fun ArchiveViewModel_searchWithNoResults_staysContentWithQuery() = runTest {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "a1", rawText = "会议记录", archivedAt = 1_000L)),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            viewModel.onQueryChange("不存在的词")

            val state = awaitContent { it.records.isEmpty() && it.query.isNotEmpty() }
            assertEquals("不存在的词", state.query)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
