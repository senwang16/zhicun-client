package com.zhicun.app.domain.inbox

import java.time.LocalDate

/**
 * 收件箱列表的展示偏好（M19：按天分组的折叠状态）。
 *
 * 与 [com.zhicun.app.domain.onboarding.OnboardingSettings] 同一取向：低频键、
 * 无查询与迁移需求，SharedPreferences 足够，不值得进 Room。
 *
 * 只持久化“折叠了哪些天”；**日期筛选不持久化** —— 筛选是一次性意图
 * （“看看那天存了什么”），重启后应回到默认视图，而不是被留在某个旧日期里。
 */
interface InboxListSettings {

    /** 当前被折叠的日期集合；空集 = 全部展开（默认）。 */

    val collapsedDays: Set<LocalDate>

    /** 覆盖保存折叠集合（实现方负责上限保护，见 [MAX_COLLAPSED_DAYS]）。 */
    fun saveCollapsedDays(days: Set<LocalDate>)

    companion object {

        /**
         * 折叠集合的条数上限。
         *
         * 防的是**无界增长**：删掉的记录对应的旧折叠日期会一直躺在 prefs 里。
         * 正常路径下ViewModel 在每次保存时已按“现存记录的天”裁剪，走不到这个上限；
         * 只有极端情况（一年以上每天都有记录且全部折叠）才会触发，触发即清空回到全展开
         * —— 那个场景下“折叠全部”本身已经失去导航意义。
         */
        const val MAX_COLLAPSED_DAYS = 365
    }
}
