package com.zhicun.app.fake

import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.UiLanguageSettings

/**
 * 界面语言读写口假实现（P11-02）。
 *
 * 只记录选择，不碰系统 `LocaleManager`：ViewModel 的职责边界正是"写偏好 + 更新状态"，
 * 重建 Activity 与系统同步属于界面/`AppLocaleManager` 的事。
 */
class FakeUiLanguageSettings(
    initial: AppLanguage = AppLanguage.ZH,
    /** 模拟 Android 13 以下（需要调用方 recreate）或 13+（系统自己重建）。 */
    override val recreateRequiredAfterSelect: Boolean = true,
) : UiLanguageSettings {

    private var current: AppLanguage = initial

    /** 实际写入过的语言，按顺序记录。 */
    val selections: MutableList<AppLanguage> = mutableListOf()

    override fun current(): AppLanguage = current

    override fun select(language: AppLanguage) {
        selections += language
        current = language
    }
}