package com.zhicun.app.presentation.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.presentation.theme.StatusAmber
import com.zhicun.app.presentation.theme.StatusAmberContainer
import com.zhicun.app.presentation.theme.StatusAmberDark
import com.zhicun.app.presentation.theme.StatusGreen
import com.zhicun.app.presentation.theme.StatusGreenContainer
import com.zhicun.app.presentation.theme.StatusGreenDark

/**
 * 处理状态徽标（"待处理 / 处理中 / 已完成 / 部分完成 / 失败 / 仅本地"）。
 *
 * 用文字 + 底色而不是纯色点：色盲用户分不清绿/琥珀/红，文字是唯一可靠的语义载体；
 * 颜色只作辅助强调（无障碍最低要求，见 PRD 非功能需求）。
 */
@Composable
fun ProcessingStatusBadge(
    status: ProcessingStatus,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ProcessingStatus.DONE -> {
            if (dark) StatusGreenDark.copy(alpha = 0.18f) to StatusGreenDark
            else StatusGreenContainer to StatusGreen
        }

        ProcessingStatus.PARTIAL -> {
            if (dark) StatusAmberDark.copy(alpha = 0.18f) to StatusAmberDark
            else StatusAmberContainer to StatusAmber
        }

        ProcessingStatus.FAILED -> scheme.errorContainer to scheme.error

        ProcessingStatus.PROCESSING, ProcessingStatus.PENDING -> {
            // 改版后"处理中"用琥珀而非品牌青绿：青绿只留给焦点（AI 区块/选中态），
            // 进行中的任务和"部分完成"同属琥珀语义
            if (dark) StatusAmberDark.copy(alpha = 0.18f) to StatusAmberDark
            else StatusAmberContainer to StatusAmber
        }

        ProcessingStatus.LOCAL_ONLY ->
            scheme.surfaceVariant to scheme.onSurfaceVariant
    }

    val label = stringResource(processingStatusLabelRes(status))

    Surface(
        modifier = modifier.semantics { contentDescription = label },
        shape = RoundedCornerShape(percent = 30),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}
