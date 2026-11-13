package com.zhicun.app.presentation.inbox

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.testRecord
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
 * 收件箱界面测试（Compose + Robolectric，本机无真机也能验证渲染与点击）。
 *
 * 只测无状态的 [InboxContent]：直接喂 UiState，不需要 Hilt / ViewModel / 数据库，
 * 失败时能立刻区分"UI 画错了"还是"数据没来"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class InboxScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun InboxContent_withRecords_rendersTitleSnippetAndStatus() {
        val records = listOf(
            testRecord(id = "r1", rawText = "买牛奶\n记得买两盒"),
            testRecord(id = "r2", rawText = "第二条记录"),
        )

        composeRule.setContent {
            AppTheme {
                InboxContent(uiState = InboxUiState.Content(records), onRecordClick = {})
            }
        }

        composeRule.onNodeWithText("买牛奶").assertIsDisplayed()
        composeRule.onNodeWithText("记得买两盒").assertIsDisplayed()
        composeRule.onNodeWithText("第二条记录").assertIsDisplayed()
        // 每张卡都有状态徽标（文案来自 strings.xml），两张卡 ⇒ 两个「待处理」
        composeRule.onAllNodesWithText("待处理").assertCountEquals(2)
    }

    @Test
    fun InboxContent_clickCard_invokesCallbackWithRecordId() {
        var clicked: String? = null

        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r-42", rawText = "可点击的标题"))),
                    onRecordClick = { clicked = it },
                )
            }
        }

        composeRule.onNodeWithText("可点击的标题").performClick()

        assertEquals("r-42", clicked)
    }

    @Test
    fun InboxContent_emptyState_showsGuidance() {
        composeRule.setContent {
            AppTheme {
                InboxContent(uiState = InboxUiState.Empty, onRecordClick = {})
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("还没有存过东西").assertIsDisplayed()
    }

    @Test
    fun InboxContent_loadingState_showsProgress() {
        composeRule.setContent {
            AppTheme {
                InboxContent(uiState = InboxUiState.Loading, onRecordClick = {})
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun InboxContent_errorState_showsErrorMessage() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Error(com.zhicun.app.R.string.inbox_error_load),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithText("收件箱读取失败，请重启应用后再试").assertIsDisplayed()
    }

    // 2026-10 改版：搜索/设置入口移至底部导航（AppBottomBar），不再属于 InboxContent。
    // 底导的选中与跳转语义由导航层保证，RoutesTest 覆盖路由契约。

    // ------------------------------------------------------------ M16：筛选与整理

    @Test
    fun InboxContent_favoriteChip_invokesToggleFilter() {
        var toggles = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    onToggleFilter = { toggles++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.FILTER_FAVORITE).assertIsDisplayed().performClick()

        assertEquals(1, toggles)
    }

    /**
     * 已经选中的筛选片再点一次**不该**再切一次。
     *
     * `toggleFilter` 是无参取反，如果 UI 不做这一层判断，"点已选中的那个"会把列表切走，
     * 用户会以为筛选片点坏了。
     */
    @Test
    fun InboxContent_clickingAlreadySelectedChip_doesNotToggle() {
        var toggles = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        records = listOf(testRecord(id = "r1", rawText = "一条记录")),
                        filter = InboxFilter.ALL,
                    ),
                    onRecordClick = {},
                    onToggleFilter = { toggles++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.FILTER_ALL).performClick()

        assertEquals(0, toggles)
    }

    /**
     * 筛选后为空必须说"没有收藏的记录"，而不是"还没有存过东西"。
     *
     * 后者会让一个有 3 条记录的用户以为数据丢了。
     */
    @Test
    fun InboxContent_favoriteFilterEmpty_showsFilteredHint() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        records = emptyList(),
                        filter = InboxFilter.FAVORITE,
                        inboxTotal = 3,
                    ),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.FILTERED_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("没有收藏的记录").assertIsDisplayed()
    }

    @Test
    fun InboxContent_selectEntry_invokesEnterSelection() {
        var enters = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    onEnterSelection = { enters++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECT_ENTRY).assertIsDisplayed().performClick()

        assertEquals(1, enters)
    }

    @Test
    fun InboxContent_selectionMode_showsCountAndEnabledArchive() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_BAR).assertIsDisplayed()
        composeRule.onNodeWithText("已选 1 条").assertIsDisplayed()
        composeRule.onNodeWithTag(InboxTestTags.SELECTION_ARCHIVE).assertIsEnabled()
        // 收藏与归档并列（2026-10-24）：整理不只是归档
        composeRule.onNodeWithTag(InboxTestTags.SELECTION_FAVORITE).assertIsEnabled()
        // 选择模式下新建按钮必须让位，否则会挡住最后一张卡片
        composeRule.onAllNodesWithTag(InboxTestTags.CREATE_FAB).assertCountEquals(0)
    }

    @Test
    fun InboxContent_selectionMode_clickingFavorite_reportsToCallback() {
        var favorites = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                    onFavoriteSelected = { favorites++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_FAVORITE).performClick()

        assertEquals(1, favorites)
    }

    /**
     * 收藏筛选视图里的动作必须是"取消收藏"（2026-10-24 用户反馈）：
     * 文案变、点击目标也变 —— 用户正看着收藏列表，"收藏"按钮点了毫无反应。
     */
    @Test
    fun InboxContent_favoriteViewSelection_showsUnfavoriteAndReportsToCallback() {
        var unfavorites = 0
        var favorites = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "r1", rawText = "一条记录")),
                        filter = InboxFilter.FAVORITE,
                    ),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                    onFavoriteSelected = { favorites++ },
                    onUnfavoriteSelected = { unfavorites++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_FAVORITE)
            .assertIsDisplayed()
            .performClick()

        assertEquals("收藏视图下点击该按钮是取消收藏", 1, unfavorites)
        assertEquals(0, favorites)
        composeRule.onNodeWithText("取消收藏").assertIsDisplayed()
        composeRule.onAllNodesWithText("已选 1 条").assertCountEquals(1)
    }

    /** 全部视图下仍是"收藏"：label 与回调都必须跟 filter 走，不能写死。 */
    @Test
    fun InboxContent_allViewSelection_showsFavoriteLabel() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "r1", rawText = "一条记录")),
                        filter = InboxFilter.ALL,
                    ),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                )
            }
        }

        // "收藏"两个字在筛选片上也会出现，这里只认选择栏那个按钮
        composeRule.onNodeWithTag(InboxTestTags.SELECTION_FAVORITE).assertIsDisplayed()
        composeRule.onAllNodesWithText("取消收藏").assertCountEquals(0)
    }

    @Test
    fun InboxContent_selectionModeWithNothingSelected_disablesArchive() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true),
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_ARCHIVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(InboxTestTags.SELECTION_FAVORITE).assertIsNotEnabled()
    }

    // ------------------------------------------------------------ M20：合并为合集

    @Test
    fun InboxContent_twoSelected_showsMergeFabAndReportsClick() {
        var merges = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(
                            testRecord(id = "r1", rawText = "第一条"),
                            testRecord(id = "r2", rawText = "第二条"),
                        ),
                    ),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1", "r2")),
                    onCreateCollection = { merges++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_COLLECTION)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, merges)
    }

    /** 只选一条时不提供合并：合集的意义是"合并"，一条不需要新实体。 */
    @Test
    fun InboxContent_singleSelected_hidesMergeFab() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                )
            }
        }

        composeRule.onAllNodesWithTag(InboxTestTags.SELECTION_COLLECTION).assertCountEquals(0)
    }

    @Test
    fun InboxContent_selectionModeCardClick_togglesInsteadOfOpeningDetail() {
        var clicked: String? = null
        var toggled: String? = null
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = { clicked = it },
                    selection = InboxSelection(active = true),
                    onToggleSelected = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithText("一条记录").performClick()

        assertEquals("r1", toggled)
        assertNull("选择模式下点卡片不该跳详情", clicked)
    }

    @Test
    fun InboxContent_longPressCard_startsSelection() {
        var longPressed: String? = null
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    onLongPressRecord = { longPressed = it },
                )
            }
        }

        composeRule.onNodeWithText("一条记录").performTouchInput { longClick() }

        assertEquals("r1", longPressed)
    }

    @Test
    fun InboxContent_archiveButton_invokesArchiveSelected() {
        var archived = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "一条记录"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                    onArchiveSelected = { archived++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_ARCHIVE).performClick()

        assertEquals(1, archived)
    }

    // ------------------------------------------------------------ 删除（2026-10-24）

    /**
     * 点「删除」只弹确认框，**不能**直接把记录删掉。
     *
     * 用户明确要求"多选了 5 条，就告诉我你要删这 5 条吗"。所以这一层必须先拦住。
     */
    @Test
    fun InboxContent_deleteButton_opensConfirmDialogWithoutDeleting() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(
                            testRecord(id = "r1", rawText = "记录一"),
                            testRecord(id = "r2", rawText = "记录二"),
                        ),
                    ),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1", "r2")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_DELETE).performClick()

        // 标题要带上条数，否则用户不知道自己正在删几条
        composeRule.onNodeWithText("删除 2 条记录？").assertIsDisplayed()
        assertEquals("确认之前不该真的删", 0, deletes)
    }

    @Test
    fun InboxContent_deleteDialogCancel_dismissesWithoutDeleting() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "记录一"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_DELETE).performClick()
        composeRule.onNodeWithTag(InboxTestTags.DELETE_DIALOG_CANCEL).performClick()

        assertEquals(0, deletes)
        composeRule.onAllNodesWithTag(InboxTestTags.DELETE_DIALOG_CONFIRM).assertCountEquals(0)
    }

    @Test
    fun InboxContent_deleteDialogConfirm_reportsToCallback() {
        var deletes = 0
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(listOf(testRecord(id = "r1", rawText = "记录一"))),
                    onRecordClick = {},
                    selection = InboxSelection(active = true, selectedIds = setOf("r1")),
                    onDeleteSelected = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.SELECTION_DELETE).performClick()
        composeRule.onNodeWithTag(InboxTestTags.DELETE_DIALOG_CONFIRM).performClick()

        assertEquals(1, deletes)
        // 确认后对话框必须自己关掉，否则下一次进选择模式它还挂着
        composeRule.onAllNodesWithTag(InboxTestTags.DELETE_DIALOG_CONFIRM).assertCountEquals(0)
    }

    /** 卡片上的置顶与收藏标记：不打标记的话，"为什么这条排在最前面"就无从解释。 */
    @Test
    fun InboxContent_pinnedAndFavoriteRecord_showsMarkers() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "r1", rawText = "重要的事", isPinned = true, isFavorite = true)),
                    ),
                    onRecordClick = {},
                )
            }
        }

        // M19 起置顶记录有自己的组头："置顶"出现两处（组头 + 卡片标记），
        // 用数量断言而不是单节点，两处都活着才算数
        composeRule.onAllNodesWithText("置顶").assertCountEquals(2)
        composeRule.onNodeWithText("★").assertIsDisplayed()
    }

    /**
     * 录音卡片必须有可区分的名字（2026-10-24 反馈）。
     *
     * 录音的转写落在 `derived_contents` 而不是 `raw_text`，所以 `displayTitle()` 恒为 null。
     * 此前一律退到"未命名记录"：十几条录音长得一模一样，用户挑不出要听哪条。
     * 现在按"类型 + 创建时间"生成，如"11-15 06:13 录音"。
     */
    @Test
    fun InboxContent_voiceNoteWithoutText_showsDatedFallbackTitle() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(
                            testRecord(
                                id = "v1",
                                type = RecordType.VOICE_NOTE,
                                rawText = null,
                                createdAt = 1_700_000_000_000L,
                            ),
                        ),
                    ),
                    onRecordClick = {},
                )
            }
        }

        // 具体时刻取决于运行机器的时区，这里只断言"时间 + 类型"这个形状
        composeRule.onNodeWithText("录音", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("未命名记录").assertCountEquals(0)
    }

    // ------------------------------------------------------------ M19：分组折叠 + 日期筛选

    /** 构造"某天正午"的 epoch millis（系统时区），与生产 DateGroups 口径一致。 */
    private fun millisAt(date: LocalDate): Long =
        date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun InboxContent_withGroupedRecords_rendersPinnedAndDayHeaders() {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 22)
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(
                            testRecord(id = "p1", createdAt = millisAt(day0), isPinned = true),
                            testRecord(id = "d1", createdAt = millisAt(day1)),
                            testRecord(id = "d0", createdAt = millisAt(day0)),
                        ),
                    ),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.GROUP_HEADER_PINNED).assertIsDisplayed()
        composeRule.onNodeWithTag(InboxTestTags.dayHeaderTag(day1)).assertIsDisplayed()
        composeRule.onNodeWithTag(InboxTestTags.dayHeaderTag(day0)).assertIsDisplayed()
        // 每个组头都带条数（置顶 1 条 / 10月24日 1 条 / 10月22日 1 条）
        composeRule.onAllNodesWithText("1 条").assertCountEquals(3)
    }

    @Test
    fun InboxContent_collapsedDay_hidesThatDaysRecords() {
        val day1 = LocalDate.of(2026, 10, 24)
        val day0 = LocalDate.of(2026, 10, 22)
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        records = listOf(
                            testRecord(id = "d1", rawText = "折叠天的记录", createdAt = millisAt(day1)),
                            testRecord(id = "d0", rawText = "另一天的记录", createdAt = millisAt(day0)),
                        ),
                        collapsedDays = setOf(day1),
                    ),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.dayHeaderTag(day1)).assertIsDisplayed()
        composeRule.onNodeWithText("折叠天的记录").assertDoesNotExist()
        composeRule.onNodeWithText("另一天的记录").assertIsDisplayed()
    }

    @Test
    fun InboxContent_clickDayHeader_invokesToggleCallback() {
        val day1 = LocalDate.of(2026, 10, 24)
        var toggled: LocalDate? = null
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "d1", createdAt = millisAt(day1))),
                    ),
                    onRecordClick = {},
                    onToggleDayCollapsed = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.dayHeaderTag(day1)).performClick()

        assertEquals(day1, toggled)
    }

    @Test
    fun InboxContent_selectedDate_rendersChipAndClearInvokesCallback() {
        val day1 = LocalDate.of(2026, 10, 24)
        var cleared: LocalDate? = LocalDate.of(1999, 1, 1)
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "d1", createdAt = millisAt(day1))),
                        selectedDate = day1,
                    ),
                    onRecordClick = {},
                    onSelectDate = { cleared = it },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.FILTER_DATE).assertIsDisplayed()
        composeRule.onNodeWithTag(InboxTestTags.FILTER_DATE).performClick()

        assertNull("点 chip 即清除（回调收到 null）", cleared)
    }

    @Test
    fun InboxContent_dateFilteredEmpty_showsDateGuidance() {
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        records = emptyList(),
                        inboxTotal = 3,
                        selectedDate = LocalDate.of(2026, 10, 24),
                    ),
                    onRecordClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.FILTERED_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("这天没有可显示的记录").assertIsDisplayed()
    }

    @Test
    fun InboxContent_menuActions_invokeCallbacks() {
        var collapsedAll = false
        var expandedAll = false
        composeRule.setContent {
            AppTheme {
                InboxContent(
                    uiState = InboxUiState.Content(
                        listOf(testRecord(id = "r1", createdAt = 1_000L)),
                    ),
                    onRecordClick = {},
                    onCollapseAll = { collapsedAll = true },
                    onExpandAll = { expandedAll = true },
                )
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.MENU_MORE).performClick()
        composeRule.onNodeWithTag(InboxTestTags.MENU_COLLAPSE_ALL).performClick()
        assertTrue(collapsedAll)

        composeRule.onNodeWithTag(InboxTestTags.MENU_MORE).performClick()
        composeRule.onNodeWithTag(InboxTestTags.MENU_EXPAND_ALL).performClick()
        assertTrue(expandedAll)
    }

    @Test
    fun InboxContent_emptyState_hidesCalendarAndMenuActions() {
        composeRule.setContent {
            AppTheme {
                InboxContent(uiState = InboxUiState.Empty, onRecordClick = {})
            }
        }

        composeRule.onNodeWithTag(InboxTestTags.OPEN_CALENDAR).assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTestTags.MENU_MORE).assertDoesNotExist()
    }
}
