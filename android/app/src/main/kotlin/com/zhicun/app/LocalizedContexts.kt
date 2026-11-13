package com.zhicun.app

import android.content.Context
import com.zhicun.app.data.settings.PreferencesLanguage
import com.zhicun.app.presentation.locale.LocaleContextWrapper

/**
 * 组件级 context 包裹（P11-05）。
 *
 * Service、无界面的中转 Activity、AppWidgetProvider 都不在 `MainActivity` 的继承链上，
 * 拿不到它在 `attachBaseContext` 里做的语言包裹。而通知标题、动作按钮、Toast 与
 * RemoteViews 文案都是从这些组件的 context 取资源 —— 不包裹就会跟着**系统语言**，
 * 用户在应用内选了 English 之后仍然收到中文通知。
 *
 * `attachBaseContext` 早于 Hilt 注入，所以这里直接读偏好（与 `MainActivity` 同一原因）。
 * 偏好文件很小、读取是同步的，且只在组件创建时各读一次。
 */
fun localizedContext(base: Context): Context =
    LocaleContextWrapper.wrap(base, PreferencesLanguage(base).uiLanguage())