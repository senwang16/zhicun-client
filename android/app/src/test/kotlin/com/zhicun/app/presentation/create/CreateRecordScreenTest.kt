package com.zhicun.app.presentation.create

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhicun.app.R
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新建记录界面测试（Compose + Robolectric）。
 *
 * 只测无状态的 [CreateRecordContent]：喂 UiState、断言渲染与回调，
 * 不涉及系统选择器（Photo Picker / 文件选择器需要真实系统 UI，属真机验证项）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class CreateRecordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        uiState: CreateRecordUiState,
        onSave: () -> Unit = {},
        onTextChange: (String) -> Unit = {},
        onCancel: () -> Unit = {},
        onVoiceClick: () -> Unit = {},
        onVoiceConfirm: () -> Unit = {},
        onVoiceDismiss: () -> Unit = {},
        onDiscardConfirm: () -> Unit = {},
        onDiscardDismiss: () -> Unit = {},
        pasteHint: Boolean = false,
    ) {
        composeRule.setContent {
            AppTheme {
                CreateRecordContent(
                    uiState = uiState,
                    onTextChange = onTextChange,
                    onSave = onSave,
                    onCancel = onCancel,
                    onPickImage = {},
                    onPickFile = {},
                    onVoiceClick = onVoiceClick,
                    onVoiceConfirm = onVoiceConfirm,
                    onVoiceDismiss = onVoiceDismiss,
                    onRemoveAttachment = {},
                    onDiscardConfirm = onDiscardConfirm,
                    onDiscardDismiss = onDiscardDismiss,
                    pasteHint = pasteHint,
                )
            }
        }
    }

    /**
     * M13 偏差 D-1 的折中：剪贴板读不到跳来时要给"长按可粘贴"的出口。
     * 用常驻提示条而不是 Snackbar——用户还要切输入法再长按，Snackbar 几秒就没了。
     */
    @Test
    fun PasteHint_showsBannerWhenComingFromClipboardFallback() {
        setContent(CreateRecordUiState(), pasteHint = true)

        composeRule.onNodeWithTag(CreateRecordTestTags.PASTE_HINT).assertIsDisplayed()
        composeRule.onNodeWithText(
            "没读到剪贴板内容不代表丢了：内容还在系统剪贴板里，长按下面的输入框即可粘贴。",
        ).assertIsDisplayed()
    }

    /** 普通入口（收件箱 FAB）不该看到这条提示——用户根本没碰过剪贴板。 */
    @Test
    fun PasteHint_hiddenByDefault() {
        setContent(CreateRecordUiState())

        composeRule.onNodeWithTag(CreateRecordTestTags.PASTE_HINT).assertDoesNotExist()
    }

    @Test
    fun EmptyContent_saveButtonIsDisabled() {
        setContent(CreateRecordUiState())

        composeRule.onNodeWithTag(CreateRecordTestTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun WithText_saveButtonIsEnabledAndClickable() {
        var saved = 0
        setContent(CreateRecordUiState(text = "一句话"), onSave = { saved++ })

        composeRule.onNodeWithTag(CreateRecordTestTags.SAVE).assertIsEnabled().performClick()

        assertEquals(1, saved)
    }

    @Test
    fun WithAttachmentOnly_saveButtonIsEnabled() {
        setContent(
            CreateRecordUiState(
                attachments = listOf(PendingAttachment("content://media/1", AttachmentKind.IMAGE)),
            ),
        )

        composeRule.onNodeWithTag(CreateRecordTestTags.SAVE).assertIsEnabled()
        composeRule.onNodeWithText("图片 1").assertIsDisplayed()
    }

    @Test
    fun TypingInInput_reportsTextChange() {
        val typed = StringBuilder()
        setContent(CreateRecordUiState(), onTextChange = { typed.append(it) })

        composeRule.onNodeWithTag(CreateRecordTestTags.INPUT).performTextInput("买牛奶")

        assertTrue("输入框必须把内容回传给 ViewModel", typed.contains("买牛奶"))
    }

    @Test
    fun SaveFailure_showsRetryHint() {
        setContent(CreateRecordUiState(text = "内容", errorMessageRes = R.string.create_save_failed))

        composeRule.onNodeWithTag(CreateRecordTestTags.ERROR).assertIsDisplayed()
    }

    @Test
    fun RestoredDraft_showsNote() {
        setContent(CreateRecordUiState(text = "恢复的草稿", restoredFromDraft = true))

        composeRule.onNodeWithTag(CreateRecordTestTags.DRAFT_NOTE).assertIsDisplayed()
    }

    @Test
    fun DiscardDialog_confirmAndDismissReportSeparately() {
        var confirmed = 0
        var dismissed = 0
        setContent(
            CreateRecordUiState(text = "没保存", showDiscardDialog = true),
            onDiscardConfirm = { confirmed++ },
            onDiscardDismiss = { dismissed++ },
        )

        composeRule.onNodeWithTag(CreateRecordTestTags.DISCARD_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(CreateRecordTestTags.DISCARD_CANCEL).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)

        composeRule.onNodeWithTag(CreateRecordTestTags.DISCARD_CONFIRM).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun Saving_disablesSaveButtonAndShowsProgressText() {
        setContent(CreateRecordUiState(text = "内容", isSaving = true))

        composeRule.onNodeWithTag(CreateRecordTestTags.SAVE).assertIsNotEnabled()
        // 2026-10 改版：底部保存大按钮删除（设计稿 03），只保留顶栏这一个保存入口
        composeRule.onAllNodesWithText("保存中…").assertCountEquals(1)
    }

    // ------------------------------------------------------------ M21：语音入口

    /** 语音不再是"添加附件"里的第三个按钮：附件区只有图片/文件。 */
    @Test
    fun VoiceEntry_isSeparatedFromAttachmentRow() {
        setContent(CreateRecordUiState())

        composeRule.onNodeWithTag(CreateRecordTestTags.PICK_IMAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(CreateRecordTestTags.PICK_FILE).assertIsDisplayed()
        composeRule.onNodeWithText("语音").assertDoesNotExist()
        composeRule.onNodeWithText("文件支持音频、视频、PDF 等，会作为本条记录的附件。").assertIsDisplayed()
        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE).assertIsDisplayed()
        composeRule.onNodeWithText("录一条语音").assertIsDisplayed()
        composeRule.onNodeWithText("会单独保存为一条记录，不加入当前附件").assertIsDisplayed()
    }

    @Test
    fun VoiceEntry_click_reportsRequest() {
        var requests = 0
        setContent(CreateRecordUiState(), onVoiceClick = { requests++ })

        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE).performClick()

        assertEquals(1, requests)
    }

    @Test
    fun VoiceConfirmDialog_confirmAndDismissReportSeparately() {
        var confirmed = 0
        var dismissed = 0
        setContent(
            CreateRecordUiState(text = "还没保存", showVoiceConfirmDialog = true),
            onVoiceConfirm = { confirmed++ },
            onVoiceDismiss = { dismissed++ },
        )

        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE_CONFIRM_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("当前内容尚未保存").assertIsDisplayed()

        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE_CONFIRM_CANCEL).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)

        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE_CONFIRM_GO).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun VoiceConfirmDialog_hiddenWithoutUnsavedContent() {
        setContent(CreateRecordUiState())

        composeRule.onNodeWithTag(CreateRecordTestTags.VOICE_CONFIRM_DIALOG).assertDoesNotExist()
    }
}
