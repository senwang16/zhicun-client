package com.zhicun.app.presentation.archive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.presentation.theme.AppTheme

// 本文件承接从 ArchiveScreen.kt 原样移出的归档箱子组件与预览
// （AGENTS.md §2.1 单文件 400 行上限）。纯移动、零行为变更：
// Modifier 链、testTag、回调语义与文案一字未改。

/**
 * 归档箱顶栏：选择模式下是"X + 计数 + 恢复/删除"，否则是标题 + 整理入口。
 *
 * 原样抽自 `ArchiveContent` 的 `topBar` 槽位 —— 之所以能安全抽出，
 * 是因为这段 UI 不持有任何 `remember` 状态：唯一的交互是
 * "点删除 → 由父级把确认框置为可见"，所以用 [onDeleteClick] 把该意图交回去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveTopBar(
    selection: ArchiveSelection,
    uiState: ArchiveUiState,
    onCancelSelection: () -> Unit,
    onRestoreSelected: () -> Unit,
    onDeleteClick: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    if (selection.active) {
        // X 图标 + 标题居中（2026-10-24 U-11）：与收件箱选择模式同款
        CenterAlignedTopAppBar(
            title = {
                Text(text = stringResource(R.string.archive_selection_count, selection.count))
            },
            modifier = Modifier.testTag(ArchiveTestTags.SELECTION_BAR),
            navigationIcon = {
                IconButton(
                    onClick = onCancelSelection,
                    modifier = Modifier.testTag(ArchiveTestTags.SELECTION_CANCEL),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.inbox_selection_cancel),
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            actions = {
                // 主动作用实底（同收件箱选择栏，2026-10-24 用户反馈“整理像没设计”）：
                // 归档箱里“恢复”是主要动作，纯文字按钮视觉重量不够。
                FilledTonalButton(
                    onClick = onRestoreSelected,
                    enabled = selection.canAct,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.testTag(ArchiveTestTags.SELECTION_RESTORE),
                ) {
                    Text(text = stringResource(R.string.archive_selection_restore))
                }
                // 删除保持文字按钮 + 错误色：它是不可恢复的那一个，
                // 重按钮会给破坏性动作煽风。
                TextButton(
                    onClick = onDeleteClick,
                    enabled = selection.canAct,
                    modifier = Modifier.testTag(ArchiveTestTags.SELECTION_DELETE),
                ) {
                    Text(
                        text = stringResource(R.string.inbox_selection_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    } else {
        TopAppBar(
            title = {
                Column {
                    Text(text = stringResource(R.string.archive_title))
                    if (uiState is ArchiveUiState.Content) {
                        Text(
                            text = stringResource(
                                R.string.archive_content_description,
                                uiState.records.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            // 没有返回按钮（2026-10-24 U-11）：归档箱是底导 tab，没有返回语义，
            // 与搜索页 U-7 同理
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            actions = {
                if (uiState is ArchiveUiState.Content && uiState.records.isNotEmpty()) {
                    // 整理入口胶囊（同收件箱，2026-10-24 用户反馈）：模式切换动作要有视觉重量
                    Surface(
                        onClick = onEnterSelection,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag(ArchiveTestTags.SELECT_ENTRY),
                    ) {
                        Text(
                            text = stringResource(R.string.inbox_select_entry),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            },
        )
    }
}

/**
 * 归档箱内搜索框（2026-10-24）：归档退出了主搜索，这里必须给一个找回的入口。
 * 常驻：搜索中选择模式也可见 —— 用户可能正要找出某一条归档记录去删。
 */
@Composable
internal fun ArchiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ArchiveTestTags.SEARCH_FIELD),
        placeholder = {
            Text(
                text = stringResource(R.string.archive_search_hint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = onClearQuery,
                    modifier = Modifier.testTag(ArchiveTestTags.SEARCH_CLEAR),
                ) {
                    Text(text = stringResource(R.string.search_clear))
                }
            }
        },
    )
}

@Composable
internal fun ArchiveEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.archive_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.archive_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一次性反馈 → 文案（文案属于表现层，ViewModel 只带数据）。 */
@Composable
internal fun archiveFeedbackMessage(feedback: ArchiveFeedback): String = when (feedback) {
    is ArchiveFeedback.Restored -> stringResource(R.string.archive_restored_toast, feedback.count)
    ArchiveFeedback.RestoreFailed -> stringResource(R.string.archive_restore_failed)
    // 删除的三档结果与收件箱共用文案：同一个动作在任何页面都该说同一句话
    is ArchiveFeedback.Deleted -> when {
        feedback.incomplete == 0 -> stringResource(R.string.inbox_delete_done, feedback.count)
        feedback.count == 0 ->
            stringResource(R.string.inbox_delete_none_done, feedback.incomplete)

        else -> stringResource(
            R.string.inbox_delete_partial,
            feedback.count,
            feedback.incomplete,
        )
    }

    ArchiveFeedback.DeleteFailed -> stringResource(R.string.inbox_delete_failed)
}

@Preview(showBackground = true, name = "归档箱 · 空状态")
@Composable
private fun ArchiveEmptyPreview() {
    AppTheme {
        ArchiveContent(uiState = ArchiveUiState.Empty, onRecordClick = {})
    }
}

@Preview(showBackground = true, name = "归档箱 · 有数据")
@Composable
private fun ArchiveContentPreview() {
    AppTheme {
        ArchiveContent(
            uiState = ArchiveUiState.Content(
                records = listOf(
                    Record(
                        id = "archived-1",
                        type = RecordType.TEXT_NOTE,
                        captureSource = CaptureSource.MANUAL,
                        rawText = "上个月的会议记录，已经整理进周报了",
                        sourceUrl = null,
                        sourceTitle = null,
                        sourceApp = null,
                        createdAt = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L,
                        localStatus = LocalStatus.SAVED,
                        processingStatus = ProcessingStatus.DONE,
                        sensitivityStatus = SensitivityStatus.CLEAR,
                        recordRevision = 1,
                        isDeleted = false,
                        archivedAt = 1_700_100_000_000L,
                    ),
                ),
            ),
            onRecordClick = {},
        )
    }
}