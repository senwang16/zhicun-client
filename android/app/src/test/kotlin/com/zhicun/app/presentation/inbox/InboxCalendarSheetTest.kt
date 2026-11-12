package com.zhicun.app.presentation.inbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.zhicun.app.presentation.theme.AppTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 日历筛选弹层（M19）。
 *
 * 只测交互契约（点天 → 选择 / 再点 → 清除 / 清除按钮），
 * 圆点渲染属视觉项，由组头与 ViewModel 的 dayCounts 单测兜底。
 * 日期用 `LocalDate.now()`：弹层初始月就是当前月，不依赖滚动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class InboxCalendarSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun InboxCalendarSheet_clickDay_invokesSelection() {
        val today = LocalDate.now()
        var selected: LocalDate? = null
        composeRule.setContent {
            AppTheme {
                InboxCalendarSheet(
                    dayCounts = mapOf(today to 2),
                    selectedDate = null,
                    onDismiss = {},
                    onDateSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.calendarDayTag(today))
            .assertIsDisplayed()
            .performClick()

        assertEquals(today, selected)
    }

    @Test
    fun InboxCalendarSheet_clickSelectedDay_clearsFilter() {
        val today = LocalDate.now()
        var selected: LocalDate? = today
        composeRule.setContent {
            AppTheme {
                InboxCalendarSheet(
                    dayCounts = mapOf(today to 2),
                    selectedDate = today,
                    onDismiss = {},
                    onDateSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.calendarDayTag(today)).performClick()

        assertNull("再点已选中的天 = 清除筛选", selected)
    }

    @Test
    fun InboxCalendarSheet_dayCell_occupiesOneSeventhWidth() {
        val today = LocalDate.now()
        composeRule.setContent {
            AppTheme {
                InboxCalendarSheet(
                    dayCounts = mapOf(today to 2),
                    selectedDate = null,
                    onDismiss = {},
                    onDateSelected = {},
                )
            }
        }

        // 守卫（2026-10-24 实踩）：库已用 weight(1f) 给日格分配 1/7 宽；若日格再
        // fillMaxWidth(1/7) 会变成视口 1/49，整张日历塌成残影。正常 ≈ 屏宽/7，
        // 塌陷 ≈ 屏宽/49，阈值取 1/10 屏宽，两侧各留近 3 倍余量。
        // ModalBottomSheet 是独立窗口，onRoot() 会命中多个根，取最宽的作为主屏。
        val rootWidth = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes()
            .maxOf { it.size.width }
        val cellWidth = composeRule.onNodeWithTag(InboxTestTags.calendarDayTag(today))
            .fetchSemanticsNode().size.width

        assertTrue(
            "日格宽度应 ≥ 屏宽/10（实际 $cellWidth / 屏宽 $rootWidth）",
            cellWidth * 10 >= rootWidth,
        )
    }

    @Test
    fun InboxCalendarSheet_clearButton_visibleOnlyWhenSelected() {
        val today = LocalDate.now()
        var selected: LocalDate? = null
        composeRule.setContent {
            AppTheme {
                InboxCalendarSheet(
                    dayCounts = mapOf(today to 2),
                    selectedDate = today,
                    onDismiss = {},
                    onDateSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.CALENDAR_CLEAR)
            .assertIsDisplayed()
            .performClick()

        assertNull(selected)
    }
}
