package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.usecase.RelatedRecord
import com.zhicun.app.domain.usecase.RelatedRecords
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 相关记录分区（M18，2026-10-24 重做展示后的断言）。
 *
 * 只测无状态块。"空列表时整块不渲染"是调用方（RecordDetailScreen 槽位）的职责，在 VM/壳层测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class RecordDetailRelatedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var clickedId: String? = null

    @Test
    fun Related_rendersOneCardPerItem_withFirstLineAsTitle() {
        render(
            RelatedRecords(
                items = listOf(
                    related("r1", rawText = "第一行标题\n第二行正文"),
                    related("r2", rawText = "另一条记录"),
                ),
                total = 2,
            ),
        )

        composeRule.onNodeWithTag(RelatedRecordsTestTags.BLOCK).assertIsDisplayed()
        assertEquals(
            2,
            composeRule.onAllNodesWithTag(RelatedRecordsTestTags.ITEM).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("第一行标题").assertIsDisplayed()
    }

    /**
     * 语音记录的 `raw_text` 是 null（转写落在 `derived_contents`）。
     *
     * 旧实现直接取 `rawText` 首行 → 标题空白，整张卡只剩时间戳，看着像随机塞的。
     * 现在走 `displayTitleOrFallback()`，与收件箱卡片同一套规则。
     */
    @Test
    fun Related_voiceRecordWithoutRawText_fallsBackToTimestampTitle() {
        render(
            RelatedRecords(
                items = listOf(related("r1", rawText = null, type = RecordType.VOICE_NOTE)),
                total = 1,
            ),
        )

        // "MM-dd HH:mm 录音"：断言包含"录音"而不是拼死具体时区格式
        composeRule.onNodeWithText("录音", substring = true).assertIsDisplayed()
    }

    /** 副行给出"这是什么类型"，否则图片、语音、网页记录在视觉上完全一样。 */
    @Test
    fun Related_metaLine_showsTypeAndTimestamp() {
        render(
            RelatedRecords(
                items = listOf(related("r1", rawText = null, type = RecordType.VOICE_NOTE)),
                total = 1,
            ),
        )

        composeRule.onNodeWithText("语音 · ", substring = true).assertIsDisplayed()
    }

    /** 没有分数时用户无法验证"为什么这条被视为相关"。 */
    @Test
    fun Related_showsSimilarityAsPercentage() {
        render(
            RelatedRecords(
                items = listOf(related("r1", rawText = "标题", score = 0.7234)),
                total = 1,
            ),
        )

        composeRule.onNodeWithText("72%").assertIsDisplayed()
    }

    /**
     * 总数大于展示条数时，标题必须报出总数。
     *
     * 旧实现只返回 top-N，"只有 5 条"与"有 40 条只显示了 5 条"在界面上完全一样。
     */
    @Test
    fun Related_titleReportsTotal_whenTruncated() {
        render(
            RelatedRecords(
                items = listOf(related("r1"), related("r2")),
                total = 6,
            ),
        )

        composeRule.onNodeWithText("相关记录 · 共 6 条").assertIsDisplayed()
    }

    /** 总数恰好等于展示条数时，"共 N 条"是一句废话，不显示。 */
    @Test
    fun Related_titleOmitsTotal_whenNothingIsTruncated() {
        render(RelatedRecords(items = listOf(related("r1"), related("r2")), total = 2))

        composeRule.onNodeWithText("相关记录").assertIsDisplayed()
        composeRule.onNodeWithText("相关记录 · 共 2 条").assertDoesNotExist()
    }

    @Test
    fun Related_clickReportsRecordId() {
        render(RelatedRecords(items = listOf(related("r9", rawText = "点我")), total = 1))

        composeRule.onAllNodesWithTag(RelatedRecordsTestTags.ITEM)[0].performClick()

        assertEquals("r9", clickedId)
    }

    private fun related(
        id: String,
        rawText: String? = "内容",
        type: RecordType = RecordType.TEXT_NOTE,
        score: Double = 0.8,
    ) = RelatedRecord(
        record = testRecord(id = id, rawText = rawText, type = type),
        score = score,
    )

    private fun render(related: RelatedRecords) {
        composeRule.setContent {
            AppTheme {
                RelatedRecordsBlock(
                    related = related,
                    onRecordClick = { clickedId = it },
                )
            }
        }
    }
}
