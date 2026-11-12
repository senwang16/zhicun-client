package com.zhicun.app.presentation.detail

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 详情页内容区的胶囊操作按钮（2026-10-26 视觉整改）。
 *
 * 之前的"编辑/复制"是裸文字 TextButton，在卡片里和正文几乎分不开，看不出可点。
 * 统一成小胶囊：图标 + 标签 + 软底，按下轻微回弹；和系统操作 chip 的观感一致，
 * 同时保留 32dp 的紧凑高度，不抢正文的视觉重心。
 *
 * [emphasized] 用于主操作（保存、复制成功的确认态）：实心主色。
 * 图标只是辅助，标签始终保留 —— 纯图标按钮在中文界面里辨识度不够。
 */
@Composable
internal fun DetailPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "detail-pill-press",
    )
    val containerColor =
        if (emphasized) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (emphasized) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 32.dp)
            .scale(scale),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor.copy(alpha = if (enabled) 1f else 0.45f),
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
