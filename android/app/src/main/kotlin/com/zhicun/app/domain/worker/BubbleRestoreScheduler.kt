package com.zhicun.app.domain.worker

/**
 * 开机恢复悬浮气泡的**重试**调度契约（2026-11-10 Bug 2）。
 *
 * ## 为什么恢复需要一个重试通道
 * 前台服务不跨重启。开机广播里直接 `startForegroundService` 是最短路径，但 Android 12 起
 * 后台启动前台服务受限，API 36 模拟器实测会抛 `ForegroundServiceStartNotAllowedException`
 * （见 `FloatingBubbleController.restoreOnBoot` 的 BLOCKED）。此时如果什么都不做，
 * 就只能等用户下次打开 App —— 与"后台常驻"的承诺不符。
 *
 * 重试交给 WorkManager（而不是在广播里死循环）：WorkManager 的执行时机由系统决定，
 * 只要那一次执行时系统允许启动前台服务（进程状态已落定 / 厂商 ROM 放宽限制），
 * 气泡就会自己回来；始终不允许时由「下次打开 App 时的 sync」兜底。
 *
 * domain 只声明"可以安排一次重试"，不感知 WorkManager。
 */
interface BubbleRestoreScheduler {

    /**
     * 安排一次延迟重试。
     *
     * 幂等：已经有重试链在跑时保持原链（`KEEP`），不会因为连收两条广播就排两份。
     */
    fun scheduleRetry()
}