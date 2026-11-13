package com.zhicun.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * 当前界面语言的 Locale（P11-04）。
 *
 * 取 Activity 的配置，而不是 `Locale.getDefault()`：语言偏好通过 `attachBaseContext`
 * 注入到配置里，只有配置能反映用户在设置页选的界面语言；系统 Locale 可能与它不同。
 * 换语言时 Activity 会 `recreate()`（或由系统按 `applicationLocales` 重建），本值随配置一起重建。
 *
 * 日期/数字/字节格式化一律用它，**禁止**直接读 `Locale.getDefault()`（R-I18N-10）。
 */
@Composable
fun uiLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ENGLISH else locales[0]
}