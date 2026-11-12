package com.zhicun.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 外部入口路由白名单（2026-10-24 全项目审查 L-1）。
 *
 * `MainActivity.EXTRA_START_ROUTE` 是 Intent extra，属于外部可写输入；
 * 这份测试锁住"能到达的页面是一个封闭集合"这个不变量。
 */
class ExternalRoutesTest {

    @Test
    fun sanitize_knownEntryRoutes_areAccepted() {
        assertEquals(Routes.INBOX, ExternalRoutes.sanitize(Routes.INBOX))
        assertEquals(Routes.CREATE, ExternalRoutes.sanitize(Routes.CREATE))
        assertEquals(Routes.CREATE_PASTE_HINT, ExternalRoutes.sanitize(Routes.CREATE_PASTE_HINT))
        assertEquals(Routes.VOICE, ExternalRoutes.sanitize(Routes.VOICE))
    }

    @Test
    fun sanitize_unknownOrEmpty_isRejected() {
        assertNull(ExternalRoutes.sanitize("evil"))
        assertNull(ExternalRoutes.sanitize(""))
        assertNull(ExternalRoutes.sanitize(null))
    }

    /**
     * 详情页不在白名单里：它需要 recordId 上下文，允许外部凭一个裸字符串直达
     * 等于把"哪条记录可以被打开"交给调用方决定。
     */
    @Test
    fun sanitize_detailRoutes_areRejected() {
        assertNull(ExternalRoutes.sanitize(Routes.RECORD_DETAIL))
        // 用字面量而不是 Routes.recordDetail(...)：后者走 android.net.Uri.encode，
        // 纯 JVM 测试里会抛 "not mocked"（这条用例刻意不引 Robolectric）
        assertNull(ExternalRoutes.sanitize("record/rec-1"))
    }

    /** 设置/归档/搜索都不是外部入口，别因为"顺手"把整张路由表都放出去。 */
    @Test
    fun allowed_excludesContextDependentScreens() {
        assertFalse(ExternalRoutes.allowed.contains(Routes.SETTINGS))
        assertFalse(ExternalRoutes.allowed.contains(Routes.ARCHIVE))
        assertFalse(ExternalRoutes.allowed.contains(Routes.SEARCH))
    }
}
