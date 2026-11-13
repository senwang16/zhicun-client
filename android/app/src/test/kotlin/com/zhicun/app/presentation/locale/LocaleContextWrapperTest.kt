package com.zhicun.app.presentation.locale

import android.content.Context
import com.zhicun.app.R
import com.zhicun.app.data.settings.AppLocaleManager
import com.zhicun.app.data.settings.PreferencesLanguage
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.presentation.common.TimestampStyle
import com.zhicun.app.presentation.common.formatTimestamp
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Locale 单一事实源（R-I18N-01/02/12）。
 *
 * [AppLocaleManager] 与 [LocaleContextWrapper] 必须给出一致的包裹结果，
 * 否则 Activity、悬浮窗与通知可能各用一套 Locale，界面出现中英混排。
 */
@RunWith(RobolectricTestRunner::class)
class LocaleContextWrapperTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun <T> inUtc(block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** 2026-10-29 07:36 UTC：中英两套 pattern 的日期与 12/24 小时制都不同。 */
    private val sampleMillis = 1_793_259_360_000L

    @Test
    fun Wrap_appliesLanguageToResources() {
        val english = LocaleContextWrapper.wrap(context, AppLanguage.EN)
        assertEquals("en", english.resources.configuration.locales[0].language)
        assertEquals("ZhiCun", english.getString(R.string.app_name))

        val chinese = LocaleContextWrapper.wrap(context, AppLanguage.ZH)
        assertEquals("zh", chinese.resources.configuration.locales[0].language)
        assertEquals("智存", chinese.getString(R.string.app_name))
    }

    @Test
    fun ManagerAndWrapper_agree() {
        val preference = PreferencesLanguage(context).apply { set(AppLanguage.EN) }
        val manager = AppLocaleManager(context, preference)

        val fromManager = manager.wrap(context)
        val fromWrapper = LocaleContextWrapper.wrap(context, manager.current())

        assertEquals(
            fromManager.resources.configuration.locales[0],
            fromWrapper.resources.configuration.locales[0],
        )
        assertEquals(
            fromManager.getString(R.string.app_name),
            fromWrapper.getString(R.string.app_name),
        )
    }

    @Test
    fun EnglishResources_areShippedForUserFacingCopy() {
        val english = LocaleContextWrapper.wrap(context, AppLanguage.EN)
        assertEquals("Language", english.getString(R.string.settings_section_language))
        assertEquals(
            "App language. Existing generated content stays as-is and is not regenerated.",
            english.getString(R.string.settings_language_hint),
        )

        val chinese = LocaleContextWrapper.wrap(context, AppLanguage.ZH)
        assertEquals("语言", chinese.getString(R.string.settings_section_language))
    }

    @Test
    fun Timestamp_followsSelectedLanguage() = inUtc {
        assertEquals(
            "Oct 29, 2026 · 7:36 AM",
            formatTimestamp(sampleMillis, AppLanguage.EN.locale),
        )
        assertEquals(
            "2026-10-29 07:36",
            formatTimestamp(sampleMillis, AppLanguage.ZH.locale),
        )
        assertEquals(
            "Oct 29, 7:36 AM",
            formatTimestamp(sampleMillis, AppLanguage.EN.locale, TimestampStyle.COMPACT),
        )
    }
}