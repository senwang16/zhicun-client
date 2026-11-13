package com.zhicun.app.presentation.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.zhicun.app.R
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 搜索界面测试（Compose + Robolectric）。
 *
 * 只测无状态 [SearchContent]。M10 验收里最容易漏的两条在这里钉死：
 * 无结果要有明确提示（不能是空白页）、结果页要显示"关键词搜索"降级说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class SearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        query: String = "",
        uiState: SearchUiState = SearchUiState.Idle,
        onQueryChange: (String) -> Unit = {},
        onClearQuery: () -> Unit = {},
        onRecordClick: (String) -> Unit = {},
        history: List<SearchHistoryEntry> = emptyList(),
        tagUsage: List<TagUsage> = emptyList(),
        onHistorySelected: (SearchHistoryEntry) -> Unit = {},
        onTagSelected: (String) -> Unit = {},
        onResultTagSelected: (String) -> Unit = {},
        onDeleteHistory: (SearchHistoryEntry) -> Unit = {},
        onClearHistory: () -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme {
                SearchContent(
                    query = query,
                    uiState = uiState,
                    onQueryChange = onQueryChange,
                    onClearQuery = onClearQuery,
                    onRecordClick = onRecordClick,
                    history = history,
                    tagUsage = tagUsage,
                    onHistorySelected = onHistorySelected,
                    onTagSelected = onTagSelected,
                    onResultTagSelected = onResultTagSelected,
                    onDeleteHistory = onDeleteHistory,
                    onClearHistory = onClearHistory,
                )
            }
        }
    }

    @Test
    fun IdleState_showsGuidance() {
        render()

        composeRule.onNodeWithTag(SearchTestTags.IDLE).assertIsDisplayed()
        composeRule.onNodeWithText("输入关键词开始搜索").assertIsDisplayed()
    }

    @Test
    fun ResultsState_rendersRecordsAndKeywordOnlyNotice() {
        render(
            query = "陪伴",
            uiState = SearchUiState.Results(
                keyword = "陪伴",
                records = listOf(
                    testRecord(id = "r1", rawText = "断联陪伴 产品笔记"),
                    testRecord(id = "r2", rawText = null, sourceTitle = "陪伴式 AI 的边界"),
                ),
            ),
        )

        composeRule.onNodeWithTag(SearchTestTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithText("断联陪伴 产品笔记").assertIsDisplayed()
        composeRule.onNodeWithText("陪伴式 AI 的边界").assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTestTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("当前为关键词搜索结果").assertIsDisplayed()
    }

    @Test
    fun ResultsState_semanticPending_showsPendingHintInsteadOfDegradeNotice() {
        render(
            query = "陪伴",
            uiState = SearchUiState.Results(
                keyword = "陪伴",
                records = listOf(testRecord(id = "r1", rawText = "断联陪伴 产品笔记")),
                keywordOnly = false,
                semanticPending = true,
            ),
        )

        composeRule.onNodeWithTag(SearchTestTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTestTags.SEMANTIC_PENDING).assertIsDisplayed()
        composeRule.onNodeWithText("正在结合语义排序…").assertIsDisplayed()
        // pending 不是降级：终态才出现的"仅关键词"提示此时不该在
        composeRule.onNodeWithTag(SearchTestTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun ClickResult_invokesCallbackWithRecordId() {
        var clicked: String? = null
        render(
            query = "陪伴",
            uiState = SearchUiState.Results("陪伴", listOf(testRecord(id = "r-9", rawText = "可点的结果"))),
            onRecordClick = { clicked = it },
        )

        composeRule.onNodeWithText("可点的结果").performClick()

        assertEquals("r-9", clicked)
    }

    @Test
    fun EmptyState_showsKeywordInHint() {
        render(query = "不存在的词", uiState = SearchUiState.Empty("不存在的词"))

        composeRule.onNodeWithTag(SearchTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("没有找到相关记录").assertIsDisplayed()
        // 提示里必须带上用户搜的词，否则用户不知道"没有"是针对哪次搜索
        composeRule.onNodeWithText("没有包含「不存在的词」的记录。试试更短的关键词，或者换个说法。")
            .assertIsDisplayed()
    }

    @Test
    fun SearchingState_showsProgress() {
        render(query = "陪伴", uiState = SearchUiState.Searching("陪伴"))

        composeRule.onNodeWithTag(SearchTestTags.SEARCHING).assertIsDisplayed()
    }

    @Test
    fun ErrorState_showsMessage() {
        render(query = "陪伴", uiState = SearchUiState.Error(R.string.search_error))

        composeRule.onNodeWithTag(SearchTestTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("搜索失败，请重试").assertIsDisplayed()
    }

    @Test
    fun TypingInField_forwardsText() {
        val typed = mutableListOf<String>()
        render(query = "", onQueryChange = { typed += it })

        composeRule.onNodeWithTag(SearchTestTags.FIELD).performTextInput("陪伴")

        // Compose 的 TextField 会把整段输入一次性回调（受控组件由外层回填）
        assertEquals(listOf("陪伴"), typed)
    }

    @Test
    fun ClearButton_visibleOnlyWithText() {
        render(query = "", uiState = SearchUiState.Idle)

        composeRule.onNodeWithTag(SearchTestTags.CLEAR).assertDoesNotExist()
    }

    @Test
    fun NoBackButton_searchIsTab() {
        // 搜索是底导 tab（2026-10-24 用户反馈）：返回键没有语义，页面上不应再有返回按钮
        render()

        composeRule.onNodeWithText("返回").assertDoesNotExist()
    }

    // ==== 空闲态：最近搜索 + 标签（M21）====

    private fun entry(query: String) =
        SearchHistoryEntry(queryKey = query.lowercase(), query = query, searchedAt = 1L)

    @Test
    fun IdleWithHistoryAndTags_showsBothSections() {
        render(
            history = listOf(entry("陪伴"), entry("compose")),
            tagUsage = listOf(TagUsage(name = "工作", recordCount = 12)),
        )

        composeRule.onNodeWithTag(SearchTestTags.LANDING).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTestTags.HISTORY_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText("最近搜索").assertIsDisplayed()
        composeRule.onNodeWithText("陪伴").assertIsDisplayed()
        composeRule.onNodeWithText("标签").assertIsDisplayed()
        // 审核 P2-5：标签区是 AI + 用户标签合并，副标题不能声称全部由 AI 生成
        composeRule.onNodeWithText("来自你的记录与 AI 标签").assertIsDisplayed()
        composeRule.onNodeWithText("#工作").assertIsDisplayed()
        // 有内容时不再显示"输入关键词开始搜索"的居中引导
        composeRule.onNodeWithTag(SearchTestTags.IDLE).assertDoesNotExist()
    }

    @Test
    fun IdleWithoutHistoryOrTags_keepsGuidance() {
        render()

        composeRule.onNodeWithTag(SearchTestTags.IDLE).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTestTags.LANDING).assertDoesNotExist()
    }

    @Test
    fun ClickHistoryChip_invokesCallback() {
        var picked: SearchHistoryEntry? = null
        render(
            history = listOf(entry("陪伴")),
            onHistorySelected = { picked = it },
        )

        composeRule.onNodeWithText("陪伴").performClick()

        assertEquals("陪伴", picked?.query)
    }

    @Test
    fun ClickTagChip_invokesCallbackWithTagName() {
        var selected: String? = null
        render(
            tagUsage = listOf(TagUsage(name = "工作", recordCount = 12)),
            onTagSelected = { selected = it },
        )

        composeRule.onNodeWithText("#工作").performClick()

        assertEquals("工作", selected)
    }

    @Test
    fun LongPressHistoryChip_invokesDelete() {
        var deleted: SearchHistoryEntry? = null
        render(
            history = listOf(entry("陪伴")),
            onDeleteHistory = { deleted = it },
        )

        composeRule.onNodeWithText("陪伴").performTouchInput { longClick() }

        assertEquals("陪伴", deleted?.query)
    }

    @Test
    fun ClearHistoryButton_invokesCallback() {
        var cleared = false
        render(
            history = listOf(entry("陪伴")),
            onClearHistory = { cleared = true },
        )

        composeRule.onNodeWithText("清空").performClick()

        assertTrue(cleared)
    }

    // ==== 结果页结果标签（M21 决策 3）====

    private fun resultsWithTags(
        tags: List<TagUsage> = listOf(TagUsage("工作", 2), TagUsage("会议", 1)),
        narrowedTag: String? = null,
        dropped: String? = null,
    ) = SearchUiState.Results(
        keyword = "陪伴",
        records = listOf(
            testRecord(id = "r1", rawText = "陪着 工作 记录"),
            testRecord(id = "r2", rawText = "陪着 会议 记录"),
        ),
        resultTags = tags,
        narrowedTag = narrowedTag,
        narrowDroppedTag = dropped,
    )

    @Test
    fun ResultsWithTags_showsResultTagRow() {
        render(query = "陪伴", uiState = resultsWithTags())

        composeRule.onNodeWithTag(SearchTestTags.RESULT_TAGS).assertIsDisplayed()
        composeRule.onNodeWithText("结果标签").assertIsDisplayed()
        composeRule.onNodeWithText("#工作").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun ResultsWithoutTags_hidesResultTagRow() {
        render(query = "陪伴", uiState = resultsWithTags(tags = emptyList()))

        composeRule.onNodeWithTag(SearchTestTags.RESULT_TAGS).assertDoesNotExist()
    }

    @Test
    fun ClickResultTag_invokesCallbackWithTagName() {
        var selected: String? = null
        render(
            query = "陪伴",
            uiState = resultsWithTags(),
            onResultTagSelected = { selected = it },
        )

        composeRule.onNodeWithContentDescription("按 #工作 在当前结果内筛选").performClick()

        assertEquals("工作", selected)
    }

    @Test
    fun SelectedResultTag_clickInvokesClear() {
        var selected: String? = null
        render(
            query = "陪伴",
            uiState = resultsWithTags(narrowedTag = "工作"),
            onResultTagSelected = { selected = it },
        )

        composeRule.onNodeWithContentDescription("已按 #工作 筛选，点击恢复完整结果").performClick()

        assertEquals("工作", selected)
    }

    @Test
    fun DroppedResultTag_showsNotice() {
        render(query = "陪伴", uiState = resultsWithTags(narrowedTag = null, dropped = "工作"))

        composeRule.onNodeWithTag(SearchTestTags.RESULT_TAGS_DROPPED).assertIsDisplayed()
        composeRule.onNodeWithText("这批结果里已经没有 #工作 的记录，已恢复完整结果")
            .assertIsDisplayed()
    }
}
