package com.zhicun.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 路由表测试。
 *
 * 重点不是"字符串常量等于自己"，而是**参数编码**：recordId 来自外部分享/剪贴板，
 * 含 `/` 时若不编码会被 Navigation 当成多级路径，跳到一半发现路由不存在直接崩。
 */
@RunWith(RobolectricTestRunner::class)
class RoutesTest {

    @Test
    fun RecordDetail_plainId_buildsExpectedPath() {
        assertEquals("record/abc-123", Routes.recordDetail("abc-123"))
    }

    @Test
    fun RecordDetail_idWithSlash_isEncoded() {
        val path = Routes.recordDetail("a/b?c=d")

        assertTrue("斜杠必须被编码，实际 $path", path.contains("%2F"))
        assertTrue("问号必须被编码，实际 $path", path.contains("%3F"))
    }

    @Test
    fun RecordDetail_idWithChinese_isPercentEncoded() {
        val path = Routes.recordDetail("笔记/1")

        assertTrue("中文应被百分号编码，实际 $path", path.startsWith("record/%"))
    }

    @Test
    fun RecordDetail_argumentNameMatchesRouteTemplate() {
        assertTrue(Routes.RECORD_DETAIL.contains("{${Routes.RECORD_ID_ARG}}"))
    }
}
