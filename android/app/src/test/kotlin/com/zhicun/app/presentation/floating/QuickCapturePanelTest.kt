package com.zhicun.app.presentation.floating

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 快捷面板（M13 / PRD v1.2 §3）。
 *
 * 面板是纯展示组件，点击只上报 [QuickCaptureAction] —— 因此可以在普通 Compose 测试里
 * 完整验证，不需要悬浮窗环境，也不需要 Service。
 *
 * 2026-10-27 起面板只保留三个入口（截图 / 记一笔 / 打开主界面）；
 * 剪贴板、录音、收件箱仍是通知栏与桌面小组件的动作，不在面板里出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class QuickCapturePanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clicked = mutableListOf<QuickCaptureAction>()

    private fun render() {
        composeRule.setContent {
            AppTheme {
                QuickCapturePanel(onAction = { clicked += it })
            }
        }
    }

    @Test
    fun Panel_showsExactlyThreeEntries() {
        render()

        composeRule.onNodeWithTag(QuickCaptureTestTags.PANEL).assertIsDisplayed()
        PANEL_ACTIONS.forEach { action ->
            composeRule.onNodeWithTag(action.testTag).assertIsDisplayed()
        }
        assertEquals(3, PANEL_ACTIONS.size)
        // 已移出面板的入口必须真的不渲染，否则"三个主要入口"只是文案层面的约定
        listOf(
            QuickCaptureAction.CLIPBOARD,
            QuickCaptureAction.VOICE,
            QuickCaptureAction.INBOX,
        ).forEach { action ->
            composeRule.onNodeWithTag(action.testTag).assertDoesNotExist()
        }
    }

    /** 三个入口都要有中文文案，不能只剩图标（图标在任意壁纸上的可辨识度不够）。 */
    @Test
    fun Panel_eachEntryHasLabel() {
        render()

        composeRule.onNodeWithText("截图").assertIsDisplayed()
        composeRule.onNodeWithText("记一笔").assertIsDisplayed()
        composeRule.onNodeWithText("打开主界面").assertIsDisplayed()
    }

    @Test
    fun ClickScreenshot_reportsScreenshotAction() {
        render()

        composeRule.onNodeWithTag(QuickCaptureAction.SCREENSHOT.testTag).performClick()

        assertEquals(listOf(QuickCaptureAction.SCREENSHOT), clicked)
    }

    @Test
    fun ClickText_reportsTextAction() {
        render()

        composeRule.onNodeWithTag(QuickCaptureAction.TEXT.testTag).performClick()

        assertEquals(listOf(QuickCaptureAction.TEXT), clicked)
    }

    @Test
    fun ClickHome_reportsHomeAction() {
        render()

        composeRule.onNodeWithTag(QuickCaptureAction.HOME.testTag).performClick()

        assertEquals(listOf(QuickCaptureAction.HOME), clicked)
    }

    /**
     * 顺序即用户拇指从上到下扫过的顺序：截图 → 记一笔 → 打开主界面。
     * 钉住它是因为重排枚举会静默改变交互习惯，而不会有任何测试失败。
     */
    @Test
    fun Panel_entriesAreInExpectedOrder() {
        assertEquals(
            listOf(
                QuickCaptureAction.SCREENSHOT,
                QuickCaptureAction.TEXT,
                QuickCaptureAction.HOME,
            ),
            PANEL_ACTIONS,
        )
    }
}
