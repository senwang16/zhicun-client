package com.zhicun.app.fake

import com.zhicun.app.domain.inbox.InboxListSettings
import java.time.LocalDate

/** [InboxListSettings] 的假实现：内存直存，saveCollapsedDays 可回读断言。 */
class FakeInboxListSettings : InboxListSettings {

    var saved: Set<LocalDate> = emptySet()

    override val collapsedDays: Set<LocalDate> get() = saved

    override fun saveCollapsedDays(days: Set<LocalDate>) {
        saved = days
    }
}
