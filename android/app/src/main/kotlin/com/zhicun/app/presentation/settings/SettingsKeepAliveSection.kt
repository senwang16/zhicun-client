package com.zhicun.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.floating.KeepAliveStatus

// 本文件承接从 SettingsSections.kt 原样移出的「后台常驻自检」区块
// （AGENTS.md §2.1 单文件 400 行上限）。纯移动、零行为变更：
// 文案、判据、testTag、Modifier 链一字未改。

/**
 * 后台常驻自检（2026-10-24）。
 *
 * 用户看到的现象是"用了两天，悬浮球自己没了"，而界面上原本没有任何线索。
 * 这里把三项系统条件摆出来，每一行只在**不满足**时标红并给出设置入口 ——
 * 一屏都是绿字没有任何价值，用户要的是"哪里不对、点哪里去改"。
 *
 * 分段线而不是新卡片：它属于"悬浮入口"这张卡的后续说明，
 * 分成两张卡会让用户以为是两个独立功能。
 *
 * 开头的"桌面小组件 + 启动管理三项"是用户实测出来的最有效组合（2026-10-24）：
 * 有桌面组件时系统会把本应用当常驻，最近任务上滑不再清掉气泡。放在自检行之前，
 * 因为它是"怎么才能不被清掉"，而下面的自检只回答"当前哪项条件没满足"。
 *
 * @param autoRestoreOnBoot 只用来选开头那句说明。原文案写死"重启手机或更新应用后会自动恢复"，
 *   用户关掉那个开关后它就成了假话 —— 文案必须跟着真值走，不能让界面自己骗自己。
 */
@Composable
internal fun KeepAliveSection(
    status: KeepAliveStatus,
    autoRestoreOnBoot: Boolean,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.settings_keepalive_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.KEEP_ALIVE_WIDGET_TIP),
    ) {
        Text(
            text = stringResource(R.string.settings_keepalive_widget_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(
            if (autoRestoreOnBoot) {
                R.string.settings_keepalive_hint
            } else {
                R.string.settings_keepalive_hint_no_restore
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    KeepAliveRow(
        label = stringResource(R.string.settings_keepalive_battery),
        value = stringResource(
            if (status.needsBatteryWhitelist) {
                R.string.settings_keepalive_battery_blocked
            } else {
                R.string.settings_keepalive_battery_ok
            },
        ),
        warn = status.needsBatteryWhitelist,
        rowTag = SettingsTestTags.KEEP_ALIVE_BATTERY,
        actionTag = SettingsTestTags.KEEP_ALIVE_BATTERY_ACTION,
        // 只在真的有问题时才给入口：一切正常还摆一个"去设置"，用户会以为点进去能优化什么
        onAction = if (status.needsBatteryWhitelist) onOpenBatterySettings else null,
    )

    KeepAliveRow(
        label = stringResource(R.string.settings_keepalive_notifications),
        value = stringResource(
            if (status.notificationsOff) {
                R.string.settings_keepalive_notifications_blocked
            } else {
                R.string.settings_keepalive_notifications_ok
            },
        ),
        warn = status.notificationsOff,
        rowTag = SettingsTestTags.KEEP_ALIVE_NOTIFICATIONS,
        actionTag = SettingsTestTags.KEEP_ALIVE_NOTIFICATION_ACTION,
        onAction = if (status.notificationsOff) onOpenNotificationSettings else null,
    )

    // 查不到分桶的版本（API 28 以下）整行不显示：显示"正常"是假消息，
    // 显示"未知"又只会让用户以为哪里坏了
    if (status.standbyBucket.isKnown) {
        KeepAliveRow(
            label = stringResource(R.string.settings_keepalive_standby),
            value = stringResource(
                if (status.standbyRestricted) {
                    R.string.settings_keepalive_standby_restricted
                } else {
                    R.string.settings_keepalive_standby_ok
                },
            ),
            warn = status.standbyRestricted,
            rowTag = SettingsTestTags.KEEP_ALIVE_STANDBY,
        )
    }
}

/**
 * 一行"项目 + 状态 +（可选）去设置"。
 *
 * @param onAction 为 null 时不显示按钮。只在真的有问题时才给入口 ——
 *   一切正常还摆一个"去设置"，用户会以为点进去能优化什么。
 */
@Composable
private fun KeepAliveRow(
    label: String,
    value: String,
    warn: Boolean,
    rowTag: String,
    modifier: Modifier = Modifier,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(rowTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onAction,
                modifier = actionTag?.let { Modifier.testTag(it) } ?: Modifier,
            ) {
                Text(text = stringResource(R.string.settings_keepalive_open_settings))
            }
        }
    }
}