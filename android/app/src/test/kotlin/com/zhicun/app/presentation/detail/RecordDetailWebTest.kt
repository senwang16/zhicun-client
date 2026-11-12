package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网页块的快照/配图分区（C，Compose + Robolectric）。
 *
 * 快照与 og:image 都是**附加能力**：钉死"有就显示、没有就完全不渲染"，
 * 绝不能出现"没有快照还留一个点了没反应的按钮"。
 * 正文四态由 DerivedBlock 的既有测试覆盖，这里不重复。
 * 预览图解码依赖真实文件，属真机验收项；这里只测"附件行存在时按钮/预览的渲染条件"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RecordDetailWebTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var openedPath: String? = null

    private fun snapshotAsset(status: AssetSaveStatus = AssetSaveStatus.SAVED, path: String = "/data/snapshot.html") =
        Asset(
            id = "a-html",
            recordId = "r1",
            mimeType = "text/html",
            localPath = path,
            fileSize = 1024,
            fileHash = null,
            originalUri = null,
            saveStatus = status,
        )

    private fun ogImageAsset(status: AssetSaveStatus = AssetSaveStatus.SAVED) = Asset(
        id = "a-img",
        recordId = "r1",
        mimeType = "image/png",
        localPath = "/data/og_image.img",
        fileSize = 2048,
        fileHash = null,
        originalUri = null,
        saveStatus = status,
    )

    private fun derived() = listOf(
        DerivedContent(
            id = "r1:TITLE",
            recordId = "r1",
            contentType = DerivedType.TITLE,
            content = "页面标题",
            processorVersion = "jsoup/1.23.2",
            processedAt = 0L,
            errorMessage = null,
        ),
        DerivedContent(
            id = "r1:PAGE_TEXT",
            recordId = "r1",
            contentType = DerivedType.PAGE_TEXT,
            content = "正文内容",
            processorVersion = "jsoup/1.23.2",
            processedAt = 0L,
            errorMessage = null,
        ),
    )

    private var fetchConfirmed = 0

    /**
     * @param record 覆盖默认记录，用于构造"敏感且未允许抓取"的形态（F-5）
     * @param derived 覆盖默认派生内容（敏感被拦时没有正文）
     */
    private fun render(
        assets: List<Asset>,
        record: Record? = null,
        derived: List<DerivedContent> = derived(),
        /** 2026-10-24：云端可用时确认已合并到 AI 横幅，网页分区不再给第二个按钮。 */
        showFetchConfirm: Boolean = true,
    ) {
        composeRule.setContent {
            AppTheme {
                WebBlock(
                    record = record ?: testRecord(
                        id = "r1",
                        processingStatus = ProcessingStatus.DONE,
                    ),
                    derived = derived,
                    assets = assets,
                    onOpenSnapshot = { openedPath = it },
                    onRetry = {},
                    onConfirmFetch = { fetchConfirmed++ },
                    showFetchConfirm = showFetchConfirm,
                )
            }
        }
    }

    @Test
    fun SnapshotAvailable_showsButtonAndOpensViewer() {
        render(listOf(snapshotAsset()))

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_SNAPSHOT)
            .assertIsDisplayed()
            .performClick()

        assertEquals("/data/snapshot.html", openedPath)
    }

    @Test
    fun NoSnapshot_rendersNothing() {
        render(emptyList())

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_SNAPSHOT).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_OG_IMAGE).assertDoesNotExist()
    }

    /** 文件曾被清理（MISSING）时按钮必须消失：点了只会看到"文件不可用"。 */
    @Test
    fun SnapshotMissing_doesNotShowButton() {
        render(listOf(snapshotAsset(status = AssetSaveStatus.MISSING)))

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_SNAPSHOT).assertDoesNotExist()
    }

    /**
     * 敏感网页被拦下时给"允许抓取此网页"的独立入口（2026-10-23 复核 F-5）。
     *
     * 整改前这里只写"确认上传后可以重新提取"，而"确认上传"要求云端通路可用 ——
     * 默认无网关的安装包里这句话是个死循环：用户永远抓不到正文。
     */
    @Test
    fun SensitiveBlocked_offersConfirmFetch() {
        render(
            assets = emptyList(),
            record = testRecord(
                id = "r1",
                type = RecordType.WEB_LINK,
                rawText = null,
                sourceUrl = "https://example.com/a",
                processingStatus = ProcessingStatus.LOCAL_ONLY,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
            derived = emptyList(),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_CONFIRM_FETCH)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, fetchConfirmed)
        // 重试仍不给：重试会被同一道闸再拦一次
        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_RETRY).assertDoesNotExist()
    }

    /**
     * 云端通路可用时，确认已合并到上方 AI 横幅（2026-10-24 用户反馈）。
     *
     * 网页分区不再给第二个"允许抓取"按钮，只说明"为什么还没有正文"，
     * 免得用户为同一份内容点两次确认。
     */
    @Test
    fun SensitiveBlocked_oneClickFlow_hidesConfirmFetch() {
        render(
            assets = emptyList(),
            record = testRecord(
                id = "r1",
                type = RecordType.WEB_LINK,
                rawText = null,
                sourceUrl = "https://example.com/a",
                processingStatus = ProcessingStatus.LOCAL_ONLY,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
            derived = emptyList(),
            showFetchConfirm = false,
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_CONFIRM_FETCH).assertDoesNotExist()
        composeRule.onNodeWithText("疑似含敏感信息，暂未抓取网页正文。在上方确认后会一并抓取。")
            .assertIsDisplayed()
        // 重试仍不给：重试会被同一道闸再拦一次
        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_RETRY).assertDoesNotExist()
    }

    /** 已允许抓取、但没有正文（比如任务还没跑）：不再重复问一次权限。 */
    @Test
    fun FetchAlreadyAllowed_hidesConfirmButton() {
        render(
            assets = emptyList(),
            record = testRecord(
                id = "r1",
                type = RecordType.WEB_LINK,
                rawText = null,
                sourceUrl = "https://example.com/a",
                processingStatus = ProcessingStatus.LOCAL_ONLY,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
                recordRevision = 1,
                webFetchConfirmedRevision = 1,
            ),
            derived = emptyList(),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_CONFIRM_FETCH).assertDoesNotExist()
    }

    @Test
    fun TextIsStillShown_alongsideSnapshot() {
        render(listOf(snapshotAsset()))

        composeRule.onNodeWithText("页面标题").assertIsDisplayed()
        composeRule.onNodeWithText("正文内容").assertIsDisplayed()
    }
}
