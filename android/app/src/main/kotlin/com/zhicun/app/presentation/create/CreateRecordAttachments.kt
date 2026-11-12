package com.zhicun.app.presentation.create

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.presentation.theme.StatusAmber

// 本文件承接从 CreateRecordScreen.kt 原样移出的附件区块与放弃确认框
// （AGENTS.md §2.1 单文件 400 行上限）。纯移动、零行为变更：
// 文案、testTag、Modifier 链、图形资源一字未改。

/**
 * 附件入口按钮（2026-10-24 改版）：图标放进带色软底圆盘、文字在下，整块可点。
 *
 * 旧版只有一枚裸图标 + 一行字，视觉上更像说明文字而不是按钮（用户反馈"连个按钮都不像"）。
 * 现在用「圆盘 + 卡片 + 阴影」三层视觉线索表达可点击，点击区域也随之内缩到整张卡片。
 */
@Composable
internal fun AttachmentButton(
    label: String,
    iconRes: Int,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = iconTint.copy(alpha = 0.12f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun AttachmentList(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 附件数量本来就少（一次分享/新建几个），用普通 Column 而不是 LazyColumn：
        // 外层已是可滚动的 Column，嵌套 Lazy 会因为高度无界直接崩
        attachments.forEachIndexed { index, attachment ->
            // 2026-10-24 改版：原来是一行粗体文字 + 「移除」文字按钮，像调试列表；
            // 现在做成软底胶囊：类型图标 + 名称 + 右侧关闭钮，一眼能看出是"待保存的附件"。
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(attachment.kind.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        // 优先显示原始文件名；拿不到才回退"图片 1 / 文件 2"
                        text = attachment.displayName ?: stringResource(
                            R.string.create_attachment_label,
                            stringResource(attachment.kind.labelRes()),
                            index + 1,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onRemove(attachment.uri) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.create_attachment_remove),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CreateRecordTestTags.DISCARD_DIALOG),
        title = { Text(text = stringResource(R.string.create_discard_title)) },
        text = { Text(text = stringResource(R.string.create_discard_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CreateRecordTestTags.DISCARD_CONFIRM),
            ) {
                Text(text = stringResource(R.string.create_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CreateRecordTestTags.DISCARD_CANCEL),
            ) {
                Text(text = stringResource(R.string.create_discard_cancel))
            }
        },
    )
}

/**
 * 语音入口（M21）：与"添加附件"在视觉上分开。
 *
 * 录音不是本条记录的附件，而是保存后会**变成另一条记录**；把它和图片/文件并排，
 * 用户很容易以为是"把录音加进当前这条"。这里用整行卡片 + 副标题把语义说清。
 */
@Composable
internal fun VoiceEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color = StatusAmber.copy(alpha = 0.12f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_type_voice),
                    contentDescription = null,
                    tint = StatusAmber,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.create_voice_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.create_voice_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** M21：有未保存内容时去录音，先确认会离开本页。 */
@Composable
internal fun VoiceNavigationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CreateRecordTestTags.VOICE_CONFIRM_DIALOG),
        title = { Text(text = stringResource(R.string.create_voice_confirm_title)) },
        text = { Text(text = stringResource(R.string.create_voice_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CreateRecordTestTags.VOICE_CONFIRM_GO),
            ) {
                Text(text = stringResource(R.string.create_voice_confirm_go))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CreateRecordTestTags.VOICE_CONFIRM_CANCEL),
            ) {
                Text(text = stringResource(R.string.create_voice_confirm_cancel))
            }
        },
    )
}

/** 附件类别的界面标签。 */
private fun AttachmentKind.labelRes(): Int = when (this) {
    AttachmentKind.IMAGE -> R.string.create_attachment_image
    AttachmentKind.FILE -> R.string.create_attachment_file
}

/** 附件类别的图标（与「添加附件」三个入口同一套图形）。 */
private fun AttachmentKind.iconRes(): Int = when (this) {
    AttachmentKind.IMAGE -> R.drawable.ic_type_image
    AttachmentKind.FILE -> R.drawable.ic_type_file
}