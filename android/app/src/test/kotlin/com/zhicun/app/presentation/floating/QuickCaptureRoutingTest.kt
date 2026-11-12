package com.zhicun.app.presentation.floating

import com.zhicun.app.presentation.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 快捷动作 → 主界面路由的真值表（M13 面板 + B1 通知栏按钮共用）。
 *
 * 面板点击与通知栏动作按钮走的是同一张映射表（routeFor），
 * 这里钉死每一格，防止任何一边改了路由另一边漏改。
 * 剪贴板特殊：读系统剪贴板要求有前台 Activity（Android 10+ 限制），
 * 必须走透明中转页，因此不在路由表里（返回 null）。
 */
class QuickCaptureRoutingTest {

    @Test
    fun RouteFor_text_goesToCreatePage() {
        assertEquals(Routes.CREATE, routeFor(QuickCaptureAction.TEXT))
    }

    @Test
    fun RouteFor_voice_goesToVoicePage() {
        assertEquals(Routes.VOICE, routeFor(QuickCaptureAction.VOICE))
    }

    @Test
    fun RouteFor_inbox_goesToInboxPage() {
        assertEquals(Routes.INBOX, routeFor(QuickCaptureAction.INBOX))
    }

    @Test
    fun RouteFor_home_goesToInboxPage() {
        assertEquals(Routes.INBOX, routeFor(QuickCaptureAction.HOME))
    }

    @Test
    fun RouteFor_clipboard_isNull_transparentActivityInstead() {
        assertNull(routeFor(QuickCaptureAction.CLIPBOARD))
    }

    @Test
    fun RouteFor_screenshot_isNull_screenCaptureActivityInstead() {
        assertNull(routeFor(QuickCaptureAction.SCREENSHOT))
    }
}
