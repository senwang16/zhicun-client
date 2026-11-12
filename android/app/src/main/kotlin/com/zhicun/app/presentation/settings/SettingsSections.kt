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
 * 设置页的六个分组（从 SettingsScreen 拆出，2026-10-22 审核 P2：单文件超 400 行）。
 *
 * 全部 `internal`：只被本包的 SettingsContent 装配，不外泄。
 * 排版与文案保持原样——这是纯拆分，不是重设计。
 */

@Composable
internal fun FloatingBubbleSection(
    uiState: SettingsUiState,
    onFloatingToggled: (Boolean) -> Unit,
    onAutoClipboardToggled: (Boolean) -> Unit,
    onAutoRestoreToggled: (Boolean) -> Unit,
    onAlwaysVisibleToggled: (Boolean) -> Unit,
    onLongPressRecordToggled: (Boolean) -> Unit,
    onGrantPermission: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_floating_switch),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_floating_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = uiState.floatingEnabled,
            onCheckedChange = onFloatingToggled,
            // 文案已由左边的标题承担，开关自身不再重复播报
            modifier = Modifier.testTag(SettingsTestTags.FLOATING_SWITCH),
        )
    }

    // 子开关（2026-10-24）：开机 / 覆盖安装后要不要自动把气泡挂回来。
    // 放在主开关正下方，因为它是"气泡的持续性"而不是"气泡的行为"，与主开关同属一条决策链。
    SubSwitchRow(
        label = stringResource(R.string.settings_floating_auto_restore),
        hint = stringResource(R.string.settings_floating_auto_restore_hint),
        checked = uiState.autoRestoreOnBoot,
        enabled = uiState.floatingEnabled,
        tag = SettingsTestTags.AUTO_RESTORE_SWITCH,
        onCheckedChange = onAutoRestoreToggled,
    )

    // 子开关（2026-10-26「悬浮窗常在」）：开启后气泡不再因外部触摸收成贴边细条。
    SubSwitchRow(
        label = stringResource(R.string.settings_floating_always_visible),
        hint = stringResource(R.string.settings_floating_always_visible_hint),
        checked = uiState.alwaysVisible,
        enabled = uiState.floatingEnabled,
        tag = SettingsTestTags.ALWAYS_VISIBLE_SWITCH,
        onCheckedChange = onAlwaysVisibleToggled,
    )

    // 子开关（2026-10-26「长按录音」）：在完整气泡上长按即录音、抬手保存。
    SubSwitchRow(
        label = stringResource(R.string.settings_floating_long_press_record),
        hint = stringResource(R.string.settings_floating_long_press_record_hint),
        checked = uiState.longPressRecord,
        enabled = uiState.floatingEnabled,
        tag = SettingsTestTags.LONG_PRESS_RECORD_SWITCH,
        onCheckedChange = onLongPressRecordToggled,
    )

    // 子开关（B2）：点气泡自动收录剪贴板。悬浮本身没开时它是死配置，置灰而不是藏起来，
    // 否则用户开了悬浮再回来找会以为功能丢了。
    SubSwitchRow(
        label = stringResource(R.string.settings_floating_auto_clipboard),
        hint = stringResource(R.string.settings_floating_auto_clipboard_hint),
        checked = uiState.autoClipboardEnabled,
        enabled = uiState.floatingEnabled,
        tag = SettingsTestTags.AUTO_CLIPBOARD_SWITCH,
        onCheckedChange = onAutoClipboardToggled,
    )

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(
            if (uiState.hasOverlayPermission) {
                R.string.settings_floating_permission_granted
            } else {
                R.string.settings_floating_permission_denied
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = if (uiState.hasOverlayPermission) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.testTag(SettingsTestTags.FLOATING_PERMISSION),
    )

    // 只有"用户想要 + 系统不给"时才打断一下。
    // 开关本来就关着的时候提示授权，属于用户没问就答。
    if (uiState.needsPermission) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_floating_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SettingsTestTags.FLOATING_BLOCKED),
        )
        TextButton(
            onClick = onGrantPermission,
            modifier = Modifier.testTag(SettingsTestTags.FLOATING_GRANT),
        ) {
            Text(text = stringResource(R.string.settings_floating_grant))
        }
    }
}

/**
 * 悬浮窗卡片里的二级开关行（标题 + 说明 + 右侧开关）。
 *
 * 抽出来是因为这里已经是第三个同款行了（开机自动恢复、自动收录剪贴板、将来还会有别的）。
 * 三份复制粘贴的排版迟早会漂成三种间距。
 *
 * @param enabled 主开关关着时置灰**而不是隐藏**：藏起来会让用户开了悬浮再回来找，
 *   以为功能丢了。置灰 + 主开关可见，才说明白"它是依附于上面那个开关的"。
 */
@Composable
private fun SubSwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
internal fun CloudAiSection(
    uiState: SettingsUiState,
    onCloudAiToggled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_cloud_ai_switch),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_cloud_ai_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = uiState.cloudAiEnabled,
            onCheckedChange = onCloudAiToggled,
            modifier = Modifier.testTag(SettingsTestTags.CLOUD_SWITCH),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    // 关掉时要把"什么时候生效"和"什么仍然可用"说清楚：
    // 用户最担心的是"我关掉之后是不是连搜索都不能用了"，事实并非如此。
    if (!uiState.cloudAiEnabled) {
        Text(
            text = stringResource(R.string.settings_cloud_ai_off_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SettingsTestTags.CLOUD_OFF_NOTE),
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Text(
            text = stringResource(R.string.settings_cloud_ai_on_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Text(
        text = stringResource(R.string.settings_cloud_ai_sensitive_note),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun TranscriptionSection(uiState: SettingsUiState) {
    Text(
        text = stringResource(R.string.settings_transcription_follow),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(
            if (uiState.transcriptionAvailable) {
                R.string.settings_transcription_available
            } else {
                R.string.settings_transcription_unavailable
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = if (uiState.transcriptionAvailable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag(SettingsTestTags.TRANSCRIPTION_STATUS),
    )
}

@Composable
internal fun PrivacySection() {
    // 折叠状态属于"这一屏的临时展示状态"，不需要进 ViewModel；
    // 但旋转屏幕后不该自己弹回去，所以用 rememberSaveable。
    var expanded by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.settings_privacy_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (expanded) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_privacy_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SettingsTestTags.PRIVACY_DETAIL),
        )
    }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.testTag(SettingsTestTags.PRIVACY_TOGGLE),
    ) {
        Text(
            text = stringResource(
                if (expanded) R.string.settings_privacy_collapse else R.string.settings_privacy_expand,
            ),
        )
    }
}

@Composable
internal fun AboutSection(
    uiState: SettingsUiState,
    onFeedbackClick: () -> Unit,
) {
    if (uiState.hasVersion) {
        Text(
            text = stringResource(
                R.string.settings_version,
                uiState.versionName,
                uiState.versionCode,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SettingsTestTags.VERSION),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Text(
        text = stringResource(R.string.settings_feedback_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = onFeedbackClick,
        modifier = Modifier.testTag(SettingsTestTags.FEEDBACK),
    ) {
        Text(text = stringResource(R.string.settings_feedback))
    }
}
