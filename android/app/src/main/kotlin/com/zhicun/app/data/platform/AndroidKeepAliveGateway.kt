package com.zhicun.app.data.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.zhicun.app.domain.floating.KeepAliveGateway
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.floating.StandbyBucket
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [KeepAliveGateway] 的 Android 实现（2026-10-24）。
 *
 * 三个数据源分别对应三种"后台被限制"的方式：
 * - `PowerManager.isIgnoringBatteryOptimizations` → Doze 的省电优化；
 * - `NotificationManagerCompat.areNotificationsEnabled` → 通知被关（部分 ROM 会趁机清前台服务）；
 * - `UsageStatsManager.appStandbyBucket` → App Standby 分桶降级。
 *
 * ## 全部读取都容错
 * 这是设置页打开时同步调用的地方，任何一处抛出都会让整页白屏 ——
 * 而它回答的只是"要不要提示用户去加白"。查不到就按保守值返回，
 * 界面少显示一行，比整页崩掉合理得多。
 */
@Singleton
class AndroidKeepAliveGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : KeepAliveGateway {

    override fun status(): KeepAliveStatus = KeepAliveStatus(
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
        notificationsEnabled = areNotificationsEnabled(),
        standbyBucket = standbyBucket(),
    )

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            // minSdk 26，该 API 从 23 起就有，无需版本分支
            power.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            // 个别 ROM 在这里抛 SecurityException（把查询也纳入了权限管控）
            Log.w(TAG, "查询电池优化白名单失败", e)
            false
        }
    }

    private fun areNotificationsEnabled(): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (e: Exception) {
        Log.w(TAG, "查询通知权限失败", e)
        // 查不到时按"开着"处理：把一行可能并不存在的问题标红，
        // 会让用户去改一个没坏的东西
        true
    }

    /**
     * App Standby 分桶。
     *
     * 用**无参**的 `appStandbyBucket`：它返回调用方自己的分桶，不需要 `PACKAGE_USAGE_STATS`
     * 权限。带包名的重载才需要那个权限，用它会让这里直接抛 SecurityException。
     */
    private fun standbyBucket(): StandbyBucket {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return StandbyBucket.UNKNOWN
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return StandbyBucket.UNKNOWN
        return try {
            mapBucket(usage.appStandbyBucket)
        } catch (e: Exception) {
            Log.w(TAG, "查询 App Standby 分桶失败", e)
            StandbyBucket.UNKNOWN
        }
    }

    override fun openBatteryOptimizationSettings() {
        // 列表页：不需要任何权限，用户自己在里面找到"智存"并选"不优化"
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrFallback(intent, "电池优化列表")
    }

    override fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrFallback(intent, "通知设置页")
    }

    /**
     * 打不开就退回应用详情页。
     *
     * 两个设置页在国产 ROM 上被改名的概率不低，而应用详情页是 AOSP 长期保证存在的
     * 兜底入口 —— 用户在那里也能找到"电池"和"通知"两个分项。
     */
    private fun startOrFallback(intent: Intent, label: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$label 不存在，退回应用详情页", e)
            openAppDetails()
        } catch (e: Exception) {
            Log.e(TAG, "打开$label 失败", e)
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

    /**
     * 桶值 → 界面粒度。
     *
     * 注意 `EXEMPTED`(5) 与 `NEVER`(50) 在 SDK stub 里**没有**公开常量
     * （只有 ACTIVE/WORKING_SET/FREQUENT/RARE/RESTRICTED 五个），
     * 因此这两个按官方文档的数值处理：白名单豁免等同"活跃"，
     * "永不使用"按最强限制算。其余未知值一律 UNKNOWN，界面不显示那一行。
     */
    private fun mapBucket(raw: Int): StandbyBucket = when (raw) {
        BUCKET_EXEMPTED -> StandbyBucket.ACTIVE
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
        UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBucket.RESTRICTED
        BUCKET_NEVER -> StandbyBucket.RESTRICTED
        else -> StandbyBucket.UNKNOWN
    }

    private companion object {
        const val TAG = "KeepAliveGateway"

        /** `UsageStatsManager.STANDBY_BUCKET_EXEMPTED`（API 31 起，SDK stub 未公开）。 */
        const val BUCKET_EXEMPTED = 5

        /** `UsageStatsManager.STANDBY_BUCKET_NEVER`（API 31 起，SDK stub 未公开）。 */
        const val BUCKET_NEVER = 50
    }
}
