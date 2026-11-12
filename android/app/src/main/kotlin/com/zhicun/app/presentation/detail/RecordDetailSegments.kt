package com.zhicun.app.presentation.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhicun.app.presentation.common.ExpandableText
import com.zhicun.app.presentation.theme.RawTextStyle

/**
 * 按附件分段时的公共小件（OCR / 转写共用）。
 *
 * 分段的判据、标题文案各自留在 OCR 与语音文件里，这里只收拢"一张卡"的
 * 外观与"一段结果"的渲染顺序，避免两处各写一份后走样。
 */

/** 一段内容的卡片外壳：标题 + 8dp 间距 + 内容。 */
@Composable
internal fun SegmentSurface(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * 分段卡里的结果文字：有文字就展示（可选中、可展开），否则按顺序解释原因。
 *
 * 顺序与 [DerivedBlock] 一致：失败优先于"本来就为空"，"处理过但为空"优先于"还在处理"。
 * 单段失败不说整条记录失败 —— 多段场景下用户要知道是哪一段没成功。
 */
@Composable
internal fun SegmentedDerivedText(
    text: String,
    errorMessage: String?,
    failed: Boolean,
    pendingText: String,
    emptyText: String,
    failureText: String,
    testTag: String,
) {
    when {
        text.isNotEmpty() -> ExpandableText(
            text = text,
            stateKey = testTag,
            style = RawTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            testTag = testTag,
        )

        !errorMessage.isNullOrBlank() -> Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        failed -> Text(
            text = failureText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        // 有结果行但内容为空：这一段确实没有可识别的内容，重试也没用
        errorMessage != null -> Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Text(
            text = pendingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
