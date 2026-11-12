package com.zhicun.app.presentation.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R

/*
 * 详情页的动作组件（2026-10-29 R4 按钮化）。
 *
 * 三种形态按层级分工（设计稿见 docs/product/多附件识别与文件名需求与实现方案.md §8）：
 * - 图标按钮：打开附件这类高频、无需文字也看得懂的动作；
 * - ⋮ 溢出菜单：低频与破坏性动作（分享、删除），菜单项始终带文字；
 * - 胶囊按钮 [DetailPillButton]：需要文字才能说清的动作（复制、重试）。
 *
 * 破坏性动作从不在主界面裸露：附件行的「删除」只在菜单里且用 error 色，
 * 页面级的「删除记录」同理。
 */

/**
 * 图标动作按钮。
 *
 * 视觉是 34dp 圆角容器，外层留 48dp 触控区 —— Material 的最小可点尺寸，
 * 小屏与单手操作时不至于点偏。[contentDescription] 必填：纯图标没有文字兜底，
 * 无障碍只能靠它读出这是什么。
 */
@Composable
internal fun DetailIconAction(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(19.dp)
                    .alpha(if (enabled) 1f else 0.38f),
            )
        }
    }
}

/**
 * ⋮ 溢出菜单。
 *
 * 用下拉菜单而不是底部弹窗：详情页最多 3 项，底部弹窗在内容页上显得过重
 * （设计稿 §8.2 的菜单规格）。菜单项由调用方传，方便不同层级共用同一个 ⋮ 外观。
 */
@Composable
internal fun DetailOverflowMenu(
    contentDescription: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    entries: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        // tag 挂在带点击语义的图标按钮上，而不是外层 Box：
        // 测试的 performClick 需要节点自身有 click 动作
        DetailIconAction(
            iconRes = R.drawable.ic_more_vert,
            contentDescription = contentDescription,
            onClick = { expanded = true },
            modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries { expanded = false }
        }
    }
}

/** 菜单项：图标 + 文字，48dp 高；[danger] 用于删除，文字与图标统一用 error 色。 */
@Composable
internal fun DetailMenuItem(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    testTag: String? = null,
) {
    val color = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        text = { Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    )
}

/**
 * 二次确认框（不可恢复动作专用）。
 *
 * 与收件箱的 [com.zhicun.app.presentation.common.DeleteRecordsDialog] 同一套心智：
 * 确认按钮用 error 色、取消是默认颜色，让"不删"成为最容易点的那个。
 * 这里额外支持自定义标题与说明，因为"删一条记录"和"删一个附件"的后果不同，
 * 共用一句话会漏掉其中一种。
 */
@Composable
internal fun DetailConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmTestTag: String,
    cancelTestTag: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(confirmTestTag),
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
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
