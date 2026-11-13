package com.zhicun.app.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.LanguagePreference
import com.zhicun.app.domain.settings.UiLanguageSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语言单一事实源（P11 / R-I18N-03/04）。
 *
 * - [currentLocale] 是全应用唯一的 Locale 出口，各页面**禁止**自行读 `Locale.getDefault()`；
 * - [wrap] 用于三处 context：Activity 的 `attachBaseContext`、悬浮窗 Service、通知构建；
 * - Android 13+ 同步系统 `LocaleManager`（用户在系统设置里改语言也能被感知）。
 *
 * 不在 `attachBaseContext` 里用本类：那时 Hilt 字段注入还没发生，拿不到实例。
 * 那条路径走 [com.zhicun.app.localizedContext]，两者读的是同一份偏好。
 */
@Singleton
class AppLocaleManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val preference: LanguagePreference,
) : UiLanguageSettings {

    override fun current(): AppLanguage = preference.uiLanguage()

    fun currentLocale(): Locale = current().locale

    /** 用当前语言包裹 context（资源、通知文案都跟着走）。 */
    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(currentLocale())
        return base.createConfigurationContext(configuration)
    }

    /** 切换语言：写偏好 → 同步系统 → 调用方负责 `recreate()`；零网络、零 Worker。 */
    override fun select(language: AppLanguage) {
        preference.set(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = appContext.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }

    /**
     * Android 13+ 由系统重建，界面**不得**再自己 `recreate()`（否则双重建闪两下）。
     */
    override val recreateRequiredAfterSelect: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Android 13+：系统"应用语言"变化时回写偏好（2026-11-01 审核 #18）。
     *
     * `syncFromSystemIfPresent` 只在冷启动读一次；进程还活着时用户在系统设置里改语言，
     * `attachBaseContext` 会拿旧偏好把系统的新配置**盖回去**，界面弹回旧语言。
     * 由 `Application.onConfigurationChanged` 在配置变更时调用本方法补齐一次：
     * 只在系统确实给本 App 指定了语言（`applicationLocales` 非空）时才回写，
     * 系统语言本身的变化不跟随（R-I18N-01：首启之后不再随系统变化）。
     */
    fun syncFromSystemIfPresent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = appContext.getSystemService(LocaleManager::class.java) ?: return
        val systemLocales = manager.applicationLocales ?: return
        if (systemLocales.isEmpty) return
        val tag = systemLocales[0]?.language ?: return
        preference.set(AppLanguage.fromCode(tag))
    }
}