package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.inbox.InboxListSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [InboxListSettings] 的 SharedPreferences 实现（M19）。
 *
 * 与 [PreferencesOnboardingSettings] 同一取向：低频读写、不缓存（每次进收件箱读一次、
 * 折叠切换时写一次，缓存反而引入“界面已切、prefs 还没写”的时序问题）。
 *
 * 存储：ISO-8601 日期（`2026-10-24`）逗号拼接 —— 一个键存整份集合，读写都是原子
 * 覆盖，不需要“按天逐键”的增量协议。坏条目（手改/跨版本）读取时直接丢弃，
 * 最坏结果是某一天回到展开态，无害。
 */
@Singleton
class PreferencesInboxListSettings @Inject constructor(
    @ApplicationContext context: Context,
) : InboxListSettings {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val collapsedDays: Set<LocalDate>
        get() = prefs.getString(KEY_COLLAPSED_DAYS, null)
            ?.split(',')
            .orEmpty()
            .mapNotNull { it.trim().toLocalDateOrNull() }
            .toSet()

    override fun saveCollapsedDays(days: Set<LocalDate>) {
        // 上限保护：超限说明集合已经失去“快速折叠导航”的意义，整体清空回到默认全展开。
        val effective = if (days.size > InboxListSettings.MAX_COLLAPSED_DAYS) emptySet() else days
        prefs.edit {
            putString(KEY_COLLAPSED_DAYS, effective.joinToString(",") { it.toString() })
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PREFS_NAME = "inbox_list"
        const val KEY_COLLAPSED_DAYS = "collapsed_days"
    }
}
