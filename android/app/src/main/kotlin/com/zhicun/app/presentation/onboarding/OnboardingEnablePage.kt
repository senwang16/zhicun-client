package com.zhicun.app.presentation.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhicun.app.R

// ---------------------------------------------------------------- 屏 4：开启 + 保护

@Composable
internal fun EnablePage(
    uiState: OnboardingUiState,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostartGuide: () -> Unit,
) {
    PageScaffold {
        Text(
            text = stringResource(R.string.onboarding_enable_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_enable_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        EnableCard(
            number = "1",
            title = stringResource(R.string.onboarding_enable_overlay_title),
            desc = stringResource(R.string.onboarding_enable_overlay_desc),
            done = uiState.overlayGranted,
            onClick = onOpenOverlaySettings,
            testTag = OnboardingTestTags.OVERLAY_CARD,
        )
        Spacer(modifier = Modifier.height(12.dp))
        EnableCard(
            number = "2",
            title = stringResource(R.string.onboarding_enable_keepalive_title),
            desc = stringResource(R.string.onboarding_enable_keepalive_desc),
            done = uiState.batteryWhitelisted,
            onClick = onOpenBatterySettings,
            testTag = OnboardingTestTags.KEEPALIVE_CARD,
        )
        // 自启动没有任何可程序化查询/跳转的公开 API（各厂商私有），首版如实引导：
        // 跳应用内「设置-后台常驻」自检区块，那里有按 ROM 写的说明（附录 T）。
        Text(
            text = stringResource(R.string.onboarding_enable_autostart_guide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenAutostartGuide)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag(OnboardingTestTags.AUTOSTART_GUIDE),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_enable_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "开启 + 保护"动作卡。完成态从系统判据派生（gateway 直读，不落 prefs）：
 * 用户在系统设置里授权后返回，ON_RESUME 复查自动变成"已完成"。
 */
@Composable
private fun EnableCard(
    number: String,
    title: String,
    desc: String,
    done: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (done) {
                    stringResource(R.string.onboarding_enable_done)
                } else {
                    stringResource(R.string.onboarding_enable_go)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}
