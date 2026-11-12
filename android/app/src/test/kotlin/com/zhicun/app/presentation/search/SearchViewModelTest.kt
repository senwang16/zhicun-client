package com.zhicun.app.presentation.search

import com.zhicun.app.R
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.domain.search.ScoredRecordId
import com.zhicun.app.domain.search.SearchQuery
import com.zhicun.app.domain.usecase.BuildResultTagIndexUseCase
import com.zhicun.app.domain.usecase.ClearSearchHistoryUseCase
import com.zhicun.app.domain.usecase.DeleteSearchHistoryUseCase
import com.zhicun.app.domain.usecase.ObserveSearchHistoryUseCase
import com.zhicun.app.domain.usecase.ObserveTagUsageUseCase
import com.zhicun.app.domain.usecase.RecordSearchHistoryUseCase
import com.zhicun.app.domain.usecase.SearchRecordsUseCase
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeEmbedder
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeSearchHistoryRepository
import com.zhicun.app.fake.FakeSearchRepository
import com.zhicun.app.fake.FakeSemanticIndexRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 搜索页 ViewModel（M10）。
 *
 * 重点在**防抖与取消**：输入停顿 [300ms] 内不该查库；连续输入只查最后一次；
 * 三种结果（有命中 / 无命中 / 异常）落到互斥状态上。
 */
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val searchRepository = FakeSearchRepository()
    private val recordRepository = FakeRecordRepository(
        listOf(
            testRecord(id = "r1", rawText = "断联陪伴"),
            testRecord(id = "r2", rawText = "陪伴 复盘"),
        ),
    )

    // 默认让语义路"不可用"（本地还没有向量）：本类的被测对象是防抖与状态流转，
    // 语义参与后的排序由 SearchRecordsUseCaseTest / HybridRankerTest 覆盖。
    private val embedder = FakeEmbedder()
    private val semanticIndex = FakeSemanticIndexRepository()

    private val tagRepository = FakeTagRepository()
    private val historyRepository = FakeSearchHistoryRepository()

    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        viewModel = SearchViewModel(
            searchRecords = SearchRecordsUseCase(
                searchRepository = searchRepository,
                recordRepository = recordRepository,
                embedder = embedder,
                semanticIndex = semanticIndex,
                derivedContentRepository = FakeDerivedContentRepository(),
                tagRepository = tagRepository,
                aiSettings = FakeAiSettings(),
                gatewaySettings = FakeAiGatewaySettings(),
            ),
            recordSearchHistory = RecordSearchHistoryUseCase(historyRepository),
            observeSearchHistory = ObserveSearchHistoryUseCase(historyRepository),
            observeTagUsage = ObserveTagUsageUseCase(tagRepository),
            deleteSearchHistory = DeleteSearchHistoryUseCase(historyRepository),
            clearSearchHistory = ClearSearchHistoryUseCase(historyRepository),
            buildResultTagIndex = BuildResultTagIndexUseCase(tagRepository),
        )
    }

    @Test
    fun InitialState_isIdle() {
        assertEquals(SearchUiState.Idle, viewModel.uiState.value)
        assertEquals("", viewModel.query.value)
    }

    @Test
    fun OnQueryChange_doesNotQueryBeforeDebounce() = runTest {
        viewModel.onQueryChange("陪伴")

        // 立刻进入 Searching：输入框有词、结果还没到，界面不该闪"无结果"
        assertEquals(SearchUiState.Searching("陪伴"), viewModel.uiState.value)
        assertTrue("防抖期内不该查库", searchRepository.calls.isEmpty())

        advanceTimeBy(100L)
        assertTrue(searchRepository.calls.isEmpty())
    }

    @Test
    fun OnQueryChange_afterDebounce_showsResults() = runTest {
        searchRepository.resultIds["陪伴 复盘"] = listOf("r2")

        viewModel.onQueryChange("陪伴 复盘")
        advanceTimeBy(300L)

        val state = viewModel.uiState.value as SearchUiState.Results
        assertEquals("陪伴 复盘", state.keyword)
        assertEquals(listOf("r2"), state.records.map { it.id })
        assertTrue("本地没有向量时，结果只有关键词一路，必须标记为降级", state.keywordOnly)
    }

    @Test
    fun OnQueryChange_noHits_showsEmptyState() = runTest {
        searchRepository.resultIds["不存在"] = emptyList()

        viewModel.onQueryChange("不存在")
        advanceUntilIdle()

        assertEquals(SearchUiState.Empty("不存在"), viewModel.uiState.value)
    }

    @Test
    fun OnQueryChange_storageFailure_showsError() = runTest {
        searchRepository.failure = IllegalStateException("db locked")

        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        assertEquals(SearchUiState.Error(R.string.search_error), viewModel.uiState.value)
    }

    @Test
    fun TypingContinuously_onlySearchesLastQuery() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")
        searchRepository.resultIds["陪伴 复盘"] = listOf("r2")

        viewModel.onQueryChange("陪伴")
        advanceTimeBy(100L)
        viewModel.onQueryChange("陪伴 复盘")
        advanceUntilIdle()

        assertEquals("只该查最后一次输入", listOf("陪伴 复盘"), searchRepository.calls.map { it.first })
        assertEquals(listOf("r2"), (viewModel.uiState.value as SearchUiState.Results).records.map { it.id })
    }

    @Test
    fun OnClearQuery_returnsToIdleWithoutQuerying() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")
        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        viewModel.onClearQuery()
        advanceUntilIdle()

        assertEquals(SearchUiState.Idle, viewModel.uiState.value)
        assertEquals("", viewModel.query.value)
        assertEquals("清空不该再查一次库", 1, searchRepository.calls.size)
    }

    @Test
    fun OnQueryChange_blankAfterNonBlank_returnsToIdle() = runTest {
        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        viewModel.onQueryChange("   ")

        assertEquals(SearchUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun OnQueryChange_capsLength() = runTest {
        viewModel.onQueryChange("a".repeat(SearchQuery.MAX_LENGTH + 20))

        assertEquals(SearchQuery.MAX_LENGTH, viewModel.query.value.length)
    }

    // ==== 两段式检索（2026-10-24 搜索卡顿整改）：关键词先行、语义后到刷新 ====

    @Test
    fun OnQueryChange_keywordResultsArriveBeforeSemanticCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        embedder.gate = gate
        searchRepository.resultIds["陪伴"] = listOf("r1")

        viewModel.onQueryChange("陪伴")
        advanceTimeBy(300L)

        // 语义还在网络往返中，关键词结果已上屏（此前这里会一直转圈）
        val pending = viewModel.uiState.value as SearchUiState.Results
        assertEquals(listOf("r1"), pending.records.map { it.id })
        assertTrue("语义在路上不算降级", pending.semanticPending)
        assertFalse(pending.keywordOnly)

        gate.complete(Unit)
        advanceUntilIdle()

        val final = viewModel.uiState.value as SearchUiState.Results
        assertFalse(final.semanticPending)
    }

    @Test
    fun OnQueryChange_semanticDegradeFinal_marksKeywordOnlyWithoutPending() = runTest {
        // 默认 fake：云端可用但本地没有向量 → 终态降级为仅关键词
        searchRepository.resultIds["陪伴"] = listOf("r1")

        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        val final = viewModel.uiState.value as SearchUiState.Results
        assertTrue("索引为空时终态如实标注仅关键词", final.keywordOnly)
        assertFalse(final.semanticPending)
    }

    @Test
    fun OnQueryChange_stage1Empty_showsEmptyThenSemanticFillsResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        embedder.gate = gate
        searchRepository.resultIds["不存在"] = emptyList()
        semanticIndex.hits = listOf(ScoredRecordId("r1", 0.9))
        semanticIndex.comparedCount = 1

        viewModel.onQueryChange("不存在")
        advanceTimeBy(300L)

        // 第一段为空也照发：先说"没找到"，别跟着网络转圈
        assertEquals(SearchUiState.Empty("不存在"), viewModel.uiState.value)

        gate.complete(Unit)
        advanceUntilIdle()

        val final = viewModel.uiState.value as SearchUiState.Results
        assertEquals("语义命中把空态翻转为结果", listOf("r1"), final.records.map { it.id })
    }

    @Test
    fun TypingContinuously_pendingResultsReplacedByNewestQuery() = runTest {
        val gate = CompletableDeferred<Unit>()
        embedder.gate = gate
        searchRepository.resultIds["陪伴"] = listOf("r1")
        searchRepository.resultIds["陪伴 复盘"] = listOf("r2")

        viewModel.onQueryChange("陪伴")
        advanceTimeBy(300L)
        assertEquals(listOf("r1"), (viewModel.uiState.value as SearchUiState.Results).records.map { it.id })

        viewModel.onQueryChange("陪伴 复盘")
        advanceTimeBy(300L)
        assertEquals("新查询的关键词结果立刻替换旧查询", listOf("r2"), (viewModel.uiState.value as SearchUiState.Results).records.map { it.id })

        gate.complete(Unit)
        advanceUntilIdle()

        val final = viewModel.uiState.value as SearchUiState.Results
        assertEquals("旧查询的语义结果不得覆盖新查询", listOf("r2"), final.records.map { it.id })
        assertFalse(final.semanticPending)
    }

    // ==== 搜索历史与标签 chip（M21）====

    @Test
    fun TypingWithoutAction_doesNotWriteHistory() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")

        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        assertTrue("边打边搜不该产生历史（否则会写满半截词）", historyRepository.recorded.isEmpty())
    }

    @Test
    fun OnSearchSubmitted_recordsCurrentQuery() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")
        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()

        viewModel.onSearchSubmitted()
        advanceUntilIdle()

        assertEquals(listOf("陪伴"), historyRepository.recorded)
        assertEquals("陪伴", historyRepository.observeRecent(10).first().single().query)
    }

    @Test
    fun OnResultOpened_recordsCurrentQuery() = runTest {
        searchRepository.resultIds["陪伴 复盘"] = listOf("r1")
        viewModel.onQueryChange("陪伴 复盘")
        advanceUntilIdle()

        viewModel.onResultOpened()
        advanceUntilIdle()

        assertEquals(listOf("陪伴 复盘"), historyRepository.recorded)
    }

    @Test
    fun OnSearchSubmitted_withBlankQuery_doesNotRecord() = runTest {
        viewModel.onSearchSubmitted()
        advanceUntilIdle()

        assertTrue(historyRepository.recorded.isEmpty())
    }

    @Test
    fun OnHistorySelected_refillsQueryRunsSearchAndRecords() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")

        viewModel.onHistorySelected(SearchHistoryEntry(queryKey = "陪伴", query = "陪伴", searchedAt = 1L))
        advanceUntilIdle()

        assertEquals("陪伴", viewModel.query.value)
        assertEquals(listOf("r1"), (viewModel.uiState.value as SearchUiState.Results).records.map { it.id })
        assertEquals(listOf("陪伴"), historyRepository.recorded)
    }

    @Test
    fun OnTagSelected_searchesTagNameAndRecords() = runTest {
        searchRepository.resultIds["工作"] = listOf("r1")

        viewModel.onTagSelected("工作")
        advanceUntilIdle()

        assertEquals("工作", viewModel.query.value)
        assertEquals(listOf("工作"), historyRepository.recorded)
    }

    @Test
    fun OnDeleteHistory_removesOnlyThatEntry() = runTest {
        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()
        viewModel.onSearchSubmitted()
        viewModel.onQueryChange("工作")
        advanceUntilIdle()
        viewModel.onSearchSubmitted()
        advanceUntilIdle()

        val entries = historyRepository.observeRecent(10).first()
        assertEquals(2, entries.size)

        viewModel.onDeleteHistory(entries.first { it.query == "陪伴" })
        advanceUntilIdle()

        assertEquals(listOf("工作"), historyRepository.observeRecent(10).first().map { it.query })
    }

    @Test
    fun OnClearHistory_removesAll() = runTest {
        viewModel.onQueryChange("陪伴")
        advanceUntilIdle()
        viewModel.onSearchSubmitted()
        advanceUntilIdle()

        viewModel.onClearHistory()
        advanceUntilIdle()

        assertTrue(historyRepository.observeRecent(10).first().isEmpty())
    }

    // ==== 结果页结果标签与结果内收窄（M21 决策 3）====

    private fun tagged(recordId: String, name: String) = RecordTag(
        recordId = recordId,
        tag = Tag(
            id = "ai-$name",
            name = name,
            source = TagSource.AI,
            confidence = null,
            createdAt = 1L,
        ),
    )

    private suspend fun TestScope.renderQuery(query: String, recordIds: List<String>): SearchUiState.Results {
        searchRepository.resultIds[query] = recordIds
        viewModel.onQueryChange(query)
        advanceUntilIdle()
        return viewModel.uiState.value as SearchUiState.Results
    }

    @Test
    fun ResultsWithEnoughTags_exposesTagsOrderedByCount() = runTest {
        tagRepository.associations.value = listOf(
            tagged("r1", "工作"),
            tagged("r2", "工作"),
            tagged("r1", "会议"),
        )

        val state = renderQuery("陪伴", listOf("r1", "r2"))

        assertEquals(
            listOf("工作" to 2, "会议" to 1),
            state.resultTags.map { it.name to it.recordCount },
        )
    }

    @Test
    fun SingleResult_hasNoResultTags() = runTest {
        tagRepository.associations.value = listOf(tagged("r1", "工作"), tagged("r1", "会议"))

        val state = renderQuery("陪伴", listOf("r1"))

        assertTrue("只有一条结果时不显示结果标签", state.resultTags.isEmpty())
    }

    @Test
    fun OnResultTagSelected_narrowsThenTogglesBack() = runTest {
        tagRepository.associations.value = listOf(tagged("r1", "工作"), tagged("r2", "会议"))
        val full = renderQuery("陪伴", listOf("r1", "r2"))
        assertEquals(listOf("r1", "r2"), full.records.map { it.id })

        viewModel.onResultTagSelected("工作")
        val narrowed = viewModel.uiState.value as SearchUiState.Results
        assertEquals("工作", narrowed.narrowedTag)
        assertEquals("收窄只保留带该标签的记录", listOf("r1"), narrowed.records.map { it.id })

        viewModel.onResultTagSelected("工作")
        val restored = viewModel.uiState.value as SearchUiState.Results
        assertNull("再点同一个标签应恢复完整结果", restored.narrowedTag)
        assertEquals(listOf("r1", "r2"), restored.records.map { it.id })
    }

    @Test
    fun QueryChange_clearsResultTagNarrowing() = runTest {
        tagRepository.associations.value = listOf(tagged("r1", "工作"), tagged("r2", "工作"))
        renderQuery("陪伴", listOf("r1", "r2"))
        viewModel.onResultTagSelected("工作")
        assertEquals("工作", (viewModel.uiState.value as SearchUiState.Results).narrowedTag)

        val next = renderQuery("复盘", listOf("r1", "r2"))

        assertNull("换词后不能带着旧标签过滤新结果", next.narrowedTag)
    }

    @Test
    fun SemanticStageDroppingTag_clearsNarrowingAndShowsNotice() = runTest {
        val gate = CompletableDeferred<Unit>()
        embedder.gate = gate
        tagRepository.associations.value = listOf(tagged("r1", "工作"), tagged("r2", "工作"))
        searchRepository.resultIds["陪伴"] = listOf("r1", "r2")

        viewModel.onQueryChange("陪伴")
        advanceTimeBy(300L) // 第一段（关键词）已上屏，语义还在路上
        viewModel.onResultTagSelected("工作")
        assertEquals("工作", (viewModel.uiState.value as SearchUiState.Results).narrowedTag)

        // 语义段到达前标签数据消失（例如标签被重新生成/记录被归档）
        tagRepository.associations.value = emptyList()
        gate.complete(Unit)
        advanceTimeBy(500L)

        val final = viewModel.uiState.value as SearchUiState.Results
        assertNull("标签没了就必须取消收窄，不能留下空列表", final.narrowedTag)
        assertEquals("工作", final.narrowDroppedTag)
        assertTrue(final.resultTags.isEmpty())

        // 提示不是常驻文案：4 秒后自动消失（审核 P3-4）
        advanceUntilIdle()
        val cleared = viewModel.uiState.value as SearchUiState.Results
        assertNull(cleared.narrowDroppedTag)
    }
}
