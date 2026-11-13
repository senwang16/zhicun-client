package com.zhicun.app.presentation.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.presentation.common.DeleteRecordsDialog
import com.zhicun.app.presentation.common.RecordCard

/** 测试与无障碍定位用的 tag。 */
object ArchiveTestTags {
    const val LIST = "archive_list"
    const val EMPTY = "archive_empty"
    const val LOADING = "archive_loading"

    /** 归档箱内搜索（2026-10-24）。 */
    const val SEARCH_FIELD = "archive_search_field"
    const val SEARCH_CLEAR = "archive_search_clear"
    const val SEARCH_EMPTY = "archive_search_empty"
    const val SELECT_ENTRY = "archive_select_entry"
    const val SELECTION_BAR = "archive_selection_bar"
    const val SELECTION_RESTORE = "archive_selection_restore"
    const val SELECTION_DELETE = "archive_selection_delete"
    const val SELECTION_CANCEL = "archive_selection_cancel"
    const val DELETE_DIALOG_CONFIRM = "archive_delete_dialog_confirm"
    const val DELETE_DIALOG_CANCEL = "archive_delete_dialog_cancel"
    const val SNACKBAR = "archive_snackbar"
}

/**
 * 归档箱（有状态壳）。
 *
 * 归档不等于删除：数据、附件、派生内容都不动，导出包里也包含，取消归档即恢复。
 * 但归档后会退出检索（全文搜索与相似推荐都看不到），见 `Record.archivedAt` 的注释。
 *
 * 这个页面有两个动作：恢复（放回收件箱）和删除（真的不要了）。
 */
@Composable
fun ArchiveScreen(
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    ArchiveContent(
        uiState = uiState,
        onRecordClick = onRecordClick,
        selection = selection,
        feedback = feedback,
        query = query,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onEnterSelection = viewModel::enterSelectionMode,
        onLongPressRecord = viewModel::startSelection,
        onToggleSelected = viewModel::toggleSelected,
        onCancelSelection = viewModel::clearSelection,
        onRestoreSelected = viewModel::restoreSelected,
        onDeleteSelected = viewModel::deleteSelected,
        onFeedbackShown = viewModel::onFeedbackShown,
        modifier = modifier,
    )
}

/**
 * 归档箱（无状态）。
 *
 * 所有回调都给了默认空实现：让只关心"某状态下渲染出什么"的 UI 测试
 * 不必写一串 `{}`（同 `InboxContent` 的约定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveContent(
    uiState: ArchiveUiState,
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    selection: ArchiveSelection = ArchiveSelection(),
    feedback: ArchiveFeedback? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onClearQuery: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onLongPressRecord: (String) -> Unit = {},
    onToggleSelected: (String) -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onRestoreSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onFeedbackShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // 文案在组合期算好：LaunchedEffect 里不能调 @Composable 的 stringResource
    val snackbarMessage = feedback?.let { archiveFeedbackMessage(it) }
    LaunchedEffect(feedback) {
        if (snackbarMessage == null) return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(snackbarMessage)
        } finally {
            // 显示完或被顶掉都要清一次性事件，否则旋转屏幕会重放
            onFeedbackShown()
        }
    }

    // 选择模式下返回键先退出选择（2026-10-24 反馈，与收件箱同一处缺陷）：
    // 否则返回会直接把归档箱 pop 掉，勾选状态和页面一起消失。
    BackHandler(enabled = selection.active, onBack = onCancelSelection)

    // 删除确认框（2026-10-24）。理由同收件箱：删除不可撤销，风险必须在动手前确认。
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selection.active) {
        if (!selection.active) showDeleteDialog = false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(ArchiveTestTags.SNACKBAR),
            )
        },
        topBar = {
            ArchiveTopBar(
                selection = selection,
                uiState = uiState,
                onCancelSelection = onCancelSelection,
                onRestoreSelected = onRestoreSelected,
                onDeleteClick = { showDeleteDialog = true },
                onEnterSelection = onEnterSelection,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 归档箱内搜索（2026-10-24）：归档退出了主搜索，这里必须给一个找回的入口。
            // 常驻：搜索中选择模式也可见 —— 用户可能正要找出某一条归档记录去删。
            ArchiveSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState) {
                    ArchiveUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(ArchiveTestTags.LOADING),
                    )

                    ArchiveUiState.Empty -> ArchiveEmptyState(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(ArchiveTestTags.EMPTY),
                    )

                    is ArchiveUiState.Error -> Text(
                        text = stringResource(uiState.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )

                    is ArchiveUiState.Content -> if (uiState.records.isEmpty() && uiState.query.isNotEmpty()) {
                        // 搜索没命中：与"归档箱是空的"分开说 —— 混用一句话，
                        // 用户会以为搜到的空等于没存过东西
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                                .padding(horizontal = 40.dp)
                                .testTag(ArchiveTestTags.SEARCH_EMPTY),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.archive_search_no_results_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.archive_search_no_results_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(ArchiveTestTags.LIST),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // key 必填（AGENTS.md §8）：恢复一条记录后其余行若没有 key 会整列重组
                            items(items = uiState.records, key = { it.id }) { record ->
                                RecordCard(
                                    record = record,
                                    tags = uiState.tagsByRecord[record.id].orEmpty(),
                                    selected = record.id in selection.selectedIds,
                                    // 归档卡片弱化（2026-10 改版）：底色退到页面底，提示"不常用"
                                    dimmed = !selection.active,
                                    onClick = {
                                        if (selection.active) {
                                            onToggleSelected(record.id)
                                        } else {
                                            onRecordClick(record.id)
                                        }
                                    },
                                    onLongClick = { onLongPressRecord(record.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认（2026-10-24）。文案与收件箱共用 —— 同一个动作不该有两种说法。
    if (showDeleteDialog) {
        DeleteRecordsDialog(
            count = selection.count,
            confirmTestTag = ArchiveTestTags.DELETE_DIALOG_CONFIRM,
            cancelTestTag = ArchiveTestTags.DELETE_DIALOG_CANCEL,
            onConfirm = {
                showDeleteDialog = false
                onDeleteSelected()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}
