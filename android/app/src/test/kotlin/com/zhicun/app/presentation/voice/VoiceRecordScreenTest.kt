package com.zhicun.app.presentation.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.R
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 录音页界面测试（M9）。
 *
 * 只验"状态 → 界面"的映射与按钮回调：计时显示、暂停/继续文案、
 * 打断对话框两个分支、太短提示、权限说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class VoiceRecordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: VoiceRecordUiState,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onTogglePause: () -> Unit = {},
        onKeep: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme {
                VoiceRecordContent(
                    uiState = state,
                    onStart = onStart,
                    onTogglePause = onTogglePause,
                    onStop = onStop,
                    onCancel = {},
                    onKeepInterrupted = onKeep,
                    onDiscardInterrupted = onDiscard,
                    onTooShortAcknowledged = {},
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    /** 2026-10-24 用户反馈：进页面只是"可以录"，必须再点一次才开始。 */
    @Test
    fun Ready_showsStartButtonAndInvokesCallback() {
        var started = 0
        render(VoiceRecordUiState.Ready, onStart = { started++ })

        composeRule.onNodeWithTag(VoiceRecordTestTags.START).assertIsDisplayed()
        composeRule.onNodeWithText("准备好后开始录音").assertIsDisplayed()
        composeRule.onNodeWithTag(VoiceRecordTestTags.START).performClick()

        assertEquals(1, started)
    }

    @Test
    fun Recording_showsElapsedTimeAndStopButton() {
        render(VoiceRecordUiState.Recording(elapsedMs = 65_000L, paused = false))

        composeRule.onNodeWithTag(VoiceRecordTestTags.TIME).assertIsDisplayed()
        // 65 秒应显示为 01:05，而不是 65（秒数不该超过 60）
        composeRule.onNodeWithText("01:05").assertIsDisplayed()
        composeRule.onNodeWithTag(VoiceRecordTestTags.STOP).assertIsDisplayed()
        composeRule.onNodeWithText("录音中").assertIsDisplayed()
    }

    @Test
    fun RecordingPaused_showsResumeAction() {
        render(VoiceRecordUiState.Recording(elapsedMs = 3_000L, paused = true))

        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("继续").assertIsDisplayed()
    }

    @Test
    fun StopButton_invokesCallback() {
        var stopped = 0
        render(VoiceRecordUiState.Recording(0L, false), onStop = { stopped++ })

        composeRule.onNodeWithTag(VoiceRecordTestTags.STOP).performClick()

        assertEquals(1, stopped)
    }

    @Test
    fun PauseButton_invokesCallback() {
        var toggled = 0
        render(VoiceRecordUiState.Recording(0L, false), onTogglePause = { toggled++ })

        composeRule.onNodeWithTag(VoiceRecordTestTags.PAUSE).performClick()

        assertEquals(1, toggled)
    }

    @Test
    fun Interrupted_showsDialogWithBothChoices() {
        var kept = 0
        var discarded = 0
        render(
            VoiceRecordUiState.Interrupted(elapsedMs = 5_000L),
            onKeep = { kept++ },
            onDiscard = { discarded++ },
        )

        composeRule.onNodeWithTag(VoiceRecordTestTags.INTERRUPT_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(VoiceRecordTestTags.INTERRUPT_KEEP).performClick()
        composeRule.onNodeWithTag(VoiceRecordTestTags.INTERRUPT_DISCARD).performClick()

        assertEquals(1, kept)
        assertEquals(1, discarded)
    }

    @Test
    fun TooShort_showsDialog() {
        render(VoiceRecordUiState.TooShort)

        composeRule.onNodeWithText("录音太短").assertIsDisplayed()
        composeRule.onNodeWithText("没有录到有效内容，不会创建记录。").assertIsDisplayed()
    }

    @Test
    fun PermissionDenied_showsSettingsEntry() {
        var opened = 0
        render(VoiceRecordUiState.PermissionDenied, onOpenSettings = { opened++ })

        composeRule.onNodeWithTag(VoiceRecordTestTags.PERMISSION).assertIsDisplayed()
        composeRule.onNodeWithText("去系统设置开启").assertIsDisplayed().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun Saving_showsProgressHint() {
        render(VoiceRecordUiState.Saving)

        composeRule.onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.voice_saving),
        ).assertIsDisplayed()
    }
}
