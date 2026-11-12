package com.zhicun.app.presentation.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.SensitivityStatus

@Composable
internal fun InfoGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
internal fun InfoRow(
    labelRes: Int,
    value: String?,
    modifier: Modifier = Modifier,
    clickableUrl: Boolean = false,
) {
    InfoRowSlot(labelRes = labelRes, modifier = modifier) {
        InfoValue(value = value, clickableUrl = clickableUrl)
    }
}

/**
 * 右侧值。
 *
 * `value == null` 统一显示破折号（`detail_value_empty`），
 * 而不是让调用方各自拼 `?: "—"` —— 否则同一页面会出现半角/全角/emoji 三种占位符。
 */
@Composable
private fun InfoValue(
    value: String?,
    clickableUrl: Boolean,
    modifier: Modifier = Modifier,
) {
    if (value.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.detail_value_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    if (clickableUrl) {
        val uriHandler = LocalUriHandler.current
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            maxLines = 2,
            modifier = modifier.clickable { uriHandler.openUri(value) },
        )
    } else {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = modifier,
        )
    }
}

/** 右侧不是纯文本（如状态徽标）时用这个槽位。 */
@Composable
internal fun InfoRowSlot(
    labelRes: Int,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    InfoRowSlot(label = stringResource(labelRes), modifier = modifier, value = value)
}

@Composable
private fun InfoRowSlot(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                value()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 14.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

internal fun sensitivityLabelRes(status: SensitivityStatus): Int = when (status) {
    SensitivityStatus.UNKNOWN -> R.string.detail_sensitivity_unknown
    SensitivityStatus.SUSPECTED -> R.string.detail_sensitivity_suspected
    SensitivityStatus.CONFIRMED_SENSITIVE -> R.string.detail_sensitivity_confirmed
    SensitivityStatus.CLEAR -> R.string.detail_sensitivity_clear
}
