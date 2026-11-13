package com.zhicun.app.data.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import com.zhicun.app.R
import com.zhicun.app.domain.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 界面语言偏好（P11-01 / R-I18N-01）。
 *
 * 钉住两个最容易回归的行为：
 * 1. **首启按设备语言**并立即写盘（`zh*` → 中文，其它 → English）；
 * 2. **写入之后不再随系统变化** —— 下一次构造必须读偏好，而不是重新看系统语言。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesLanguageTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun storedCode(): String? = context()
        .getSharedPreferences(PreferencesLanguage.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PreferencesLanguage.KEY_UI_LANGUAGE, null)

    @Test
    @Config(qualifiers = "en-rUS")
    fun FirstLaunch_englishDevice_initialisesEnglishAndPersists() {
        assertEquals(AppLanguage.EN, PreferencesLanguage(context()).uiLanguage())
        assertEquals("立即持久化，之后不再随系统变化", "en", storedCode())
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun FirstLaunch_chineseDevice_initialisesChineseAndPersists() {
        assertEquals(AppLanguage.ZH, PreferencesLanguage(context()).uiLanguage())
        assertEquals("zh", storedCode())
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun FirstLaunch_otherDevice_fallsBackToEnglish() {
        assertEquals(AppLanguage.EN, PreferencesLanguage(context()).uiLanguage())
    }

    @Test
    fun Set_persistsAcrossInstances() {
        PreferencesLanguage(context()).set(AppLanguage.EN)
        assertEquals(AppLanguage.EN, PreferencesLanguage(context()).uiLanguage())

        PreferencesLanguage(context()).set(AppLanguage.ZH)
        assertEquals(AppLanguage.ZH, PreferencesLanguage(context()).uiLanguage())
    }

    @Test
    fun UnknownStoredValue_fallsBackToChinese() {
        context().getSharedPreferences(PreferencesLanguage.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PreferencesLanguage.KEY_UI_LANGUAGE, "fr")
            .commit()

        assertEquals("脏数据不该让界面变成第三种语言", AppLanguage.ZH, PreferencesLanguage(context()).uiLanguage())
    }

    @Test
    fun AppLocaleManager_wrapsContextWithSelectedLocaleAndResources() {
        val context = context()
        val preference = PreferencesLanguage(context).apply { set(AppLanguage.EN) }
        val manager = AppLocaleManager(context, preference)

        val wrapped = manager.wrap(context)
        assertEquals("en", wrapped.resources.configuration.locales[0].language)
        assertEquals("en", manager.currentLocale().language)
        assertEquals("ZhiCun", wrapped.getString(R.string.app_name))

        preference.set(AppLanguage.ZH)
        assertEquals("智存", manager.wrap(context).getString(R.string.app_name))
    }

    @Test
    fun Select_syncsSystemAppLocaleOnAndroid13Plus() {
        val context = context()
        val manager = AppLocaleManager(context, PreferencesLanguage(context))

        manager.select(AppLanguage.EN)

        assertEquals("写偏好是立即的", AppLanguage.EN, PreferencesLanguage(context).uiLanguage())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val system = context.getSystemService(LocaleManager::class.java)
            assertEquals("系统『应用语言』同步为 en", "en", system.applicationLocales[0]?.language)
        }
    }
}