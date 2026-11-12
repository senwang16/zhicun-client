package com.zhicun.app.presentation.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.presentation.common.DeleteRecordsDialog
import com.zhicun.app.presentation.onboarding.OnboardingViewModel
import java.time.LocalDate

object InboxTestTags {
    const val LIST = "inbox_list"
    const val EMPTY = "inbox_empty"
    const val LOADING = "inbox_loading"
    const val CREATE_FAB = "inbox_create_fab"

    /** M16：筛选与整理。 */
    const val FILTER_ALL = "inbox_filter_all"
    const val FILTER_FAVORITE = "inbox_filter_favorite"
    const val SELECT_ENTRY = "inbox_select_entry"
    const val SELECTION_BAR = "inbox_selection_bar"
    const val SELECTION_FAVORITE = "inbox_selection_favorite"
    const val SELECTION_ARCHIVE = "inbox_selection_archive"
    const val SELECTION_DELETE = "inbox_selection_delete"
    const val SELECTION_COLLECTION = "inbox_selection_collection"
    const val SELECTION_CANCEL = "inbox_selection_cancel"
    const val DELETE_DIALOG_CONFIRM = "inbox_delete_dialog_confirm"
    const val DELETE_DIALOG_CANCEL = "inbox_delete_dialog_cancel"
    const val FILTERED_EMPTY = "inbox_filtered_empty"
    const val SNACKBAR = "inbox_snackbar"

    /** 附录 T：悬浮窗引导横幅（可关闭）。 */
    const val BUBBLE_GUIDE_BANNER = "inbox_bubble_guide_banner"
    const val BUBBLE_GUIDE_DISMISS = "inbox_bubble_guide_dismiss"

    /** M19：按天分组折叠与日期筛选。 */
    const val GROUP_HEADER_PINNED = "inbox_group_header_pinned"
    const val DAY_HEADER_PREFIX = "inbox_day_header_"
    const val FILTER_DATE = "inbox_filter_date"
    const val OPEN_CALENDAR = "inbox_open_calendar"
    const val MENU_MORE = "inbox_menu_more"
    const val MENU_COLLAPSE_ALL = "inbox_menu_collapse_all"
    const val MENU_EXPAND_ALL = "inbox_menu_expand_all"
    const val CALENDAR_SHEET = "inbox_calendar_sheet"
    const val CALENDAR_DAY_PREFIX = "inbox_calendar_day_"
    const val CALENDAR_CLEAR = "inbox_calendar_clear"
    const val CALENDAR_PREV_MONTH = "inbox_calendar_prev_month"
    const val CALENDAR_NEXT_MONTH = "inbox_calendar_next_month"

    /** 日期组头的 tag（`inbox_day_header_` + ISO 日期，保证每天唯一）。 */
    fun dayHeaderTag(date: LocalDate): String = DAY_HEADER_PREFIX + date

    /** 日历格子的 tag（`inbox_calendar_day_` + ISO 日期）。 */
    fun calendarDayTag(date: LocalDate): String = CALENDAR_DAY_PREFIX + date
}

/**
 * 收件箱页面（有状态壳）。
 *
 * 只做三件事：拿 ViewModel、订阅 StateFlow、把回调转成 ViewModel 方法。
 * 真正的布局在 [InboxContent]，这样 UI 测试可以脱离 Hilt/ViewModel 直接喂状态
 * （AGENTS.md §5.2 不鼓励为了测 UI 去 mock 整个仓储）。
 *
 * 搜索/归档/设置入口自 2026-10 改版起由底部导航承载（见 [com.zhicun.app.presentation.navigation.AppBottomBar]），
 * 本页不再持有这三个入口。
 */
@Composable
fun InboxScreen(
    onRecordClick: (String) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 新建合集成功后跳进它的详情页（M20）；默认空实现便于不接导航的测试。 */
    onCollectionCreated: (String) -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    // 附录 T：悬浮窗引导横幅。判据（权限 + 关闭标记）在 OnboardingViewModel 里，
    // 与引导第 4 屏、自检区块同源 —— 这里只是它的另一个展示面。
    val bubbleGuideBanner by onboardingViewModel.bubbleGuideBanner.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        onboardingViewModel.refresh()
        onPauseOrDispose { }
    }
    InboxContent(
        uiState = uiState,
        selection = selection,
        feedback = feedback,
        showBubbleGuideBanner = bubbleGuideBanner,
        onDismissBubbleGuideBanner = onboardingViewModel::dismissBubbleGuideBanner,
        onOpenOverlaySettings = onboardingViewModel::openOverlaySettings,
        onRecordClick = onRecordClick,
        onCreateClick = onCreateClick,
        onCollectionCreated = onCollectionCreated,
        onToggleFilter = viewModel::toggleFilter,
        onEnterSelection = viewModel::enterSelectionMode,
        onLongPressRecord = viewModel::startSelection,
        onToggleSelected = viewModel::toggleSelected,
        onCancelSelection = viewModel::clearSelection,
        onFavoriteSelected = viewModel::favoriteSelected,
        onUnfavoriteSelected = viewModel::unfavoriteSelected,
        onArchiveSelected = viewModel::archiveSelected,
        onDeleteSelected = viewModel::deleteSelected,
        onCreateCollection = viewModel::createCollectionSelected,
        onFeedbackShown = viewModel::onFeedbackShown,
        onToggleDayCollapsed = viewModel::toggleDayCollapsed,
        onCollapseAll = viewModel::collapseAllDays,
        onExpandAll = viewModel::expandAllDays,
        onSelectDate = viewModel::selectDate,
        modifier = modifier,
    )
}

