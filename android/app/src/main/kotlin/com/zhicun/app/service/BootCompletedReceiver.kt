package com.zhicun.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.domain.worker.BubbleRestoreScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 开机 / 应用更新后把悬浮气泡挂回去（2026-10-24，2026-11-10 修订启动方式）。
 *
 * ## 为什么必须有它
 * 前台服务只解决"运行期间不被随手回收"，它**不跨重启**，也不跨应用更新：
 * - 手机重启 → 进程没了，没有任何东西会主动拉起服务；
 * - 覆盖安装（升级）→ 进程被系统杀掉，同样不会自动恢复。
 * 整改前只有 `MainActivity.onCreate` 一处启动服务，于是这两种情况下气泡都会永久消失，
 * 直到用户想起来手动打开一次 App —— 用户看到的就是"APP 不能后台常驻"。
 *
 * ## 为什么收这两个广播就够
 * - `BOOT_COMPLETED`：开机完成（用户已解锁，prefs 可读）。direct-boot 阶段的
 *   `LOCKED_BOOT_COMPLETED` 刻意不接：那时应用数据还在 CE 存储里没解锁，
 *   读不到用户的开关，硬起服务只能靠猜。
 * - `MY_PACKAGE_REPLACED`：本应用被覆盖安装之后。
 *
 * ## 后台启动前台服务在 API 36 上并不总是合法（2026-11-10 修正原先的注释）
 * 原文写着"这两条广播都在系统的豁免列表里，因此 `startForegroundService` 被允许"。
 * 实测（API 36 模拟器，三次重启）**不成立**：`BOOT_COMPLETED` 里启动直接抛
 * `ForegroundServiceStartNotAllowedException: mAllowStartForeground false`。
 * 平台把"开机"当成后台状态后，豁免与否取决于系统版本与厂商实现，应用无法预先判定。
 *
 * 因此这里不再把"启动成功"当既成事实：
 * - 启动被接受 → 记日志，收工；
 * - 启动被拒 → 交给 [BubbleRestoreScheduler] 安排延迟重试（Worker 里再做同样的判据），
 *   始终被拒时由「用户下次打开 App 时 `MainActivity.onCreate` 的 sync」兜底。
 *
 * ## 为什么注入 settings 与 scheduler 而不是直接 new
 * 开关的真值只有一份（prefs），走 DI 才能保证与 App 内其他地方读到的是同一个实现。
 * 这个 Receiver 不做任何**策略**判断，策略全在 `FloatingBubbleController.restoreOnBoot`
 * （它从 `shouldRun` 派生，见 `shouldRestoreOnBoot`）。
 *
 * ## 用户能不能不要开机自启（2026-10-24）
 * 能，两个层级：关掉「显示悬浮气泡」= 彻底不要气泡；只关「开机自动恢复气泡」
 * = 气泡还是留着，但重启后不自己冒出来（下次打开智存才出现）。以前只有第一层，
 * 用户想"少一点后台行为"只能把整个功能关掉，而界面上又没写清楚恢复是跟着哪个开关走的。
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settings: FloatingBubbleSettings

    @Inject
    lateinit var retryScheduler: BubbleRestoreScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        if (!isRestoreAction(intent?.action)) return

        when (FloatingBubbleController.restoreOnBoot(context, settings)) {
            // 静默跳过是**正确结果**，不是失败：用户明确关掉了。留 info 是为了
            // 用户回头问"为什么开机没有气泡"时能用日志一句话回答。
            BootRestoreResult.SKIPPED ->
                Log.i(TAG, "收到 ${intent?.action}，但开关未允许恢复，跳过")

            BootRestoreResult.STARTED ->
                Log.i(TAG, "收到 ${intent?.action}，已恢复悬浮气泡")

            BootRestoreResult.BLOCKED -> {
                // 判据允许、系统拒绝：这是唯一需要补救的分支（原实现只打了条 stack trace）
                Log.w(TAG, "收到 ${intent?.action}，系统拒绝后台启动前台服务，安排延迟重试")
                retryScheduler.scheduleRetry()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompleted"

        /**
         * 哪些广播才需要恢复气泡（抽成纯函数以便在 JVM 上测）。
         *
         * 刻意不写成 `intent.action != null`：这个 Receiver 只声明了两条广播，
         * 但清单是可以被 `tools:node="merge"` 改动的，将来若有人加了别的 action，
         * "来什么就恢复什么"会让气泡在最莫名其妙的时机弹出来。
         */
        fun isRestoreAction(action: String?): Boolean = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> true

            else -> false
        }
    }
}