package com.zhicun.app.presentation.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import java.util.Locale

/** 顶部录音条 / 一次性提示条的测试与无障碍 tag。 */
object FloatingTestTags {
    const val RECORDING_BAR = "floating_recording_bar"
    const val RECORDING_TIME = "floating_recording_time"
}

/**
 * 顶部录音计时条（2026-10-26）。
 *
 * ## 为什么不画在气泡上
 * 气泡只有 48dp，且会被拖动、收起、贴边；把计时数字塞上去既看不清，也会与形态切换打架。
 * 因此录音状态与时长显示在**屏幕顶部的独立窗口**里，气泡只负责变色提示"正在录音"。
 *
 * 本 Composable 只负责画；窗口位置、触摸穿透、出现/消失时机由
 * `BubbleRecordingController` 负责（见 service 层）。
 *
 * @param recording true = 录音中（红点 + "录音中" + 计时）；false = 一次性提示（只显示 [message]）。
 * @param elapsedMs 录音已录时长（毫秒）。
 * @param message 一次性提示文案（录音中忽略）。
 */
@Composable
fun BubbleRecordingBar(
    recording: Boolean,
    elapsedMs: Long,
    message: String?,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(FloatingTestTags.RECORDING_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recording) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Text(
                text = stringResource(R.string.floating_recording_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(FloatingTestTags.RECORDING_TIME),
            )
        } else {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 毫秒 → 计时串（纯函数，可 JVM 单测）。
 *
 * 不足 1 小时为 `分:秒`（`00:07`），达到 1 小时及以上为 `时:分:秒`（`1:02:03`）
 * （spec §6.2-5）。固定用 [Locale.US] 生成数字，保证与设备区域无关、测试可断言。
 */
fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}