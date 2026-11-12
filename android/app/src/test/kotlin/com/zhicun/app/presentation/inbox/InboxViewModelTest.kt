package com.zhicun.app.presentation.inbox

import app.cash.turbine.test
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.CreateCollectionUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.FavoriteRecordsUseCase
import com.zhicun.app.domain.usecase.GetRecordsUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeInboxListSettings
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import java.time.LocalDate
import java.time.ZoneId
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
 * 收件箱 ViewModel 单元测试（M3 验收：空列表与有数据两种情况；M16：筛选与批量归档）。
 *
 * 走 Robolectric 而不是纯 JVM：ViewModel 的错误分支会调 `android.util.Log`，
 * 纯 JVM 下会抛 `not mocked`（Android 单元测试的默认行为）。
 *
 * `MainDispatcherRule` 用的是 `UnconfinedTestDispatcher`，所以 `viewModelScope.launch`
 * 里的代码在假仓储上会同步跑完 —— 归档这类动作调用后可以直接断言，不需要 `advanceUntilIdle`。
 */
@RunWith(RobolectricTestRunner::class)
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        repository: FakeRecordRepository,
        tags: FakeTagRepository = FakeTagRepository(),
        maintenance: FakeMaintenanceRepository = FakeMaintenanceRepository(),
        settings: FakeInboxListSettings = FakeInboxListSettings(),
        collections: FakeCollectionRepository = FakeCollectionRepository(),
        jobRepository: FakeProcessingJobRepository = FakeProcessingJobRepository(),
    ): InboxViewModel {
        // 软删除要同步给清理侧：两个假实现各有各的存储，不接起来的话
        // 删除用例软删除之后清理看不到任何待清理记录，测试会一路绿灯但什么都没验证。
        repository.onSoftDelete = { ids ->
            ids.forEach { id ->
                maintenance.softDeletedRecords += testRecord(id = id, isDeleted = true)
            }
        }
        val aiSettings = FakeAiSettings()
        val gatewaySettings = FakeAiGatewaySettings()
        return InboxViewModel(
            getRecords = GetRecordsUseCase(repository),
            tagRepository = tags,
            archiveRecords = ArchiveRecordsUseCase(repository),
            favoriteRecords = FavoriteRecordsUseCase(repository),
            deleteRecords = DeleteRecordsUseCase(
                recordRepository = repository,
                collectionRepository = collections,
                cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
                    maintenanceRepository = maintenance,
                    assetRepository = FakeAssetRepository(),
                    fileStore = FakeLocalFileStore(),
                ),
                scheduler = FakeProcessingScheduler(),
            ),
            createCollection = CreateCollectionUseCase(
                recordRepository = repository,
                collectionRepository = collections,
                scheduleProcessing = ScheduleProcessingUseCase(
                    jobRepository = jobRepository,
                    scheduler = FakeProcessingScheduler(),
                    recordRepository = repository,
                    aiSettings = aiSettings,
                    gatewaySettings = gatewaySettings,
                ),
                sensitiveDetector = SensitiveContentDetector(),
                diagnosticLog = FakeDiagnosticLog(),
            ),
            listSettings = settings,
        )
    }

    /** 构造"某天某时"的 epochMillis：用系统时区换算，与生产 DateGroups 的口径一致。 */
    private fun millisAt(date: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ------------------------------------------------------------ M3 基础行为

    @Test
    fun InboxViewModel_beforeSubscribe_keepsLoadingInitialValue() {
        val viewModel = viewModel(FakeRecordRepository())

        // WhileSubscribed 语义：没有订阅者时上游不启动，状态停留在 initialValue
        assertEquals(InboxUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun InboxViewModel_emptyRepository_emitsEmpty() = runTest {
        val viewModel = viewModel(FakeRecordRepository(emptyList()))

        viewModel.uiState.test {
            assertEquals(InboxUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_withRecords_emitsContentInRepositoryOrder() = runTest {
        val newer = testRecord(id = "newer", createdAt = 2_000L)
        val older = testRecord(id = "older", createdAt = 1_000L)
        val viewModel = viewModel(FakeRecordRepository(listOf(newer, older)))

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue("应为 Content，实际 $state", state is InboxUiState.Content)
            // ViewModel 不重排：顺序由 SQL 决定，这里只是原样透传
            assertEquals(listOf("newer", "older"), (state as InboxUiState.Content).records.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_repositoryThrows_emitsError() = runTest {
        val repository = FakeRecordRepository().apply {
            failWith = IllegalStateException("database is locked")
        }
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue("应为 Error，实际 $state", state is InboxUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_recordsAddedLater_emitsUpdatedContent() = runTest {
        val repository = FakeRecordRepository(emptyList())
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            assertEquals(InboxUiState.Empty, awaitItem())

            repository.stored.value = listOf(testRecord(id = "late"))

            val state = awaitItem()
            assertTrue(state is InboxUiState.Content)
            assertEquals(1, (state as InboxUiState.Content).records.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ M16 筛选

    @Test
    fun InboxViewModel_defaultFilter_isAll() = runTest {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "a"))))

        viewModel.uiState.test {
            val state = awaitItem() as InboxUiState.Content
            assertEquals(InboxFilter.ALL, state.filter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_favoriteFilter_hidesNonFavoriteRecords() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "fav", isFavorite = true),
                testRecord(id = "plain"),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            assertEquals(2, (awaitItem() as InboxUiState.Content).records.size)

            viewModel.toggleFilter()

            val filtered = awaitItem() as InboxUiState.Content
            assertEquals(InboxFilter.FAVORITE, filtered.filter)
            assertEquals(listOf("fav"), filtered.records.map { it.id })
            // 总数不受筛选影响：它是"库里到底有没有东西"的依据
            assertEquals(2, filtered.inboxTotal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 筛选把记录全筛掉时**仍然是 Content**。
     *
     * 若返回 Empty，界面会显示"还没有存过东西"并引导用户去分享第一条 —— 但他的库里
     * 明明有 3 条记录。这条用例钉住的就是这个区分。
     */
    @Test
    fun InboxViewModel_favoriteFilterWithNoFavorite_staysContentAndFlagsFilteredEmpty() = runTest {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "a"))))

        viewModel.uiState.test {
            awaitItem()

            viewModel.toggleFilter()

            val state = awaitItem() as InboxUiState.Content
            assertTrue(state.records.isEmpty())
            assertTrue("应标记为筛选后为空", state.filteredToEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_toggleFilterTwice_returnsToAll() = runTest {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "a"))))

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleFilter()
            assertEquals(InboxFilter.FAVORITE, (awaitItem() as InboxUiState.Content).filter)

            viewModel.toggleFilter()
            assertEquals(InboxFilter.ALL, (awaitItem() as InboxUiState.Content).filter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun InboxViewModel_archivedRecords_doNotAppearInInbox() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "kept"),
                testRecord(id = "archived", archivedAt = 5_000L),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            val state = awaitItem() as InboxUiState.Content
            assertEquals(listOf("kept"), state.records.map { it.id })
            // 已归档不算"筛掉"：它是收件箱这个概念之外的东西
            assertEquals(1, state.inboxTotal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ M16 选中

    @Test
    fun InboxViewModel_startSelection_activatesAndSelectsThatRecord() {
        val viewModel = viewModel(FakeRecordRepository())

        assertFalse(viewModel.selection.value.active)
        viewModel.startSelection("a")

        assertTrue(viewModel.selection.value.active)
        assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
        assertTrue(viewModel.selection.value.canAct)
    }

    @Test
    fun InboxViewModel_startSelection_whenAlreadyActive_togglesInsteadOfAdding() {
        val viewModel = viewModel(FakeRecordRepository())
        viewModel.startSelection("a")

        viewModel.startSelection("a")

        assertFalse("再次长按同一条应取消勾选", "a" in viewModel.selection.value.selectedIds)
        // 取消勾选到 0 条时不自动退出选择模式：用户可能只是点错了，留在模式里更方便
        assertTrue(viewModel.selection.value.active)
    }

    @Test
    fun InboxViewModel_enterSelectionMode_selectsNothing() {
        val viewModel = viewModel(FakeRecordRepository())

        viewModel.enterSelectionMode()

        assertTrue(viewModel.selection.value.active)
        assertTrue(viewModel.selection.value.selectedIds.isEmpty())
        assertFalse("0 条时收藏/归档按钮都应禁用", viewModel.selection.value.canAct)
    }

    /**
     * 不在选择模式时 `toggleSelected` 必须无效。
     *
     * 否则普通浏览时点一下卡片就悄悄勾上一条，用户完全看不出来（卡片没高亮），
     * 直到某次误触"归档"才发现丢了几条。
     */
    @Test
    fun InboxViewModel_toggleSelected_whenNotActive_isIgnored() {
        val viewModel = viewModel(FakeRecordRepository())

        viewModel.toggleSelected("a")

        assertTrue(viewModel.selection.value.selectedIds.isEmpty())
        assertFalse(viewModel.selection.value.active)
    }

    @Test
    fun InboxViewModel_clearSelection_resetsSelectionAndMode() {
        val viewModel = viewModel(FakeRecordRepository())
        viewModel.startSelection("a")

        viewModel.clearSelection()

        assertFalse(viewModel.selection.value.active)
        assertTrue(viewModel.selection.value.selectedIds.isEmpty())
    }

    @Test
    fun InboxViewModel_toggleFilter_clearsSelection() = runTest {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "a"))))
        viewModel.startSelection("a")

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleFilter()
            awaitItem()

            // 切换筛选后旧勾选可能已经不可见，留着会导致"选了 3 条却只归档了 1 条"
            assertFalse(viewModel.selection.value.active)
            assertTrue(viewModel.selection.value.selectedIds.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ M16 批量归档

    @Test
    fun InboxViewModel_archiveSelected_movesRecordsOutOfInboxAndExitsSelectionMode() = runTest {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "a"), testRecord(id = "b"), testRecord(id = "c")),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            assertEquals(3, (awaitItem() as InboxUiState.Content).records.size)

            viewModel.startSelection("a")
            viewModel.toggleSelected("b")
            viewModel.archiveSelected()

            // 记录从收件箱消失
            val state = awaitItem() as InboxUiState.Content
            assertEquals(listOf("c"), state.records.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, repository.stored.value.count { it.archivedAt != null })
        assertFalse("归档成功后应退出选择模式", viewModel.selection.value.active)
        assertTrue(viewModel.selection.value.selectedIds.isEmpty())
        assertEquals(InboxFeedback.Archived(2), viewModel.feedback.value)
    }

    @Test
    fun InboxViewModel_archiveSelected_archivedRecordsStillExistInRepository() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a", rawText = "原始内容")))
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.archiveSelected()

        // 归档不是删除：原文、附件都还在；只是退出搜索与相关推荐（2026-10-24 裁决）
        val archived = repository.stored.value.single()
        assertEquals("原始内容", archived.rawText)
        assertFalse(archived.isDeleted)
        assertTrue(archived.isArchived)
    }

    @Test
    fun InboxViewModel_archiveSelected_withoutSelection_doesNothing() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a")))
        val viewModel = viewModel(repository)
        viewModel.enterSelectionMode()

        viewModel.archiveSelected()

        assertEquals(0, repository.stored.value.count { it.archivedAt != null })
        assertNull(viewModel.feedback.value)
    }

    @Test
    fun InboxViewModel_archiveSelected_repositoryThrows_reportsFailureAndKeepsSelection() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a"))).apply {
            archiveFailure = IllegalStateException("disk I/O error")
        }
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.archiveSelected()

        assertEquals(InboxFeedback.ArchiveFailed, viewModel.feedback.value)
        // 失败时保留勾选：用户可以直接再点一次，不必重新选一遍
        assertTrue(viewModel.selection.value.active)
        assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
    }

    // ------------------------------------------------------------ M16 批量收藏（2026-10-24）

    @Test
    fun InboxViewModel_favoriteSelected_marksSelectedAndStaysInSelectionMode() {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "a"), testRecord(id = "b"), testRecord(id = "c")),
        )
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")
        viewModel.toggleSelected("b")

        viewModel.favoriteSelected()

        assertEquals(
            setOf("a", "b"),
            repository.stored.value.filter { it.isFavorite }.map { it.id }.toSet(),
        )
        assertFalse("没勾选的那条不能被改", repository.stored.value.single { it.id == "c" }.isFavorite)
        assertEquals(InboxFeedback.Favorited(2), viewModel.feedback.value)
        // 与归档的关键差异：收藏不改变列表内容，用户往往接着要点归档，
        // 此时退出选择模式等于逼他重新勾一遍
        assertTrue("收藏后不应退出选择模式", viewModel.selection.value.active)
        assertEquals(setOf("a", "b"), viewModel.selection.value.selectedIds)
    }

    /**
     * 连点两次结果必须相同。
     *
     * 用例刻意做成"设成目标值"而不是"切换"：切换语义下，用户连点两下会把刚打上的星
     * 又取消掉，而他看到的是按钮和提示都正常响应的两次点击。
     */
    @Test
    fun InboxViewModel_favoriteSelected_calledTwice_keepsFavorite() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a")))
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.favoriteSelected()
        viewModel.favoriteSelected()

        assertTrue(repository.stored.value.single().isFavorite)
        assertEquals(InboxFeedback.Favorited(1), viewModel.feedback.value)
    }

    /**
     * 收藏筛选视图的"整理"必须是**取消收藏**（2026-10-24 用户反馈）：
     * 用户正看着收藏列表，此时动作是"移出收藏"。同样不退出选择模式 ——
     * 记录还在列表里，只是没了收藏标记。
     */
    @Test
    fun InboxViewModel_unfavoriteSelected_unmarksSelectedAndStaysInSelectionMode() {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "a", isFavorite = true),
                testRecord(id = "b", isFavorite = true),
                testRecord(id = "c", isFavorite = false),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")
        viewModel.toggleSelected("b")

        viewModel.unfavoriteSelected()

        assertEquals(
            setOf<String>(),
            repository.stored.value.filter { it.isFavorite }.map { it.id }.toSet(),
        )
        assertFalse(repository.stored.value.single { it.id == "c" }.isFavorite)
        assertEquals(InboxFeedback.Unfavorited(2), viewModel.feedback.value)
        assertTrue(viewModel.selection.value.active)
        assertEquals(setOf("a", "b"), viewModel.selection.value.selectedIds)
    }

    @Test
    fun InboxViewModel_favoriteSelected_withoutSelection_doesNothing() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a")))
        val viewModel = viewModel(repository)
        viewModel.enterSelectionMode()

        viewModel.favoriteSelected()

        assertEquals(0, repository.stored.value.count { it.isFavorite })
        assertNull(viewModel.feedback.value)
    }

    @Test
    fun InboxViewModel_favoriteSelected_repositoryThrows_reportsFailureAndKeepsSelection() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a"))).apply {
            favoriteFailure = IllegalStateException("disk I/O error")
        }
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.favoriteSelected()

        assertEquals(InboxFeedback.FavoriteFailed, viewModel.feedback.value)
        assertTrue("失败时保留勾选，用户可以直接再点一次", viewModel.selection.value.active)
        assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
    }

    @Test
    fun InboxViewModel_onFeedbackShown_clearsOneShotFeedback() {
        val viewModel = viewModel(FakeRecordRepository(listOf(testRecord(id = "a"))))
        viewModel.startSelection("a")
        viewModel.archiveSelected()
        assertTrue(viewModel.feedback.value != null)

        viewModel.onFeedbackShown()

        // 不清掉的话，旋转屏幕后 ViewModel 里的旧值会被重新展示一遍
        assertNull(viewModel.feedback.value)
    }

    // ------------------------------------------------------------ 批量删除（2026-10-24）

    @Test
    fun InboxViewModel_deleteSelected_removesRecordsAndReportsCount() = runTest {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "a"), testRecord(id = "b"), testRecord(id = "c")),
        )
        val viewModel = viewModel(repository)

        viewModel.uiState.test {
            assertEquals(3, (awaitItem() as InboxUiState.Content).records.size)

            viewModel.startSelection("a")
            viewModel.toggleSelected("b")
            viewModel.deleteSelected()

            // 删除是"从列表里彻底消失"，不只是换一个筛选条件
            val state = awaitItem() as InboxUiState.Content
            assertEquals(listOf("c"), state.records.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            "删除必须真的清掉数据，不是只置个界面标记",
            listOf("c"),
            repository.stored.value.map { it.id },
        )
        assertEquals(InboxFeedback.Deleted(count = 2, incomplete = 0), viewModel.feedback.value)
        assertFalse("删除成功后应退出选择模式", viewModel.selection.value.active)
    }

    /**
     * 删除是终态动作：即使有文件删不掉（附件被占用），记录也已经从列表消失了，
     * 继续留在选择模式里只会让用户对着一个空壳继续点。
     */
    @Test
    fun InboxViewModel_deleteSelected_partialCleanup_stillExitsSelection() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a")))
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.deleteSelected()

        assertFalse(viewModel.selection.value.active)
        val feedback = viewModel.feedback.value as InboxFeedback.Deleted
        assertEquals(1, feedback.count)
        assertEquals(0, feedback.incomplete)
    }

    @Test
    fun InboxViewModel_deleteSelected_withoutSelection_doesNothing() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a")))
        val viewModel = viewModel(repository)
        viewModel.enterSelectionMode()

        viewModel.deleteSelected()

        assertEquals(1, repository.stored.value.size)
        assertNull(viewModel.feedback.value)
    }

    @Test
    fun InboxViewModel_deleteSelected_repositoryThrows_reportsFailureAndKeepsSelection() {
        val repository = FakeRecordRepository(listOf(testRecord(id = "a"))).apply {
            deleteFailure = IllegalStateException("disk I/O error")
        }
        val viewModel = viewModel(repository)
        viewModel.startSelection("a")

        viewModel.deleteSelected()

        assertEquals(InboxFeedback.DeleteFailed, viewModel.feedback.value)
        // 删除失败时记录还在，保留勾选让用户直接重试
        assertTrue(viewModel.selection.value.active)
        assertEquals(setOf("a"), viewModel.selection.value.selectedIds)
        assertEquals(1, repository.stored.value.size)
    }

    // ------------------------------------------------------------ M19：按天分组折叠 + 日期筛选

    /**
     * 分组骨架：置顶组在最前（即使置顶记录的日期更晚），其余按天倒序；
     * 同一天的记录跟在自己的组头后面，顺序仍由 SQL（created_at 倒序）保证。
     */
    @Test
    fun InboxViewModel_grouping_pinnedFirstThenDaysDescending() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 23)
        val day2 = LocalDate.of(2026, 10, 25)
        // SQL 序：置顶优先、再按 created_at 倒序
        val records = listOf(
            testRecord(id = "pinned", createdAt = millisAt(day1), isPinned = true),
            testRecord(id = "d2-late", createdAt = millisAt(day2, 18, 0)),
            testRecord(id = "d1-ten", createdAt = millisAt(day1, 10, 0)),
            testRecord(id = "d1-eight", createdAt = millisAt(day1, 8, 0)),
            testRecord(id = "d0", createdAt = millisAt(day0)),
        )
        val viewModel = viewModel(FakeRecordRepository(records))

        viewModel.uiState.test {
            val content = awaitItem() as InboxUiState.Content
            assertEquals(
                listOf(
                    InboxListItem.PinnedHeader(count = 1),
                    InboxListItem.RecordEntry(records[0]),
                    InboxListItem.DayHeader(day2, count = 1, collapsed = false),
                    InboxListItem.RecordEntry(records[1]),
                    InboxListItem.DayHeader(day1, count = 2, collapsed = false),
                    InboxListItem.RecordEntry(records[2]),
                    InboxListItem.RecordEntry(records[3]),
                    InboxListItem.DayHeader(day0, count = 1, collapsed = false),
                    InboxListItem.RecordEntry(records[4]),
                ),
                content.listItems,
            )
            // 置顶记录被抽走后，那天的组头只数非置顶的记录（d1 剩 2 条）
            assertEquals(mapOf(day0 to 1, day1 to 3, day2 to 1), content.dayCounts)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 跨日边界：本地时区 23:59:59 与次日 00:00 分属两个组。 */
    @Test
    fun InboxViewModel_grouping_acrossMidnightSplitsGroups() = runTest {
        val lateNight = LocalDate.of(2026, 10, 23)
        val earlyMorning = LocalDate.of(2026, 10, 24)
        val records = listOf(
            testRecord(id = "before", createdAt = millisAt(lateNight, 23, 59)),
            testRecord(id = "after", createdAt = millisAt(earlyMorning, 0, 0)),
        )
        val viewModel = viewModel(FakeRecordRepository(records))

        viewModel.uiState.test {
            val content = awaitItem() as InboxUiState.Content
            assertEquals(
                2,
                content.listItems.count { it is InboxListItem.DayHeader },
            )
            assertEquals(
                setOf(lateNight, earlyMorning),
                content.dayCounts.keys,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 折叠某天：该天只剩组头，记录不渲染；再点一次展开。状态同时落进偏好。 */
    @Test
    fun InboxViewModel_toggleDayCollapsed_hidesRecordsAndPersists() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 23)
        val records = listOf(
            testRecord(id = "d1", createdAt = millisAt(day1)),
            testRecord(id = "d0", createdAt = millisAt(day0)),
        )
        val settings = FakeInboxListSettings()
        val viewModel = viewModel(FakeRecordRepository(records), settings = settings)

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleDayCollapsed(day1)

            val collapsed = awaitItem() as InboxUiState.Content
            assertEquals(setOf(day1), collapsed.collapsedDays)
            assertEquals(
                "折叠后整列只剩各组头与未折叠天的记录",
                listOf(
                    InboxListItem.DayHeader(day1, count = 1, collapsed = true),
                    InboxListItem.DayHeader(day0, count = 1, collapsed = false),
                    InboxListItem.RecordEntry(records[1]),
                ),
                collapsed.listItems,
            )
            assertEquals(setOf(day1), settings.saved)

            viewModel.toggleDayCollapsed(day1)
            val expanded = awaitItem() as InboxUiState.Content
            assertTrue(expanded.collapsedDays.isEmpty())
            assertTrue(settings.saved.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 全部折叠 / 全部展开：作用域是"当前有记录的每一天"，展开后偏好清空。 */
    @Test
    fun InboxViewModel_collapseAndExpandAll_daysWithRecords() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 22)
        val records = listOf(
            testRecord(id = "d1", createdAt = millisAt(day1)),
            testRecord(id = "d0", createdAt = millisAt(day0)),
        )
        val settings = FakeInboxListSettings()
        val viewModel = viewModel(FakeRecordRepository(records), settings = settings)

        viewModel.uiState.test {
            awaitItem()
            viewModel.collapseAllDays()

            val collapsed = awaitItem() as InboxUiState.Content
            assertEquals(setOf(day0, day1), collapsed.collapsedDays)
            assertTrue(collapsed.listItems.all { it !is InboxListItem.RecordEntry })

            viewModel.expandAllDays()
            val expanded = awaitItem() as InboxUiState.Content
            assertTrue(expanded.collapsedDays.isEmpty())
            assertEquals(2, expanded.listItems.count { it is InboxListItem.RecordEntry })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 折叠没有记录的日期是无效操作：不改变状态也不写偏好。 */
    @Test
    fun InboxViewModel_toggleDayCollapsed_unknownDayIgnored() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val settings = FakeInboxListSettings()
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "d1", createdAt = millisAt(day1)))),
            settings = settings,
        )

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleDayCollapsed(LocalDate.of(2020, 1, 1))

            // 忽略意味着 uiState 不重发射（StateFlow 去重，即使重算也停在同一值）
            expectNoEvents()
            assertTrue(settings.saved.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 日期筛选：只显示该天，且**自动展开**（折叠状态对该天失效）、置顶组不出现；
     * 清除后恢复全量与原有折叠状态。
     */
    @Test
    fun InboxViewModel_selectDate_filtersAndAutoExpands() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 23)
        val records = listOf(
            testRecord(id = "pinned", createdAt = millisAt(day0), isPinned = true),
            testRecord(id = "d1-a", createdAt = millisAt(day1, 10, 0)),
            testRecord(id = "d1-b", createdAt = millisAt(day1, 8, 0)),
            testRecord(id = "d0", createdAt = millisAt(day0)),
        )
        val viewModel = viewModel(FakeRecordRepository(records))

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleDayCollapsed(day1)
            awaitItem()
            viewModel.selectDate(day1)

            val filtered = awaitItem() as InboxUiState.Content
            assertEquals(day1, filtered.selectedDate)
            assertEquals(listOf("d1-a", "d1-b"), filtered.records.map { it.id })
            assertEquals(
                "日期视图里没有置顶组，该天的记录（含置顶的）都在组头下",
                listOf(
                    InboxListItem.DayHeader(day1, count = 2, collapsed = false),
                    InboxListItem.RecordEntry(records[1]),
                    InboxListItem.RecordEntry(records[2]),
                ),
                filtered.listItems,
            )

            viewModel.selectDate(null)
            val restored = awaitItem() as InboxUiState.Content
            assertEquals(4, restored.records.size)
            assertEquals(setOf(day1), restored.collapsedDays)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 选到没有记录（或该条件下没记录）的一天：列表空但仍为 Content，交给日期版空态引导。 */
    @Test
    fun InboxViewModel_selectDateWithoutRecords_filteredToEmpty() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "d1", createdAt = millisAt(day1)))),
        )

        viewModel.uiState.test {
            awaitItem()
            viewModel.selectDate(LocalDate.of(2026, 10, 20))

            val filtered = awaitItem() as InboxUiState.Content
            assertTrue(filtered.records.isEmpty())
            assertEquals(1, filtered.inboxTotal)
            assertTrue(filtered.filteredToEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 日期筛选期间点组头折叠：必须忽略，否则点了没有任何可见变化。 */
    @Test
    fun InboxViewModel_toggleDayCollapsed_whileDateSelectedIgnored() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val settings = FakeInboxListSettings()
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "d1", createdAt = millisAt(day1)))),
            settings = settings,
        )

        viewModel.uiState.test {
            awaitItem()
            viewModel.selectDate(day1)
            awaitItem()
            viewModel.toggleDayCollapsed(day1)

            // 忽略意味着 uiState 不重发射：等一小段确认没有新事件
            expectNoEvents()
            assertTrue(settings.saved.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 日历圆点的口径：收藏筛选参与（圆点只标"这个条件下有的天"），
     * 日期筛选**不**参与（圆点要能导航到还没在看的那些天）。
     */
    @Test
    fun InboxViewModel_dayCounts_favoriteFilterAppliesDateFilterDoesNot() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 23)
        val records = listOf(
            testRecord(id = "fav", createdAt = millisAt(day1), isFavorite = true),
            testRecord(id = "plain", createdAt = millisAt(day0)),
        )
        val viewModel = viewModel(FakeRecordRepository(records))

        viewModel.uiState.test {
            awaitItem()
            viewModel.toggleFilter() // → FAVORITE
            val favoriteOnly = awaitItem() as InboxUiState.Content
            assertEquals(mapOf(day1 to 1), favoriteOnly.dayCounts)

            viewModel.selectDate(day0) // 收藏条件下 day0 没有记录
            val filtered = awaitItem() as InboxUiState.Content
            assertEquals(
                "日期筛选不影响圆点",
                mapOf(day1 to 1),
                filtered.dayCounts,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 折叠状态从持久化偏好恢复：新构造的 ViewModel 直接带着旧折叠集。 */
    @Test
    fun InboxViewModel_collapsedDaysRestoredFromSettings() = runTest {
        val day1 = LocalDate.of(2026, 10, 24)
        val settings = FakeInboxListSettings().apply { saved = setOf(day1) }
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "d1", createdAt = millisAt(day1)))),
            settings = settings,
        )

        viewModel.uiState.test {
            val content = awaitItem() as InboxUiState.Content
            assertEquals(setOf(day1), content.collapsedDays)
            assertTrue(
                content.listItems.none { it is InboxListItem.RecordEntry && it.record.id == "d1" },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------ M20 合并为合集

    @Test
    fun InboxViewModel_createCollectionSelected_createsAndReportsCreated() = runTest {
        val collections = FakeCollectionRepository()
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "b", createdAt = 2_000L),
                testRecord(id = "a", createdAt = 1_000L),
            ),
        )
        val viewModel = viewModel(repository, collections = collections)
        viewModel.startSelection("b")
        viewModel.toggleSelected("a")

        viewModel.createCollectionSelected()

        assertEquals(1, collections.creations.size)
        // 用例按 createdAt 升序落 position，与勾选顺序无关
        assertEquals(listOf("a", "b"), collections.creations.single().memberRecordIds)
        val feedback = viewModel.feedback.value
        assertTrue("应报告创建成功，实际 $feedback", feedback is InboxFeedback.CollectionCreated)
        assertEquals(2, (feedback as InboxFeedback.CollectionCreated).memberCount)
        assertFalse("创建成功后应退出选择模式", viewModel.selection.value.active)
    }

    @Test
    fun InboxViewModel_createCollectionSelected_rejected_keepsSelectionForRetry() = runTest {
        val collections = FakeCollectionRepository()
        val collectionRecord = testRecord(id = "c", type = com.zhicun.app.domain.model.RecordType.COLLECTION)
        val repository = FakeRecordRepository(listOf(collectionRecord, testRecord(id = "a")))
        val viewModel = viewModel(repository, collections = collections)
        viewModel.startSelection("c")
        viewModel.toggleSelected("a")

        viewModel.createCollectionSelected()

        assertTrue("合集不能嵌套，不该写库", collections.creations.isEmpty())
        assertEquals(
            InboxFeedback.CollectionRejected(
                com.zhicun.app.domain.usecase.CreateCollectionResult.Reason.NESTED_COLLECTION,
            ),
            viewModel.feedback.value,
        )
        assertTrue("被拒绝时保留勾选，用户改选后可直接再点", viewModel.selection.value.active)
    }

    @Test
    fun InboxViewModel_createCollectionSelected_singleRecord_doesNothing() = runTest {
        val collections = FakeCollectionRepository()
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "a"))),
            collections = collections,
        )
        viewModel.startSelection("a")

        viewModel.createCollectionSelected()

        assertTrue(collections.creations.isEmpty())
        assertNull(viewModel.feedback.value)
    }

    @Test
    fun InboxViewModel_createCollectionSelected_repositoryThrows_reportsFailure() = runTest {
        val collections = FakeCollectionRepository().apply {
            createFailure = IllegalStateException("disk full")
        }
        val viewModel = viewModel(
            FakeRecordRepository(listOf(testRecord(id = "a"), testRecord(id = "b"))),
            collections = collections,
        )
        viewModel.startSelection("a")
        viewModel.toggleSelected("b")

        viewModel.createCollectionSelected()

        assertEquals(InboxFeedback.CollectionCreateFailed, viewModel.feedback.value)
    }
}
