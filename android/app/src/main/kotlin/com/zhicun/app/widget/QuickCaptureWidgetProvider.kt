package com.zhicun.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zhicun.app.MainActivity
import com.zhicun.app.R
import com.zhicun.app.localizedContext
import com.zhicun.app.presentation.floating.QuickCaptureAction
import com.zhicun.app.presentation.floating.routeFor

/**
 * 桌面小组件（B3）：三个快捷按钮 —— 新建文字 / 新建语音 / 打开收件箱。
 *
 * ## 为什么是手写 RemoteViews 而不是 Glance
 * 三个静态按钮，没有状态、没有数据绑定、没有 recomposition 需求。
 * 引入 Glance（连同 Compose 在 widget 宿主里的运行时）只为画三个按钮，不划算 ——
 * 项目守"零新依赖"的选型原则。RemoteViews 的能力上限（静态布局 + 点击事件）恰好够用。
 *
 * ## 为什么路由复用 [routeFor]
 * 面板（M13）、通知动作按钮（B1）、小组件（B3）是同一个动作的三个入口。
 * 三处各写一份映射，迟早出现"通知进语音页、小组件进新建页"的漂移。
 * 小组件的三个动作都取自这张表的非空分支，路由改一处全生效。
 */
class QuickCaptureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 桌面上可能放了多个同类 widget，每个都要单独更新
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {

        /**
         * 小组件的按钮，顺序即布局里从左到右（与 [BUTTON_IDS] 一一对应）。
         * 全部取自 [routeFor] 的非空分支 —— 剪贴板不在小组件上：
         * 它要拉起透明中转页读系统剪贴板，从桌面直达反而容易误触。
         */
        internal val widgetActions: List<QuickCaptureAction> = listOf(
            QuickCaptureAction.TEXT,
            QuickCaptureAction.VOICE,
            QuickCaptureAction.INBOX,
        )

        /** 顺序必须与 [widgetActions] 一致；requestCode 用下标生成，天然互不相同。 */
        private val BUTTON_IDS = intArrayOf(
            R.id.widget_button_text,
            R.id.widget_button_voice,
            R.id.widget_button_inbox,
        )

        /** PendingIntent requestCode 号段（全局唯一，见 updateWidget 内注释）。 */
        private const val WIDGET_REQUEST_CODE_BASE = 3000

        internal fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
            // 按钮文案跟随应用内语言（P11-05）：Provider 的 context 也可能来自系统刷新，
            // 不包裹就会退回系统语言
            val labels = localizedContext(context)
            widgetActions.forEachIndexed { index, action ->
                views.setTextViewText(BUTTON_IDS[index], labels.getString(action.labelRes))
                // routeFor 对这三个动作恒非空；contract 由 QuickCaptureWidgetProviderTest 钉死
                views.setOnClickPendingIntent(
                    BUTTON_IDS[index],
                    launchIntent(
                        context,
                        // requestCode 全局唯一：PendingIntent 的去重键是 (requestCode + Intent
                        // filterEquals)，extras 不参与。B1 通知 contentIntent 曾占用 0（route=inbox），
                        // 与这里的 0 撞车后点了「记一笔」却跳收件箱——后建的 extras 覆盖先建的。
                        // 号段约定：0-999 系统/暂留、1000-1999 FloatingBubbleService 通知、
                        // 3000+ 小组件（见 FloatingBubbleService REQUEST_CODE_*）。
                        WIDGET_REQUEST_CODE_BASE + index,
                        routeFor(action) ?: return@forEachIndexed,
                    ),
                )
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * 重绘桌面上所有小组件（P11-05）。
         *
         * 文案在 [updateWidget] 里解析，系统不会因为应用内换语言而自动刷新 ——
         * 不主动重绘，桌面会一直显示旧语言，直到下一次 widget 更新。
         */
        internal fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuickCaptureWidgetProvider::class.java),
            )
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun launchIntent(context: Context, requestCode: Int, route: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_ROUTE, route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
