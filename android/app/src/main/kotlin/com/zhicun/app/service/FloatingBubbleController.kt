package com.zhicun.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zhicun.app.domain.floating.FloatingBubbleSettings

/**
 * 悬浮气泡的启停入口（M13）。
 *
 * ## 为什么集中到一处
 * "是否该显示气泡"由两个独立的真值决定：**用户开关**（prefs）与**系统权限**（framework）。
 * 如果让设置页、启动流程、Activity 各自判断，很容易出现"开关开了但服务没起"、
 * "权限撤销了但服务还在"这类不一致。所有启停都走 [sync]，判据只有一份。
 *
 * ## 为什么不做成可注入的类
 * 它没有状态、没有依赖（[FloatingBubbleSettings] 由调用方传入），且需要在
 * `MainActivity` / 设置页 / 启动流程三处调用。做成 object 后调用点不需要为了
 * 一次 `startService` 去承担注入成本；需要单测的部分（[shouldRun]）是纯函数。
 */
object FloatingBubbleController {

    private const val TAG = "FloatingBubbleCtl"

    /**
     * 是否需要运行气泡服务。
     *
     * 抽成纯函数是为了能直接单测：这是整条链路里唯一有判断逻辑的地方，
     * 而它恰好是"开关与权限不一致"最容易出错的地方。
     */
    fun shouldRun(enabled: Boolean, hasOverlayPermission: Boolean): Boolean =
        enabled && hasOverlayPermission

    /**
     * 开机 / 覆盖安装后是否该把气泡恢复出来（2026-10-24）。
     *
     * 刻意写成**从 [shouldRun] 派生**，而不是把三个条件并排再写一遍：恢复只是运行的一个
     * 更窄的子集，一旦将来 [shouldRun] 加了新条件（例如再叠一个系统级开关），
     * 恢复路径必须自动跟着收窄。并排写的版本会在这里静默漏掉那条新条件 ——
     * 表现为"开机后气泡违反某个开关自己冒出来"。
     *
     * 注意 `autoRestoreOnBoot` 只能**减少**恢复，不能让一个被关掉的气泡开机弹出来。
     */
    fun shouldRestoreOnBoot(
        enabled: Boolean,
        hasOverlayPermission: Boolean,
        autoRestoreOnBoot: Boolean,
    ): Boolean = autoRestoreOnBoot && shouldRun(enabled, hasOverlayPermission)

    /**
     * 服务健康判据（2026-10-27，PRD v1.2 §2.1）：开关开着、权限也在，但服务没在跑。
     *
     * 抽成纯函数：界面只在**应该运行却缺失**时提示重新开启，而不是把"用户自己关了"
     * 也报成异常（那会变成每次进 App 都弹一句没头没脑的警告）。
     */
    fun healthStatus(
        enabled: Boolean,
        hasOverlayPermission: Boolean,
        isRunning: Boolean,
    ): BubbleServiceHealth = when {
        !shouldRun(enabled, hasOverlayPermission) -> BubbleServiceHealth.NOT_NEEDED
        isRunning -> BubbleServiceHealth.RUNNING
        else -> BubbleServiceHealth.MISSING
    }

    /** 按当前开关与权限状态对齐服务（启动或停止）。返回服务最终是否应该处于运行态。 */
    fun sync(context: Context, settings: FloatingBubbleSettings): Boolean {
        val hasPermission = Settings.canDrawOverlays(context)
        return if (shouldRun(settings.enabled, hasPermission)) {
            start(context)
            true
        } else {
            stop(context)
            false
        }
    }

