package com.zhicun.app.domain.floating

/**
 * 悬浮气泡的启停门面（M13）。
 *
 * ## 为什么不是直接调用 `FloatingBubbleController`
 * 那是个 `object`，内部要 `Settings.canDrawOverlays` 与 `startForegroundService` ——
 * 都是 Android 类型。设置页的 ViewModel 需要它来判断"开关拨到开之后到底该提示授权、
 * 还是可以真的启动"，而这个判断逻辑（用户开关 × 系统权限）是最容易出错的地方，
 * 必须能在 JVM 上测。因此抽一层接口：domain 只声明能力，data 层负责与系统打交道。
 *
 * ## 为什么不做查询服务运行状态的方法
 * 前台服务的启动是**异步**的：`startForegroundService` 只是把请求投递给系统，
 * `isRunning` 在紧接着的那一帧里几乎必然是 false。这样的值放上界面只会显示
 * "开关开着但服务未运行"，看起来像 bug 而不是状态。界面要表达的东西
 * 完全可以从"开关 + 权限"推出来（见 `SettingsUiState.needsPermission`），
 * 所以这里不提供它，避免有人拿去做不可靠的判断。
 */
interface FloatingBubbleGateway {

    /** 系统是否已授予"显示在其他应用上层"权限。 */
    fun hasPermission(): Boolean

    /**
     * 「悬浮窗常在」开关变化后让运行中的气泡即时生效（2026-10-26）：
     * 开启时若气泡正处于贴边细条，立即展开为完整气泡。服务没在跑时静默忽略。
     */
    fun applyAlwaysVisible()

    /**
     * 按"用户开关 + 系统权限"对齐服务状态。
     *
     * @return 服务**应该**处于运行态时为 true。注意它不代表窗口已经画出来 ——
     *   前台服务启动是异步的，界面不该拿它当作"立即生效"的保证。
     */
    fun sync(): Boolean

    /**
     * 打开系统的"显示在其他应用上层"授权页。
     *
     * 失败（部分 ROM 没有这个页面）时退回应用详情页；两者都失败则静默留日志 ——
     * 这是用户主动点的按钮，弹 Toast 说"打不开"没有意义，他自己会再点一次。
     */
    fun openPermissionSettings()
}
