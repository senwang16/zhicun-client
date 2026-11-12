package com.zhicun.app.widget

import com.zhicun.app.presentation.floating.QuickCaptureAction
import com.zhicun.app.presentation.floating.routeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 小组件按钮的契约（B3）。
 *
 * RemoteViews 的构建依赖真实 AppWidgetManager，属系统行为，不进单测。
 * 这里钉的是容易漂移的那部分：**三个按钮的动作、顺序，以及
 * 每一个都能在共享路由表 routeFor 里找到去向** —— 面板 / 通知按钮 / 小组件
 * 是同一动作的三个入口，路由表只此一张，谁也私不开第二份。
 */
class QuickCaptureWidgetProviderTest {

    @Test
    fun WidgetButtons_areTextVoiceInboxInOrder() {
        assertEquals(
            listOf(
                QuickCaptureAction.TEXT,
                QuickCaptureAction.VOICE,
                QuickCaptureAction.INBOX,
            ),
            QuickCaptureWidgetProvider.widgetActions,
        )
    }

    /** 剪贴板不在小组件上：它要拉起透明中转页，从桌面直达容易误触。 */
    @Test
    fun WidgetButtons_doNotContainClipboard() {
        assertEquals(false, QuickCaptureWidgetProvider.widgetActions.contains(QuickCaptureAction.CLIPBOARD))
    }

    /** 每个按钮都必须有路由可去：一旦 routeFor 改了语义，这条会先在测试里红。 */
    @Test
    fun EveryWidgetButton_hasRouteInSharedTable() {
        QuickCaptureWidgetProvider.widgetActions.forEach { action ->
            assertNotNull("动作 $action 在共享路由表里没有去向", routeFor(action))
        }
    }
}
