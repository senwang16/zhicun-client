package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多图 OCR 分段（2026-10-29）。
 *
 * 验收点：
 * 1. 两张以上图片时每张一张结果卡，标题点出顺序与文件名；
 * 2. 单一文件记录（含老数据）不出现分段标题 —— 多一层壳只是噪音；
 * 3. 复制一次带走全部识别结果，用户不必逐张复制再自己拼。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class RecordDetailOcrSegmentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val images = listOf(
        testAsset(id = "a1", recordId = "r1", mimeType = "image/png", fileName = "白板左侧.png", orderIndex = 0),
        testAsset(id = "a2", recordId = "r1", mimeType = "image/png", fileName = "白板右侧.png", orderIndex = 1),
    )

    private fun render(
        derived: List<com.zhicun.app.domain.model.DerivedContent>,
        assets: List<com.zhicun.app.domain.model.Asset> = images,
        status: ProcessingStatus = ProcessingStatus.DONE,
        onCopyText: ((String) -> Unit)? = null,
    ) {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = status,
                        ),
                        assets = assets,
                        derived = derived,
                    ),
                    onBack = {},
                    onCopyText = onCopyText,
                )
            }
        }
    }

    @Test
    fun twoImages_showsOneCardPerImageWithHeading() {
        render(
            derived = listOf(
                testDerived(
                    id = "r1:OCR_TEXT:a1",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "左半边的字",
                    assetId = "a1",
                ),
                testDerived(
                    id = "r1:OCR_TEXT:a2",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "右半边的字",
                    assetId = "a2",
                ),
            ),
        )

        composeRule.onNodeWithText("第 1 张 · 白板左侧.png").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("第 2 张 · 白板右侧.png").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.ocrTextTag("a1")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.ocrTextTag("a2")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("左半边的字").assertExists()
        composeRule.onNodeWithText("右半边的字").assertExists()
    }

    /** 只有一张图时保持旧形态：没有"第 1 张"这种多余的分段标题。 */
    @Test
    fun singleImage_hasNoSegmentHeading() {
        render(
            derived = listOf(
                testDerived(
                    id = "r1:OCR_TEXT:a1",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "唯一的识别结果",
                    assetId = "a1",
                ),
            ),
            assets = images.take(1),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_TEXT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("第 1 张 · 白板左侧.png").assertDoesNotExist()
    }

    /** 升级后尚未补跑的多图记录（只有一条记录级结果）：保持单卡，不出现 N 个"正在识别"。 */
    @Test
    fun twoImagesWithLegacyRow_staysSingleCard() {
        render(
            derived = listOf(
                testDerived(
                    id = "r1:OCR_TEXT",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "升级前识别出的整段文字",
                ),
            ),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_TEXT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("升级前识别出的整段文字").assertExists()
        composeRule.onAllNodesWithTag(RecordDetailTestTags.ocrTextTag("a1")).assertCountEquals(0)
    }

    /** 复制一次带走全部结果：按图片顺序拼接，中间空一行。 */
    @Test
    fun copyButton_copiesAllSegmentsInOrder() {
        var copied: String? = null
        render(
            derived = listOf(
                testDerived(
                    id = "r1:OCR_TEXT:a1",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "左半边的字",
                    assetId = "a1",
                ),
                testDerived(
                    id = "r1:OCR_TEXT:a2",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "右半边的字",
                    assetId = "a2",
                ),
            ),
            onCopyText = { copied = it },
        )

        composeRule.onNodeWithTag("${RecordDetailTestTags.OCR_TEXT}_copy")
            .performScrollTo()
            .performClick()

        assertEquals("左半边的字\n\n右半边的字", copied)
    }

    /** 第二张失败时只标第二张：用户要知道是哪一张没成功。 */
    @Test
    fun failedSegment_isMarkedOnlyOnItsOwnCard() {
        render(
            status = ProcessingStatus.PARTIAL,
            derived = listOf(
                testDerived(
                    id = "r1:OCR_TEXT:a1",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = "左半边的字",
                    assetId = "a1",
                ),
                testDerived(
                    id = "r1:OCR_TEXT:a2",
                    recordId = "r1",
                    contentType = DerivedType.OCR_TEXT,
                    content = null,
                    assetId = "a2",
                ),
            ),
        )

        composeRule.onNodeWithText("左半边的字").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("这张图识别失败，原图仍可正常查看")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
