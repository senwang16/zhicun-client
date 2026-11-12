package com.zhicun.app.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zhicun.app.presentation.theme.AppTheme

/** 设置页预览（从 SettingsScreen 拆出，纯展示用）。 */

@Preview(showBackground = true, name = "设置 · 气泡关闭")
@Composable
private fun SettingsFloatingOffPreview() {
    AppTheme {
        SettingsContent(
            uiState = SettingsUiState(
                floatingEnabled = false,
                hasOverlayPermission = false,
                cloudAiEnabled = true,
                versionName = "0.1.0",
                versionCode = 1,
            ),
            onBack = {},
            onFloatingToggled = {},
            onGrantPermission = {},
        )
    }
}

@Preview(showBackground = true, name = "设置 · 缺权限 + 云端已关")
@Composable
private fun SettingsNeedsPermissionPreview() {
    AppTheme {
        SettingsContent(
            uiState = SettingsUiState(
                floatingEnabled = true,
                hasOverlayPermission = false,
                cloudAiEnabled = false,
                versionName = "0.1.0",
                versionCode = 1,
            ),
            onBack = {},
            onFloatingToggled = {},
            onGrantPermission = {},
        )
    }
}
