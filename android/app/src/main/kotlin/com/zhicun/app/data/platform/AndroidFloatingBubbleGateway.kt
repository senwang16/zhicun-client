package com.zhicun.app.data.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.service.FloatingBubbleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FloatingBubbleGateway] 的 Android 实现（M13）。
 *
 * 全部职责就一句：把 domain 的抽象落到 `Settings.canDrawOverlays` / `startForegroundService` /
 * `ACTION_MANAGE_OVERLAY_PERMISSION` 上。判断逻辑一律不在这里写 ——
 * 它在 [FloatingBubbleController.shouldRun] 与 `SettingsViewModel` 里，都是纯 JVM 可测的。
 */
@Singleton
class AndroidFloatingBubbleGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: FloatingBubbleSettings,
) : FloatingBubbleGateway {

    override fun hasPermission(): Boolean = FloatingBubbleController.hasOverlayPermission(context)

    override fun applyAlwaysVisible() = FloatingBubbleController.applyAlwaysVisible(settings)

    override fun sync(): Boolean = FloatingBubbleController.sync(context, settings)

    override fun openPermissionSettings() {
        // 带上 package: 的 URI，系统会直接打开"本应用"的那一项；
        // 不带时落到应用列表，用户还得自己找到智存（国产 ROM 上列表可能很长）
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 少数 ROM 没有这个 action，退回应用详情页（用户能在那里找到悬浮窗开关）
            Log.w(TAG, "悬浮窗授权页不存在，退回应用详情页", e)
            openAppDetails()
        } catch (e: Exception) {
            // 后台启动 Activity 的限制 / 系统被厂商改动：留痕即可，不做二次弹窗
            Log.e(TAG, "打开悬浮窗授权页失败", e)
        }
    }

    private fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开应用详情页也失败", e)
        }
    }

    private companion object {
        const val TAG = "FloatingGateway"
    }
}
