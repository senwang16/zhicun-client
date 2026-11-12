package com.zhicun.app.presentation.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Record
import com.zhicun.app.presentation.common.ExpandableText
import com.zhicun.app.presentation.theme.RawTextStyle

/**
 * 原文卡片与编辑态（2026-10-26）。
 *
 * 从 `RecordDetailSections.kt` 拆出（AGENTS.md §2.1 单文件 400 行上限）：
 * 装配顺序留在那边，这里只管"这块内容怎么展示、怎么改"。
 */

/**
 * 原文展示与编辑。
 *
 * 长文默认收起、点"展开全文"原地展开（2026-10-24 用户反馈）——
 * 通用逻辑在 [ExpandableText]，这里只负责卡片容器与空态。
 *
 * 编辑态**不做失焦取消**：原文可能是几千字，误点一下整段修改就没了，
 * 取消只走显式按钮或返回键（见 [RawTextEditor]）。
 * 保存后由 `UpdateRecordTextUseCase` 重新生成 AI 摘要/标签/向量，界面上要提前说清楚
 * （[R.string.detail_raw_edit_hint]），否则用户不知道为什么摘要忽然变了。
 */
@Composable
internal fun RawTextCard(
    record: Record,
    editState: DetailContentEditState,
    onStartEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onCopyText: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** 正文为空时的提示。合集上是"还没有合集说明"，普通记录是"没有原文"。 */
    emptyTextRes: Int = R.string.detail_no_content,
) {
    val text = record.rawText?.takeIf { it.isNotBlank() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (editState.editing) {
                RawTextEditor(
                    draft = editState.draft,
                    saving = editState.saving,
                    error = editState.error,
                    onDraftChange = onDraftChange,
                    onCancel = onCancelEdit,
                    onSave = onSave,
                )
            } else {
                if (text == null) {
                    Text(
                        text = stringResource(emptyTextRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExpandableText(
                        text = text,
                        stateKey = record.id,
                        style = RawTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        testTag = RecordDetailTestTags.RAW_TEXT,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!record.isDeleted) {
                        DetailPillButton(
                            label = stringResource(R.string.detail_raw_edit),
                            iconRes = R.drawable.ic_action_edit,
                            onClick = onStartEdit,
                            modifier = Modifier.testTag(RecordDetailTestTags.RAW_TEXT_EDIT_START),
                        )
                    }
                    if (text != null && onCopyText != null) {
                        CopyTextButton(
                            text = text,
                            onCopy = onCopyText,
                            testTag = RecordDetailTestTags.RAW_TEXT_COPY,
                        )
                    }
                }
            }
        }
    }
}

/** 原文编辑框：保存 / 取消 + 行内错误。进行中两个按钮都禁用，避免重复提交。 */
@Composable
private fun RawTextEditor(
    draft: String,
    saving: Boolean,
    error: DetailContentEditError?,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 返回键先退出原文编辑，而不是直接退出详情页（2026-10-26，与标签编辑一致）
    BackHandler { onCancel() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(RecordDetailTestTags.RAW_TEXT_EDIT),
            enabled = !saving,
            minLines = 4,
            maxLines = 12,
            textStyle = RawTextStyle,
            supportingText = {
                Text(
                    text = stringResource(R.string.detail_raw_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            isError = error != null,
        )
        if (error != null) {
            Text(
                text = rawEditorErrorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(RecordDetailTestTags.RAW_TEXT_ERROR),
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailPillButton(
                label = stringResource(
                    if (saving) R.string.detail_raw_saving else R.string.detail_raw_save,
                ),
                iconRes = R.drawable.ic_action_check,
                emphasized = true,
                enabled = !saving,
                onClick = onSave,
                modifier = Modifier.testTag(RecordDetailTestTags.RAW_TEXT_SAVE),
            )
            DetailPillButton(
                label = stringResource(R.string.detail_raw_cancel),
                iconRes = R.drawable.ic_close,
                enabled = !saving,
                onClick = onCancel,
                modifier = Modifier.testTag(RecordDetailTestTags.RAW_TEXT_CANCEL),
            )
        }
    }
}

@Composable
private fun rawEditorErrorText(error: DetailContentEditError): String = stringResource(
    when (error) {
        DetailContentEditError.Empty -> R.string.detail_raw_empty_error
        DetailContentEditError.Conflict -> R.string.detail_raw_conflict_error
        DetailContentEditError.Failed -> R.string.detail_raw_save_failed
    },
)

/**
 * 未完成状态的手动重新处理（2026-10-26）。
 *
 * 重试是空动作时（云端被挡 → `RetryProcessingUseCase` 一个任务都排不出来）
 * 换成设置入口，理由与 `DerivedBlock` 的 F-4 处理一致：给了按钮却什么都不发生，
 * 比没有按钮更让人以为 App 坏了。
 */
@Composable
internal fun ManualReprocessRow(
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (canRetry) {
                    R.string.detail_manual_retry_hint
                } else {
                    R.string.detail_manual_retry_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        when {
            canRetry -> DetailPillButton(
                label = stringResource(R.string.detail_manual_retry),
                iconRes = R.drawable.ic_action_refresh,
                onClick = onRetry,
                modifier = Modifier.testTag(RecordDetailTestTags.MANUAL_RETRY),
            )

            onOpenSettings != null -> DetailPillButton(
                label = stringResource(R.string.detail_open_cloud_settings),
                onClick = onOpenSettings,
                modifier = Modifier.testTag(RecordDetailTestTags.MANUAL_RETRY_SETTINGS),
            )
        }
    }
}