/**
 * 收件箱页面（无状态）。
 *
 * @param onRecordClick 点卡片回调，参数是 recordId —— 页面不认识路由，跳转由导航层决定。
 * @param onCreateClick 点"新建"悬浮按钮回调（M5）。默认空实现只为让"不关心新建入口"的 UI 测试
 *   不必传入；生产路径由 `AppNavGraph` 显式提供。
 * @param selection 多选状态（M16）。默认"未进入选择模式"，让 M3–M15 的测试不必改。
 * @param onToggleDayCollapsed 点日期组头折叠/展开该天（M19）。
 * @param onSelectDate 日历点选/清除日期筛选（M19）；null = 清除。
 * @param onCreateCollection 把勾选的记录合并为合集（M20）。
 * @param onCollectionCreated 合集创建成功后的跳转（参数是新合集 id）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxContent(
    uiState: InboxUiState,
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreateClick: () -> Unit = {},
    selection: InboxSelection = InboxSelection(),
    feedback: InboxFeedback? = null,
    onToggleFilter: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onLongPressRecord: (String) -> Unit = {},
    onToggleSelected: (String) -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onFavoriteSelected: () -> Unit = {},
    onUnfavoriteSelected: () -> Unit = {},
    onArchiveSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onCreateCollection: () -> Unit = {},
    onCollectionCreated: (String) -> Unit = {},
    onFeedbackShown: () -> Unit = {},
    showBubbleGuideBanner: Boolean = false,
    onDismissBubbleGuideBanner: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    onToggleDayCollapsed: (LocalDate) -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onExpandAll: () -> Unit = {},
    onSelectDate: (LocalDate?) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // 合集创建成功不弹提示：紧接着就跳进新合集，提示条会被页面切换吞掉。
    val snackbarMessage = when (feedback) {
        null, is InboxFeedback.CollectionCreated -> null
        else -> inboxFeedbackMessage(feedback)
    }
    LaunchedEffect(feedback) {
        when (val current = feedback) {
            null -> Unit
            is InboxFeedback.CollectionCreated -> {
                // 先导航再清事件：顺序反过来的话，重组可能先看到"无反馈"，
                // 而跳转只发生在这一次 LaunchedEffect 里
                onCollectionCreated(current.collectionId)
                onFeedbackShown()
            }

            else -> {
                val message = snackbarMessage ?: return@LaunchedEffect
                try {
                    snackbarHostState.showSnackbar(message)
                } finally {
                    // 无论提示是"显示完"还是"被下一条顶掉"，都要清掉一次性事件：
                    // 否则旋转屏幕后 ViewModel 里的旧值会被重新展示一遍
                    onFeedbackShown()
                }
            }
        }
    }

    // 选择模式下系统返回键 = 退出选择，而不是离开页面（2026-10-24 反馈）。
    // 不拦的话：收件箱是 NavHost 的起始目的地，返回会被一路 pop 到根 → 直接退出 App，
    // 用户看到的是"点了整理、按返回，应用没了"。归档箱同理。
    BackHandler(enabled = selection.active, onBack = onCancelSelection)

    // 删除确认框（2026-10-24）。用 rememberSaveable：旋转屏幕后不该把用户刚弹出的
    // 确认框悄悄收掉 —— 他正准备点"删除"，回来发现对话框没了会以为操作已经执行过。
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    // 退出选择模式就关掉确认框，避免它挂在一个空选择上（此时点删除在 ViewModel 里是空操作，
    // 看起来却像"点了没反应"）
    LaunchedEffect(selection.active) {
        if (!selection.active) showDeleteDialog = false
    }

    // 日历筛选弹层（M19）。rememberSaveable：旋转屏幕时"弹层开着"这一事实不该丢。
    // 只在 Content 下有意义（空状态没有可筛的天），无数据时即使残存 true 也不渲染。
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState) {
        if (uiState !is InboxUiState.Content) showCalendar = false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(InboxTestTags.SNACKBAR),
            )
        },
        floatingActionButton = {
            // 选择模式下新建按钮藏起来：此刻可操作的是"整理已选项"，新建按钮会挡住最后一张卡片。
            // 选够两条时换成「合并为合集」（M20）—— 它是多选模式专属动作，
            // 放进顶栏会和收藏/归档/删除挤成一排，窄屏上标题都放不下。
            // 合并是"多选之后才有意义"的动作，放在此刻唯一的主操作位上正好。
            when {
                selection.canMerge -> ExtendedFloatingActionButton(
                    onClick = onCreateCollection,
                    modifier = Modifier.testTag(InboxTestTags.SELECTION_COLLECTION),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(text = stringResource(R.string.inbox_selection_collection))
                }

                selection.active -> Unit

                // 空状态下也必须看得见：新用户第一次打开时，这个按钮就是唯一可点的东西
                else -> FloatingActionButton(
                    onClick = onCreateClick,
                    modifier = Modifier.testTag(InboxTestTags.CREATE_FAB),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        topBar = {
            if (selection.active) {
                // 收藏筛选视图里动作是"取消收藏"（2026-10-24 用户反馈）：
                // 用户正看着收藏列表，此时给他一个"收藏"按钮，点了没有任何可见变化
                //（记录本来就都是收藏），看起来像按钮坏了。label 与回调都随 filter 切换，
                // 且都从同一个 uiState 推导 —— 不会出现"文案是取消收藏、点的却是收藏"。
                val isFavoriteView = uiState is InboxUiState.Content &&
                    uiState.filter == InboxFilter.FAVORITE
                SelectionTopBar(
                    selection = selection,
                    favoriteLabel = stringResource(
                        if (isFavoriteView) {
                            R.string.inbox_selection_unfavorite
                        } else {
                            R.string.inbox_selection_favorite
                        },
                    ),
                    onCancel = onCancelSelection,
                    onFavorite = { if (isFavoriteView) onUnfavoriteSelected() else onFavoriteSelected() },
                    onArchive = onArchiveSelected,
                    onDelete = { showDeleteDialog = true },
                )
            } else {
                InboxTopBar(
                    uiState = uiState,
                    showListActions = uiState is InboxUiState.Content && uiState.records.isNotEmpty(),
                    onOpenCalendar = { showCalendar = true },
                    onCollapseAll = onCollapseAll,
                    onExpandAll = onExpandAll,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 附录 T：悬浮窗引导横幅。常驻可关（判据：未授权 && 未手动关闭），
            // 引导跳过的人从这条横幅找回入口 —— 它不随首启消失。
            if (showBubbleGuideBanner) {
                BubbleGuideBanner(
                    onOpenSettings = onOpenOverlaySettings,
                    onDismiss = onDismissBubbleGuideBanner,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState) {
                    InboxUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(InboxTestTags.LOADING),
                    )

                    InboxUiState.Empty -> InboxEmptyState(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is InboxUiState.Error -> Text(
                        text = stringResource(uiState.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )

                    is InboxUiState.Content -> Column(modifier = Modifier.fillMaxSize()) {
                        InboxFilterRow(
                            filter = uiState.filter,
                            showSelectEntry = uiState.records.isNotEmpty(),
                            onToggleFilter = onToggleFilter,
                            onEnterSelection = onEnterSelection,
                            // chip 文案与组头同一出处（今天/昨天/M月d日），
                            // 不出现"组头叫今天、chip 却写日期"的两种叫法
                            selectedDateLabel = uiState.selectedDate?.let { dayGroupTitle(it) },
                            onClearDateFilter = { onSelectDate(null) },
                        )
                        if (uiState.filteredToEmpty) {
                            FilteredEmptyState(
                                filter = uiState.filter,
                                dateFiltered = uiState.selectedDate != null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(InboxTestTags.FILTERED_EMPTY),
                            )
                        } else {
                            InboxList(
                                uiState = uiState,
                                selection = selection,
                                onRecordClick = onRecordClick,
                                onLongPressRecord = onLongPressRecord,
                                onToggleSelected = onToggleSelected,
                                onToggleDayCollapsed = onToggleDayCollapsed,
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认（2026-10-24）。放在 Scaffold 之后：AlertDialog 是独立窗口，
    // 与页面布局无关，但要能读到 selection.count 这段快照。
    if (showDeleteDialog) {
        DeleteRecordsDialog(
            count = selection.count,
            confirmTestTag = InboxTestTags.DELETE_DIALOG_CONFIRM,
            cancelTestTag = InboxTestTags.DELETE_DIALOG_CANCEL,
            onConfirm = {
                showDeleteDialog = false
                onDeleteSelected()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    // 日历筛选弹层（M19）。选完即关：选日期是"导航"不是"配置"，
    // 留着弹层反而挡住刚筛出来的结果。
    if (showCalendar && uiState is InboxUiState.Content) {
        InboxCalendarSheet(
            dayCounts = uiState.dayCounts,
            selectedDate = uiState.selectedDate,
            onDismiss = { showCalendar = false },
            onDateSelected = { date ->
                onSelectDate(date)
                showCalendar = false
            },
        )
    }
}
