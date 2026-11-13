package com.zhicun.app.presentation.locale

import android.content.Context
import android.content.res.Configuration
import com.zhicun.app.domain.settings.AppLanguage

/**
 * 用指定语言包裹 context（P11-01）。
 *
 * 为什么不只依赖 `AppLocaleManager`：`Activity.attachBaseContext` 早于 Hilt 字段注入，
 * 那时拿不到任何 `@Inject` 实例，只能把语言作为参数传进来。悬浮窗与通知的 context 同理
 * （Service 常常先于 Activity 起来）。语言值只有两个来源：`AppLocaleManager.current()`
 * 与 `LanguagePreference.uiLanguage()`，两者读的是同一份偏好。
 *
 * 只包一层新 context，不改 base 的 Configuration —— 后者是进程级共享对象。
 */
object LocaleContextWrapper {

    fun wrap(base: Context, language: AppLanguage): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(language.locale)
        return base.createConfigurationContext(configuration)
    }
}