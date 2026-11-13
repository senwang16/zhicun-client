package com.zhicun.app.fake

import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.LanguagePreference

/** 语言偏好假实现（P11）：只回答当前值，写入记录在 [writes] 里便于断言。 */
class FakeLanguagePreference(
    initial: AppLanguage = AppLanguage.ZH,
) : LanguagePreference {

    private var current: AppLanguage = initial

    val writes: MutableList<AppLanguage> = mutableListOf()

    override fun uiLanguage(): AppLanguage = current

    override fun set(language: AppLanguage) {
        writes += language
        current = language
    }
}