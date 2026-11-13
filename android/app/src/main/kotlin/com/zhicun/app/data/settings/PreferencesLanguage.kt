package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.LanguagePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语言偏好持久化（P11 / R-I18N-03）。
 *
 * 首次启动按系统语言初始化并**立即写入**；之后不再随系统变化
 * （用户在本应用里选择的语言必须稳定）。卸载重装或清除数据才会重新按设备语言初始化。
 *
 * 刻意用独立文件而不是塞进已有 `ai_gateway`：这一项与隐私/云端的设置生命周期无关，
 * 混在一起会让"清 AI 配置"顺手把语言也清掉。
 */
@Singleton
class PreferencesLanguage @Inject constructor(
    @ApplicationContext context: Context,
) : LanguagePreference {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun uiLanguage(): AppLanguage {
        val stored = prefs.getString(KEY_UI_LANGUAGE, null)
        if (stored != null) return AppLanguage.fromCode(stored)

        val initial = AppLanguage.fromDevice()
        prefs.edit { putString(KEY_UI_LANGUAGE, initial.code) }
        return initial
    }

    override fun set(language: AppLanguage) {
        prefs.edit { putString(KEY_UI_LANGUAGE, language.code) }
    }

    companion object {
        const val PREFS_NAME = "language_preference"
        const val KEY_UI_LANGUAGE = "ui_language"
    }
}