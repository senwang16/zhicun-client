package com.zhicun.app.domain.floating

/**
 * 后台常驻自检的门面（2026-10-24）。
 *
 * ## 为什么不塞进 FloatingBubbleGateway
 * 那个接口回答的是"气泡该不该显示"（开关 × 悬浮窗权限），
 * 这个接口回答的是"应用整体还能不能留在后台"（电池优化 / 通知 / App Standby）。
 * 两者的真值来源、失效方式、将来扩展方向都不同：前者只服务气泡，
 * 后者在将来有后台任务（例如批量转写补投）时同样要用。
 * 合成一个接口会让"设置页读一个状态"这件事带上整个气泡启停的语义。
 *
 * ## 为什么查询是同步的
 * 三项都是 framework 里的即时状态查询，代价是微秒级，没有 IO。
 * 做成 suspend 只会让设置页多一层 loading 态，而这个值本来就是"此刻的真相"。
 */
interface KeepAliveGateway {

    /** 读当前系统侧条件。任何一项查不到时按其"未知/正常"的保守值返回，绝不抛异常。 */
    fun status(): KeepAliveStatus

    /**
     * 打开系统的电池优化列表。
     *
     * 刻意用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页）而不是
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（弹申请框）：后者必须声明
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，而 Google Play 只允许闹钟、
     * VoIP、设备管理等类别申请，笔记/速记类应用会被拒审。列表页不需要任何权限，
     * 只是用户要多点两下 —— 这个代价换的是"能上架"。
     */
    fun openBatteryOptimizationSettings()

    /** 打开本应用的通知设置页。 */
    fun openNotificationSettings()
}
