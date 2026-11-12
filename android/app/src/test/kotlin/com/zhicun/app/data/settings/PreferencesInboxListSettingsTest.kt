package com.zhicun.app.data.settings

import android.content.Context
import com.zhicun.app.domain.inbox.InboxListSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 收件箱折叠偏好的 SharedPreferences 实现（M19）。
 *
 * 重点四条：默认空集（全展开）、跨实例持久（ViewModel 每次构造读初值）、
 * 超上限清空（防无界增长）、坏条目读取时丢弃（手改/跨版本不炸）。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesInboxListSettingsTest {

    private fun newSettings() =
        PreferencesInboxListSettings(RuntimeEnvironment.getApplication())

    @Test
    fun Defaults_collapsedDaysEmpty() {
        assertTrue("默认必须全展开", newSettings().collapsedDays.isEmpty())
    }

    @Test
    fun Save_persistsAcrossInstances() {
        val days = setOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 20))
        newSettings().saveCollapsedDays(days)

        assertEquals(days, newSettings().collapsedDays)
    }

    @Test
    fun Save_emptySet_persistsEmpty() {
        val settings = newSettings()
        settings.saveCollapsedDays(setOf(LocalDate.of(2026, 10, 24)))
        settings.saveCollapsedDays(emptySet())

        assertTrue(newSettings().collapsedDays.isEmpty())
    }

    @Test
    fun Save_overLimit_savesEmptyInstead() {
        val tooMany = (1..InboxListSettings.MAX_COLLAPSED_DAYS + 1)
            .map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
            .toSet()

        newSettings().saveCollapsedDays(tooMany)

        assertTrue("超限必须整体清空而不是截断", newSettings().collapsedDays.isEmpty())
    }

    @Test
    fun Read_dropsInvalidEntries() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("inbox_list", Context.MODE_PRIVATE)
            .edit()
            .putString("collapsed_days", "2026-10-24, not-a-date ,2026-10-26")
            .commit()

        assertEquals(
            "坏条目丢弃、好条目保留",
            setOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 26)),
            newSettings().collapsedDays,
        )
    }
}
