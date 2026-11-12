package com.zhicun.app.presentation.archive

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 归档箱界面测试（M16）。
 *
 * 与收件箱测试同一套路：只测无状态的 [ArchiveContent]，直接喂 UiState。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ArchiveScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ArchiveContent_withRecords_rendersSnippetAndCount() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "归档的会议记录", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithText("归档的会议记录").assertIsDisplayed()
        composeRule.onNodeWithText("共 1 条").assertIsDisplayed()
    }

    @Test
    fun ArchiveContent_clickRecord_invokesCallbackWithId() {
        var clicked: String? = null
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a-42", rawText = "可以点开看全文", archivedAt = 1_000L)),
                    ),
                    onRecordClick = { clicked = it },
                )
            }
        }

        composeRule.onNodeWithText("可以点开看全文").performClick()

        assertEquals("a-42", clicked)
    }

    @Test
    fun ArchiveContent_emptyState_showsGuidance() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(uiState = ArchiveUiState.Empty, onRecordClick = {})
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("归档箱是空的").assertIsDisplayed()
    }

    @Test
    fun ArchiveContent_loadingState_showsProgress() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(uiState = ArchiveUiState.Loading, onRecordClick = {})
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun ArchiveContent_errorState_showsErrorMessage() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Error(com.zhicun.app.R.string.archive_error_load),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithText("归档箱读取失败，请重启应用后再试").assertIsDisplayed()
    }

    @Test
    fun ArchiveContent_noBackButton_archiveIsTab() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(uiState = ArchiveUiState.Empty, onRecordClick = {})
            }
        }

        // 归档箱是底导 tab，没有返回语义（2026-10-24 U-11，与搜索页 U-7 同理）
        composeRule.onNodeWithText("返回").assertDoesNotExist()
    }

    @Test
    fun ArchiveContent_selectEntry_invokesEnterSelection() {
        var enters = 0
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    onEnterSelection = { enters++ },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECT_ENTRY).performClick()

        assertEquals(1, enters)
    }

    @Test
    fun ArchiveContent_selectionMode_showsCountAndRestore() {
        var restored = 0
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "要恢复的记录", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    selection = ArchiveSelection(active = true, selectedIds = setOf("a1")),
                    onRestoreSelected = { restored++ },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_BAR).assertIsDisplayed()
        composeRule.onNodeWithText("已选 1 条").assertIsDisplayed()
        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_RESTORE).performClick()

        assertEquals(1, restored)
    }

    @Test
    fun ArchiveContent_selectionModeWithNothingSelected_disablesRestore() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(listOf(testRecord(id = "a1", archivedAt = 1_000L))),
                    onRecordClick = {},
                    selection = ArchiveSelection(active = true),
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_RESTORE).assertIsNotEnabled()
    }

    @Test
    fun ArchiveContent_selectionModeCardClick_togglesInsteadOfOpeningDetail() {
        var clicked: String? = null
        var toggled: String? = null
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "要恢复的记录", archivedAt = 1_000L)),
                    ),
                    onRecordClick = { clicked = it },
                    selection = ArchiveSelection(active = true),
                    onToggleSelected = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithText("要恢复的记录").performClick()

        assertEquals("a1", toggled)
        assertNull("选择模式下点卡片不该跳详情", clicked)
    }

    // ------------------------------------------------------------ 删除（2026-10-24）

    /**
     * 归档箱里的删除是**真的删**，不是"从归档箱挪走"。
     *
     * 所以同样要有确认框：用户很容易以为在归档箱点删除 = 退回收件箱。
     */
    @Test
    fun ArchiveContent_deleteButton_opensConfirmDialogWithoutDeleting() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(
                            testRecord(id = "a1", rawText = "归档一", archivedAt = 1_000L),
                            testRecord(id = "a2", rawText = "归档二", archivedAt = 1_000L),
                            testRecord(id = "a3", rawText = "归档三", archivedAt = 1_000L),
                        ),
                    ),
                    onRecordClick = {},
                    selection = ArchiveSelection(active = true, selectedIds = setOf("a1", "a2", "a3")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_DELETE).performClick()

        composeRule.onNodeWithText("删除 3 条记录？").assertIsDisplayed()
        assertEquals("确认之前不该真的删", 0, deletes)
    }

    @Test
    fun ArchiveContent_deleteDialogConfirm_reportsToCallback() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "要删掉的", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    selection = ArchiveSelection(active = true, selectedIds = setOf("a1")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_DELETE).performClick()
        composeRule.onNodeWithTag(ArchiveTestTags.DELETE_DIALOG_CONFIRM).performClick()

        assertEquals(1, deletes)
        composeRule.onAllNodesWithTag(ArchiveTestTags.DELETE_DIALOG_CONFIRM).assertCountEquals(0)
    }

    @Test
    fun ArchiveContent_deleteDialogCancel_dismissesWithoutDeleting() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "要删掉的", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    selection = ArchiveSelection(active = true, selectedIds = setOf("a1")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SELECTION_DELETE).performClick()
        composeRule.onNodeWithTag(ArchiveTestTags.DELETE_DIALOG_CANCEL).performClick()

        assertEquals(0, deletes)
        composeRule.onAllNodesWithTag(ArchiveTestTags.DELETE_DIALOG_CONFIRM).assertCountEquals(0)
    }

    // ------------------------------------------------- 归档箱内搜索（2026-10-24）

    @Test
    fun ArchiveContent_searchField_rendersAndReportsInput() {
        var query: String? = null
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "归档的会议记录", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    onQueryChange = { query = it },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SEARCH_FIELD)
            .assertIsDisplayed()
            .performTextInput("会议")

        assertEquals("会议", query)
    }

    @Test
    fun ArchiveContent_searchFieldClearButton_invokesClearQuery() {
        var cleared = false
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(
                        listOf(testRecord(id = "a1", rawText = "归档的会议记录", archivedAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    query = "会议",
                    onClearQuery = { cleared = true },
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SEARCH_CLEAR).performClick()

        assertTrue(cleared)
    }

    /**
     * 搜索无结果的提示必须与"归档箱是空的"分开：混用一句话，
     * 用户会以为搜到的空等于记录全没了。
     */
    @Test
    fun ArchiveContent_searchWithNoResults_showsSearchEmptyHint() {
        composeRule.setContent {
            AppTheme {
                ArchiveContent(
                    uiState = ArchiveUiState.Content(records = emptyList(), query = "不存在的词"),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(ArchiveTestTags.SEARCH_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("没有找到匹配的归档记录").assertIsDisplayed()
        composeRule.onNodeWithText("归档箱是空的").assertDoesNotExist()
    }
}