    /**
     * 开机 / 覆盖安装后的恢复（2026-11-10 Bug 2）。
     *
     * ## 为什么要返回结果而不是只留日志
     * Android 12 起后台启动前台服务受限；API 36 模拟器实测 `BOOT_COMPLETED` 里直接
     * `startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException`
     * （`mAllowStartForeground false`）。原实现把异常吞在 [start] 里，调用方
     * 完全不知道"这次恢复根本没发生"，于是只能等用户下次打开 App ——
     * 与"后台常驻"的产品承诺冲突，且日志里只有一条 stack trace 没有补救动作。
     *
     * 现在把"系统是否接受了启动请求"作为返回值交出去：被拒时调用方可以安排延迟重试
     * （见 `BubbleRestoreScheduler`），而不是假装恢复成功。
     *
     * 判据仍然只有 [shouldRestoreOnBoot] 一处（它从 [shouldRun] 派生）。
     */
    fun restoreOnBoot(context: Context, settings: FloatingBubbleSettings): BootRestoreResult {
        val restore = shouldRestoreOnBoot(
            enabled = settings.enabled,
            hasOverlayPermission = hasOverlayPermission(context),
            autoRestoreOnBoot = settings.autoRestoreOnBoot,
        )
        if (!restore) return BootRestoreResult.SKIPPED
        // 已经在跑就别再投一次（进程可能因其它原因还活着）
        if (FloatingBubbleService.isRunning) return BootRestoreResult.STARTED
        return if (start(context)) BootRestoreResult.STARTED else BootRestoreResult.BLOCKED
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 「悬浮窗常在」开关变化后的即时生效（2026-10-26）：开启时若气泡此刻是贴边细条则展开。
     * 只在服务已运行时才有意义；该开关**不改变服务启停**（[sync] 只认 enabled × 权限）。
     */
    fun applyAlwaysVisible(settings: FloatingBubbleSettings) {
        if (settings.alwaysVisible) FloatingBubbleService.applyAlwaysVisible()
    }

    private fun start(context: Context): Boolean {
        // 已经在跑就别再投一次：这个 sync 会被 MainActivity 每次 onCreate、
        // 以及设置页每次拨开关调用，重复 startForegroundService 会让服务收到
        // 一串 onStartCommand（每次都重发通知栏），纯属噪声。
        // 注意进程被杀时静态引用会随进程一起消失，所以这里不会误判"还活着"。
        if (FloatingBubbleService.isRunning) return true

        val intent = Intent(context, FloatingBubbleService::class.java)
        return try {
            // 必须用 startForegroundService：普通 startService 在后台会被
            // IllegalStateException 拒绝（API 26 起），而气泡正是"在后台常驻"的场景。
            // minSdk=26，版本分支恒为真，已按 lint（ObsoleteSdkInt）去掉。
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // 部分 ROM 在"后台启动前台服务"上有额外限制（Android 12+ 的 FGS 限制）。
            // 失败不该影响 App 其他功能，用户能在设置页看到开关状态与权限状态，据此重试。
            Log.e(TAG, "启动悬浮气泡服务失败", e)
            false
        }
    }

    private fun stop(context: Context) {
        try {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "停止悬浮气泡服务失败", e)
        }
    }
}

/** 开机恢复的结论（2026-11-10 Bug 2）：调用方据此决定要不要安排重试。 */
enum class BootRestoreResult {
    /** 判据不允许恢复（用户关了气泡 / 关了开机恢复 / 没有悬浮窗权限）：静默跳过。 */
    SKIPPED,

    /** 系统接受了前台服务启动请求（服务随后自己挂上气泡）。 */
    STARTED,

    /**
     * 判据允许，但系统拒绝了后台启动前台服务（Android 12+/API 36 的 FGS 限制）。
     *
     * 调用方应安排延迟重试：部分场景（系统刚完成启动、进程状态尚未落定）过几秒
     * 再试就能成功；始终失败时最终仍由「用户下次打开 App 时的 sync」兜底。
     */
    BLOCKED,
}

/** 气泡服务健康状态（2026-10-27，PRD v1.2 §2.1）：只在"该在却不在"时提示用户。 */
enum class BubbleServiceHealth {
    /** 用户没开气泡，或没给悬浮窗权限：不属于异常，不提示。 */
    NOT_NEEDED,

    RUNNING,

    /** 开关开着、权限也在，但服务不在：需要重新拉起并提示用户。 */
    MISSING,
}
