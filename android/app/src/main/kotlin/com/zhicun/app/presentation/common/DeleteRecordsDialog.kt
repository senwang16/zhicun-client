package com.zhicun.app.presentation.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.zhicun.app.R

/**
 * 删除确认框（2026-10-24）。
 *
 * 收件箱与归档箱共用一份：`不可恢复` 这个动作不该在两个页面有两种说法，
 * 也不该出现"一处改了文案另一处忘了改"。差异只有测试 tag。
 *
 * ## 为什么是"确认后直接删"，而不是删完给撤销
 * 用户明确选了这个方向：删除是终态动作，把风险放在动手之前，比"删了之后再给 4 秒后悔"
 * 更符合"删就是删"的心智。代价是误点确认无从挽回 —— 所以正文必须把删掉什么写清楚。
 *
 * ## 为什么正文要点名"附件和 AI 生成的内容"
 * 用户勾选时看到的只是标题和摘要，很容易以为删的是"这张卡片"。
 * 附件（可能几十 MB 的录音/PDF）和派生内容（摘要、OCR、标签、向量）都是真实数据，
 * 不写清楚就等于在用户不知情的情况下多删了东西。
 *
 * ## 为什么"取消"不是错误色、且放在 dismissButton
 * 这一步不可恢复，所以让"不删"成为视觉上最容易点的那个。
 *
 * @param count 待删除条数，用于标题（1 条说"这条"，多条说"N 条"）
 */
@Composable
fun DeleteRecordsDialog(
    count: Int,
    confirmTestTag: String,
    cancelTestTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (count == 1) {
                    stringResource(R.string.inbox_delete_confirm_title_one)
                } else {
                    stringResource(R.string.inbox_delete_confirm_title_many, count)
                },
            )
        },
        text = { Text(text = stringResource(R.string.inbox_delete_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(confirmTestTag),
            ) {
                Text(
                    text = stringResource(R.string.inbox_delete_confirm_ok),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(cancelTestTag),
            ) {
                Text(text = stringResource(R.string.inbox_selection_cancel))
            }
        },
    )
}
