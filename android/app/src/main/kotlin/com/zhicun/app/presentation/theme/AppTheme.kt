package com.zhicun.app.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Neutral0,
    primaryContainer = Teal50,
    onPrimaryContainer = Teal900,
    secondary = Slate700,
    onSecondary = Neutral0,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate900,
    tertiary = Teal500,
    onTertiary = Neutral0,
    background = Neutral50,
    onBackground = Slate900,
    surface = Neutral0,
    onSurface = Slate900,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Slate500,
    outline = Slate300,
    outlineVariant = Slate200,
    error = StatusRed,
    onError = Neutral0,
    errorContainer = StatusRedContainer,
    onErrorContainer = StatusRed,
)

private val DarkColors = darkColorScheme(
    primary = Teal300,
    onPrimary = Teal900,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal50,
    secondary = Slate300,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,
    tertiary = Teal200,
    onTertiary = Teal900,
    background = Neutral900,
    onBackground = Neutral100,
    surface = Neutral800,
    onSurface = Neutral100,
    surfaceVariant = Neutral700,
    onSurfaceVariant = Slate300,
    outline = Slate500,
    outlineVariant = Slate700,
    error = ErrorDark,
    onError = Neutral900,
    errorContainer = ErrorContainerDark,
    onErrorContainer = StatusRedContainer,
)

/**
 * 全局主题。
 *
 * - `dynamicColor` 默认关闭：智存是自有品牌应用，跟随系统取色会让
 *   国产 ROM（尤其取色算法各异的小米/OPPO）出现同一页面三种观感；需要时可由调用方开启。
 * - 动态取色 API 仅 API 31+ 存在，这里按 AGENTS.md §2.4 做显式版本分支，
 *   低版本回落到品牌色，不做「无判断直接调用高版本专有 API」。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
