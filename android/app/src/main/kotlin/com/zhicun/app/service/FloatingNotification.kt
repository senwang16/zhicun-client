package com.zhicun.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zhicun.app.MainActivity
import com.zhicun.app.R
import com.zhicun.app.presentation.floating.QuickCaptureAction
import com.zhicun.app.presentation.floating.routeFor
import com.zhicun.app.presentation.navigation.Routes

private const val TAG = "FloatingBubble"
private const val CHANNEL_ID = "floating_bubble"
private const val NOTIFICATION_ID = 1001
// 通知动作按钮的 requestCode 必须互不相同，否则后建的 PendingIntent 会覆盖先建的
// （PendingIntent 去重键 = requestCode + Intent filterEquals，extras 不参与；
//  与小组件的号段划分见 updateWidget——contentIntent 用 1005，别再用 0。
//  全局号段约定：0-999 保留、1000-1999 本服务、3000+ 小组件。）
private const val REQUEST_CODE_CONTENT = 1005
private const val REQUEST_CODE_TEXT = 1002
private const val REQUEST_CODE_VOICE = 1003
private const val REQUEST_CODE_CLIPBOARD = 1004

/** 通知栏上"直达新建"的两个动作（按显示顺序）。剪贴板单独处理：它走中转页而不是主界面路由。 */
private val ROUTE_ACTIONS = listOf(
    REQUEST_CODE_TEXT to QuickCaptureAction.TEXT,
    REQUEST_CODE_VOICE to QuickCaptureAction.VOICE,
)

/**
 * 进入前台：常驻通知 + 低优先级通知渠道（从 FloatingBubbleService 拆出，纯构建逻辑）。
 *
 * requestCode 全局唯一：PendingIntent 按 (requestCode + Intent filterEquals) 去重，
 * extras 不参与——B3 小组件曾与这里的 0 撞车（同组件同 flags），点「记一笔」
 * 被覆盖成跳收件箱。
 */
internal fun Service.startFloatingForeground(
    withMicrophone: Boolean = false,
    withMediaProjection: Boolean = false,
) {
    ensureChannel()
    val contentIntent = PendingIntent.getActivity(
        this,
        REQUEST_CODE_CONTENT,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_ROUTE, Routes.INBOX),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_floating_notification)
        .setContentTitle(getString(R.string.floating_notification_title))
        .setContentText(getString(R.string.floating_notification_text))
        .setContentIntent(contentIntent)

    // 动作按钮（B1）：不用打开应用再找气泡，通知栏直达三个高频入口。
    // 点正文仍是收件箱（contentIntent）；路由映射与面板 onAction 共用 routeFor。
    //
    // 这里**不再**写 `routeFor(...)!!`（2026-10-24 全项目审查 L-1）：`routeFor` 的返回类型
    // 是 `String?`（剪贴板走中转页，没有主界面路由），强解包等于把"当前这几个动作一定有路由"
    // 这个隐含前提写进代码 —— 哪天给 TEXT 之类加一条返回 null 的分支，这里就是运行期崩溃。
    // 改成"取不到路由就不建这个按钮"，语义上永远不会崩。
    ROUTE_ACTIONS.forEach { (requestCode, action) ->
        val route = routeFor(action) ?: return@forEach
        builder.addAction(
            action.iconRes,
            getString(action.labelRes),
            routeAction(requestCode, route),
        )
    }
    builder.addAction(
        QuickCaptureAction.CLIPBOARD.iconRes,
        getString(QuickCaptureAction.CLIPBOARD.labelRes),
        clipboardAction(),
    )

    val notification = builder
        // 常驻、不可滑动清除：滑掉就会变成"有服务没通知"，系统 5 秒内会把它杀掉
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .build()

    // API 34 起必须显式声明前台服务类型；specialUse 是"不在既有分类里"的合法归口，
    // 对应的 FOREGROUND_SERVICE_SPECIAL_USE 权限与 property 声明见 AndroidManifest。
    // 录音（2026-10-26）时叠加 microphone 类型：API 34+ 使用麦克风的前台服务必须在
    // startForeground 时声明该类型（manifest 也声明了，但运行期仍需显式带上）。
    // 截图（2026-10-27）同理：getMediaProjection 之前必须已带 mediaProjection 类型，
    // 且截完要降回 specialUse（否则系统持续显示"正在截屏"）。
    //
    // API 29-33 也要传运行期类型（2026-10-27 复核）：specialUse 还不存在
    // （ServiceCompat 会掩掉高位），但 mediaProjection 从 Android 10 起、microphone
    // 从 Android 11 起都是实打实的运行期状态。传 0 等于"没有类型"：
    // 后台访问麦克风、屏幕捕获都会被系统拒绝。
    val type = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            var value = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMicrophone) {
                value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (withMediaProjection) {
                value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            value
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            var value = 0
            // microphone 类型是 API 30 才有的常量；Android 11 起才要求后台访问麦克风
            // 使用带该类型的 FGS，API 29 传它会撞上未知类型校验。
            if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (withMediaProjection) {
                value = value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            value
        }

        else -> 0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
}

/**
 * 通知动作按钮：拉起主界面并落到目标路由。
 * 语义与 FloatingBubbleService.onAction 的 TEXT/VOICE 分支一致，
 * 只是入口从气泡面板换成了通知栏。
 */
private fun Service.routeAction(requestCode: Int, route: String): PendingIntent =
    PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/**
 * 通知动作按钮：读剪贴板。走透明中转页（Android 10+ 要求"有前台 Activity"才能读剪贴板），
 * 与 FloatingBubbleService.onAction 的 CLIPBOARD 分支同一条路径。
 */
private fun Service.clipboardAction(): PendingIntent =
    PendingIntent.getActivity(
        this,
        REQUEST_CODE_CLIPBOARD,
        Intent(this, ClipboardCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

private fun Service.ensureChannel() {
    // 通知渠道是 API 26 的强制要求，而 minSdk=26 —— 这里不需要版本判断（lint: ObsoleteSdkInt）
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_notification_channel),
            // LOW：不出声、不弹横幅，只在通知栏占一行说明"它在运行"
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) },
    )
}
