package com.zhicun.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R

/**
 * 数据：导出与清理（M15）。
 *
 * 两个动作都不可撤销（导出物可能外流、清理是物理删除），因此都先弹对话框，
 * 把"会发生什么"写清楚再让用户点确认。
 */
@Composable
internal fun DataSection(
    uiState: SettingsUiState,
    onExportConfirmed: (Boolean) -> Unit,
    onCleanupConfirmed: () -> Unit,
    onRetryShare: () -> Unit,
) {
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showCleanupDialog by rememberSaveable { mutableStateOf(false) }
    var includeDerived by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.settings_export_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = { showExportDialog = true },
        // 导出/清理进行中禁用：重复点会把同样的活干两遍，还会同时写两个大包
        enabled = !uiState.busy,
        modifier = Modifier.testTag(SettingsTestTags.EXPORT_BUTTON),
    ) {
        Text(text = stringResource(R.string.settings_export))
    }

    if (uiState.exportRunning) {
        if (uiState.hasDeterminateProgress) {
            LinearProgressIndicator(
                progress = { uiState.exportDone.toFloat() / uiState.exportTotal },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (uiState.hasDeterminateProgress) {
                stringResource(
                    R.string.settings_export_progress,
                    uiState.exportDone,
                    uiState.exportTotal,
                )
            } else {
                stringResource(R.string.settings_export_running)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SettingsTestTags.EXPORT_PROGRESS),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_cleanup_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = { showCleanupDialog = true },
        enabled = !uiState.busy,
        modifier = Modifier.testTag(SettingsTestTags.CLEANUP_BUTTON),
    ) {
        Text(text = stringResource(R.string.settings_cleanup))
    }

    if (uiState.cleanupRunning) {
        Text(
            text = stringResource(R.string.settings_cleanup_running),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 上一次操作的结论：带数字，让用户知道"到底导了多少、清了多少"
    uiState.notice?.let { notice ->
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.NOTICE),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = noticeText(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp),
            )
        }
        // 分享面板没打开时的出口（M16）：包已经生成在私有缓存里，用户自己翻不出来，
        // 不给这个按钮等于告诉他"导出完成"却拿不到东西
        if (notice is SettingsNotice.ExportNotShared && uiState.canRetryShare) {
            TextButton(
                onClick = onRetryShare,
                modifier = Modifier.testTag(SettingsTestTags.RETRY_SHARE),
            ) {
                Text(text = stringResource(R.string.settings_export_retry_share))
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            modifier = Modifier.testTag(SettingsTestTags.EXPORT_DIALOG),
            onDismissRequest = { showExportDialog = false },
            title = { Text(text = stringResource(R.string.settings_export_dialog_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.settings_export_dialog_body))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = includeDerived,
                            onCheckedChange = { includeDerived = it },
                            modifier = Modifier.testTag(SettingsTestTags.EXPORT_INCLUDE_DERIVED),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_export_include_derived),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        onExportConfirmed(includeDerived)
                    },
                    modifier = Modifier.testTag(SettingsTestTags.EXPORT_CONFIRM),
                ) {
                    Text(text = stringResource(R.string.settings_export_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(text = stringResource(R.string.settings_dialog_cancel))
                }
            },
        )
    }

    if (showCleanupDialog) {
        AlertDialog(
            modifier = Modifier.testTag(SettingsTestTags.CLEANUP_DIALOG),
            onDismissRequest = { showCleanupDialog = false },
            title = { Text(text = stringResource(R.string.settings_cleanup_dialog_title)) },
            text = { Text(text = stringResource(R.string.settings_cleanup_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupDialog = false
                        onCleanupConfirmed()
                    },
                    modifier = Modifier.testTag(SettingsTestTags.CLEANUP_CONFIRM),
                ) {
                    Text(text = stringResource(R.string.settings_cleanup_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) {
                    Text(text = stringResource(R.string.settings_dialog_cancel))
                }
            },
        )
    }
}

/** 把 [SettingsNotice] 翻成一句人话。带数字的情况一定把数字带上。 */
@Composable
private fun noticeText(notice: SettingsNotice): String = when (notice) {
    is SettingsNotice.ExportDone -> if (notice.missingAssets > 0) {
        stringResource(
            R.string.settings_export_done_with_missing,
            notice.records,
            notice.assets,
            notice.missingAssets,
        )
    } else {
        stringResource(R.string.settings_export_done, notice.records, notice.assets)
    }

    is SettingsNotice.ExportFailed -> stringResource(R.string.settings_export_failed)

    is SettingsNotice.ExportNotShared -> if (notice.missingAssets > 0) {
        stringResource(
            R.string.settings_export_not_shared_with_missing,
            notice.records,
            notice.assets,
            notice.missingAssets,
        )
    } else {
        stringResource(
            R.string.settings_export_not_shared,
            notice.records,
            notice.assets,
        )
    }

    is SettingsNotice.CleanupDone -> if (notice.failedFiles > 0) {
        stringResource(R.string.settings_cleanup_failed, notice.failedFiles)
    } else {
        stringResource(R.string.settings_cleanup_done, notice.records, notice.assets)
    }

    SettingsNotice.CleanupNothing -> stringResource(R.string.settings_cleanup_none)

    SettingsNotice.CleanupFailed -> stringResource(R.string.settings_cleanup_error)
}
