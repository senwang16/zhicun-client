package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.settings.CloudUploadGate
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
 * 详情页 AI 区块（M11）：摘要、标签、疑似敏感横幅。
 *
 * M11 的验收里最容易被做错的是"疑似敏感"这一态：它既不是失败，也不是"还在处理"，
 * 而是**用户没同意上传**。因此单独断言横幅文案与确认按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RecordDetailAiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tag(name: String, source: TagSource = TagSource.AI) = Tag(
        id = "$source-$name",
        name = name,
        source = source,
        confidence = null,
        createdAt = 1L,
    )

    private fun render(
        status: ProcessingStatus = ProcessingStatus.DONE,
        sensitivity: SensitivityStatus = SensitivityStatus.CLEAR,
        summary: String? = "讨论了三点：范围、时间、负责人。",
        tags: List<Tag> = emptyList(),
        onConfirm: () -> Unit = {},
        onRetry: () -> Unit = {},
        recordType: RecordType = RecordType.TEXT_NOTE,
        cloudBlockReason: CloudUploadGate.Reason? = null,
        /**
         * 默认按"从未确认过"推导：ViewModel 算的是
         * `isSensitive && !cloudConfirmedFor(现存内容块)`，而这里造的记录
         * 没有 `cloudConfirmedRevision`，所以结果就等于"敏感"。
         * 要测"已确认"的形态，显式传 false。
         */
        cloudConfirmPending: Boolean = sensitivity.isSensitive,
        /** 重试是否真的会做事（F-4）；默认 true 让既有用例保持原样。 */
        canRetry: Boolean = true,
        /** 这次确认会一并抓取网页（2026-10-24 用户反馈）：默认 false 保持既有文案。 */
        webFetchPending: Boolean = false,
        onOpenSettings: () -> Unit = {},
        /** 标签行编辑保存（U-7）；默认空实现让既有用例零改动。 */
        onTagsEdited: (List<String>) -> Unit = {},
        /** 复制摘要（2026-10-26）；默认 null = 不画复制按钮，与旧界面一致。 */
        onCopyText: ((String) -> Unit)? = null,
    ) {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = recordType,
                            rawText = "今天和产品对齐了范围。",
                            processingStatus = status,
                            sensitivityStatus = sensitivity,
                        ),
                        assets = emptyList(),
                        derived = if (summary == null) {
                            emptyList()
                        } else {
                            listOf(
                                testDerived(
                                    id = "r1:SUMMARY",
                                    recordId = "r1",
                                    contentType = DerivedType.SUMMARY,
                                    content = summary,
                                ),
                            )
                        },
                        tags = tags,
                        cloudBlockReason = cloudBlockReason,
                        cloudConfirmPending = cloudConfirmPending,
                        canRetry = canRetry,
                        webFetchPending = webFetchPending,
                    ),
                    onBack = {},
                    onRetry = onRetry,
                    onConfirmCloud = onConfirm,
                    onOpenSettings = onOpenSettings,
                    onTagsEdited = onTagsEdited,
                    onCopyText = onCopyText,
                )
            }
        }
    }

    @Test
    fun Summary_isShownWithTags() {
        render(tags = listOf(tag("会议"), tag("待办", TagSource.USER)))

        composeRule.onNodeWithText("AI 摘要与标签").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_SUMMARY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("讨论了三点：范围、时间、负责人。").assertExists()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("会议").assertExists()
        composeRule.onNodeWithText("待办").assertExists()
    }

    @Test
    fun PendingStatus_showsGeneratingHint() {
        render(status = ProcessingStatus.PENDING, summary = null)

        composeRule.onNodeWithText("正在生成摘要…").performScrollTo().assertIsDisplayed()
    }

    /**
     * "未上传"要说真实原因（R-5）。
     *
     * 整改前三种来源共用一句"疑似含敏感信息"：默认安装包（网关未配置）下
     * 每条干净记录都会被说成疑似敏感，既吓人又失真。
     */
    @Test
    fun LocalOnly_cloudDisabled_saysCloudOffAndOffersRetry() {
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            summary = null,
            cloudBlockReason = CloudUploadGate.Reason.CLOUD_DISABLED,
        )

        composeRule.onNodeWithText("云端 AI 处理已关闭，内容没有上传").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("疑似含敏感信息，尚未上传云端").assertDoesNotExist()
        // R-1/R-3：以前这条记录没有任何出路，现在必须能重试
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_RETRY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun LocalOnly_gatewayNotConfigured_saysNotConfigured() {
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            summary = null,
            cloudBlockReason = CloudUploadGate.Reason.NOT_CONFIGURED,
        )

        composeRule.onNodeWithText("还没配置 AI 网关，内容没有上传").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("疑似含敏感信息，尚未上传云端").assertDoesNotExist()
    }

    /** 旧版本写入的 LOCAL_ONLY 没有原因可查：给中性表述，绝不能退回"疑似敏感"。 */
    @Test
    fun LocalOnly_withoutReason_usesNeutralWording() {
        render(status = ProcessingStatus.LOCAL_ONLY, summary = null)

        composeRule.onNodeWithText("内容尚未上传云端").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("正在生成摘要…").assertDoesNotExist()
    }

    /**
     * PARTIAL（本地产出成功 + 云端失败）必须有失败说明与重试入口（R-1）。
     *
     * 整改前判据是 `== FAILED`，PARTIAL 落到"还在生成"分支：文案永远停在
     * "正在生成摘要…"，而重试按钮又不渲染 —— 最常见的一种失败组合因此完全没有出路。
     */
    @Test
    fun PartialStatus_showsFailureAndRetry() {
        var retried = 0
        render(status = ProcessingStatus.PARTIAL, summary = null, onRetry = { retried++ })

        composeRule.onNodeWithText("摘要生成失败，记录内容不受影响").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("正在生成摘要…").assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_RETRY).performScrollTo().performClick()
        assertEquals(1, retried)
    }

    /** 云端根本不可用时不给确认按钮：点了不会发生任何事（R-3）。 */
    @Test
    fun SuspectedSensitive_cloudUnavailable_hidesConfirmButton() {
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            sensitivity = SensitivityStatus.SUSPECTED,
            summary = null,
            cloudBlockReason = CloudUploadGate.Reason.CLOUD_DISABLED,
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_CONFIRM).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_CONFIRM_UNAVAILABLE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun FailedStatus_showsFailureAndRetry() {
        var retried = 0
        render(status = ProcessingStatus.FAILED, summary = null, onRetry = { retried++ })

        composeRule.onNodeWithText("摘要生成失败，记录内容不受影响").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_RETRY).performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun SuspectedSensitive_withoutSummary_showsBannerOnly() {
        var confirmed = 0
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            sensitivity = SensitivityStatus.SUSPECTED,
            summary = null,
            onConfirm = { confirmed++ },
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_SENSITIVE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("疑似包含敏感信息").assertExists()
        // 不能出现"正在生成摘要"：没有上传，就没有在生成
        composeRule.onNodeWithText("正在生成摘要…").assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_SUMMARY).assertDoesNotExist()

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_CONFIRM).performScrollTo().performClick()
        assertEquals(1, confirmed)
    }

    /**
     * 敏感网页：一次点击同时授权上传与抓取，按钮文案必须说清这件事（2026-10-24 用户反馈）。
     *
     * 文案若仍写"确认上传并生成摘要"，用户点下去并不知道会去抓网页；
     * 而网页分区此时已经没有第二个按钮了。
     */
    @Test
    fun SuspectedSensitiveWebLink_confirmButtonAlsoCoversFetch() {
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            sensitivity = SensitivityStatus.SUSPECTED,
            summary = null,
            recordType = RecordType.WEB_LINK,
            webFetchPending = true,
        )

        composeRule.onNodeWithText("确认上传并抓取网页").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("确认上传并生成摘要").assertDoesNotExist()
    }

    /**
     * 待确认时**已经存在**的摘要不能被横幅顶掉（2026-10-23 整改 F-3）。
     *
     * 真实触发路径：确认过上传 → 摘要生成 → 转写随后完成，而转写正文不在确认范围里，
     * 横幅重新出现（R-2）。整改前这条分支直接跳过整块派生结果，
     * 用户看到的是"摘要没了"，与"有新内容待确认"这个真实状态完全不符。
     */
    @Test
    fun SuspectedSensitive_withExistingSummary_showsBannerAndSummary() {
        render(
            status = ProcessingStatus.PENDING,
            sensitivity = SensitivityStatus.SUSPECTED,
            summary = "讨论了三点：范围、时间、负责人。",
            tags = listOf(tag("会议")),
            cloudConfirmPending = true,
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_SENSITIVE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_SUMMARY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("讨论了三点：范围、时间、负责人。").assertExists()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS).performScrollTo().assertIsDisplayed()
        // 有结果就不该说"正在生成"
        composeRule.onNodeWithText("正在生成摘要…").assertDoesNotExist()
    }

    /**
     * 云端被挡 + 纯云端任务：重试按钮是空操作，必须换成设置入口（2026-10-23 整改 F-4）。
     *
     * 整改前这条记录显示"重新生成"，而 `RetryProcessingUseCase` 一个可跑任务都排不出来、
     * 直接返回 false 且不改状态 —— 用户会反复点，以为 App 卡住了。
     */
    @Test
    fun LocalOnly_cloudDisabled_textNote_hidesRetryAndOffersSettings() {
        var opened = 0
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            summary = null,
            cloudBlockReason = CloudUploadGate.Reason.CLOUD_DISABLED,
            canRetry = false,
            onOpenSettings = { opened++ },
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_RETRY).assertDoesNotExist()
        composeRule.onNodeWithText("云端 AI 处理已关闭，内容没有上传").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.OPEN_SETTINGS).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** 回归：图片还有本地 OCR 可跑，重试按钮必须留着（别把 F-4 做成"一律隐藏"）。 */
    @Test
    fun FailedImage_ocrRetry_stillShown() {
        render(
            recordType = RecordType.IMAGE,
            status = ProcessingStatus.FAILED,
            summary = null,
            canRetry = true,
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_RETRY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.OPEN_SETTINGS).assertDoesNotExist()
    }

    /** 横幅里也要有这条出路：云端不通时它会说"到设置里打开后再回来"，那就得能点过去。 */
    @Test
    fun SuspectedSensitive_cloudUnavailable_offersSettingsEntry() {
        var opened = 0
        render(
            status = ProcessingStatus.LOCAL_ONLY,
            sensitivity = SensitivityStatus.SUSPECTED,
            summary = null,
            cloudBlockReason = CloudUploadGate.Reason.CLOUD_DISABLED,
            canRetry = false,
            onOpenSettings = { opened++ },
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.OPEN_SETTINGS_BANNER)
            .performScrollTo()
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun Tags_areCappedAtFive() {
        render(
            tags = (1..7).map { tag("标签$it") },
        )

        // 第 6、7 个不该出现在界面上（与卡片一致，PRD §6.3）
        composeRule.onNodeWithText("标签5").assertExists()
        composeRule.onNodeWithText("标签6").assertDoesNotExist()
    }

    @Test
    fun EmptyTags_hidesTagRow() {
        render(tags = emptyList())

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS).assertDoesNotExist()
    }

    /** U-7（2026-10-24）：点标签进入编辑，输入框预填**全部**标签名（用户标签排前）。 */
    @Test
    fun ClickTag_entersEditModeWithAllNamesPrefilled() {
        render(tags = listOf(tag("会议"), tag("待办", TagSource.USER)))

        composeRule.onNodeWithText("会议").performScrollTo().performClick()

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT)
            .performScrollTo()
            .assertTextEquals("待办、会议")
    }

    /** 保存把顿号/逗号分隔的文本解析成名单交给回调；解析前的 trim 由 ViewModel 做。 */
    @Test
    fun EditSave_invokesCallbackWithParsedNames() {
        var saved: List<String>? = null
        render(
            tags = listOf(tag("会议"), tag("待办", TagSource.USER)),
            onTagsEdited = { saved = it },
        )

        composeRule.onNodeWithText("会议").performScrollTo().performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).performTextClearance()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT)
            .performTextInput("重要会议，待办、新标签")
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_SAVE).performClick()

        assertEquals(listOf("重要会议", "待办", "新标签"), saved)
    }

    /** 空输入保存 = 取消：不回调（一键清光走"整理"分区的逐个删除）。 */
    @Test
    fun EditSave_emptyText_doesNotInvokeCallback() {
        var calls = 0
        render(
            tags = listOf(tag("会议")),
            onTagsEdited = { calls++ },
        )

        composeRule.onNodeWithText("会议").performScrollTo().performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).performTextClearance()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_SAVE).performClick()

        assertEquals(0, calls)
    }

    /** 显式取消（2026-10-26）：退出编辑、保留原标签、不回调保存。 */
    @Test
    fun EditCancel_exitsWithoutInvokingCallback() {
        var calls = 0
        render(
            tags = listOf(tag("会议")),
            onTagsEdited = { calls++ },
        )

        composeRule.onNodeWithText("会议").performScrollTo().performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).performTextInput("改过")
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_CANCEL).performClick()

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).assertDoesNotExist()
        composeRule.onNodeWithText("会议").assertExists()
        assertEquals(0, calls)
    }

    /**
     * 失焦取消（2026-10-26）：点页面其它位置即放弃本次编辑。
     *
     * 焦点清理由 `DetailBody` 根节点的点击手势统一做，所以这里点的是分区标题 ——
     * 它自身没有点击行为，事件会落到页面根节点上，正是真实用户"点空白处"的路径。
     */
    @Test
    fun EditLostFocus_exitsWithoutInvokingCallback() {
        var calls = 0
        render(
            tags = listOf(tag("会议")),
            onTagsEdited = { calls++ },
        )

        composeRule.onNodeWithText("会议").performScrollTo().performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).performTextInput("改过")
        composeRule.onNodeWithText("原文").performScrollTo().performClick()

        composeRule.onNodeWithTag(RecordDetailTestTags.AI_TAGS_EDIT).assertDoesNotExist()
        assertEquals(0, calls)
    }

    /** 摘要复制（2026-10-26 用户裁决：每个模块各自复制）。 */
    @Test
    fun SummaryCopy_invokesCallbackWithSummaryText() {
        var copied: String? = null
        render(summary = "这段摘要要被单独复制", onCopyText = { copied = it })

        composeRule.onNodeWithTag("${RecordDetailTestTags.AI_SUMMARY}_copy")
            .performScrollTo()
            .performClick()

        assertEquals("这段摘要要被单独复制", copied)
    }
}
