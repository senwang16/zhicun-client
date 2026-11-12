package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.settings.AiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiSettings] 的 SharedPreferences 实现（M14）。
 *
 * 读到的是"缺省值"而不是"关"：键不存在代表用户从未表达过意愿，此时按产品默认值走。
 * 这与 `getBoolean(KEY, true)` 的语义一致，因此不需要区分"没这个键"和"存了 true"。
 */
@Singleton
class PreferencesAiSettings @Inject constructor(
    @ApplicationContext context: Context,
) : AiSettings {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val cloudAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_AI, DEFAULT_CLOUD_AI)

    override fun setCloudAiEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CLOUD_AI, enabled) }
    }

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_CLOUD_AI = "cloud_ai_enabled"

        /** 产品默认：允许云端处理（见 [AiSettings] 类注释）。 */
        const val DEFAULT_CLOUD_AI = true
    }
}
