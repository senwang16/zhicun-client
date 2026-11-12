package com.zhicun.app.domain.floating

/**
 * 后台常驻的系统侧条件快照（2026-10-24）。
 *
 * ## 为什么需要它
 * 悬浮气泡是前台服务，但"前台服务"只保证**系统不会像普通后台进程那样随手回收**，
 * 它挡不住三件事：Doze 的电池优化、通知被关掉后部分 ROM 的清理策略、
 * App Standby 把应用降级到 RARE/RESTRICTED 分桶。
 * 用户看到的现象是"用了两天，气泡自己没了"，而界面上没有任何线索能解释这件事 ——
 * 这类"静默失效"必须变成可见状态，否则用户会以为功能坏了或者干脆弃用。
 *
 * ## 这里只陈述事实，不做承诺
 * `batteryOptimizationIgnored = false` 的含义是"没有加入电池优化白名单"，
 * **不是**"系统一定会杀你"。同样 `standbyBucket = RARE` 也不等于"服务已经停了"。
 * 界面文案因此写成"未加入白名单/受限"，而不是"已被系统杀死" —— 后者是谎话。
 *
 * @param batteryOptimizationIgnored 是否在电池优化白名单内（`PowerManager.isIgnoringBatteryOptimizations`）
 * @param notificationsEnabled 通知权限是否仍然有效。关掉后前台服务通知不可见，
 *   部分 ROM 会把它当作普通后台进程清理（这是用户最难自己发现的一条）
 * @param standbyBucket 系统给本应用的 App Standby 分桶；API 28 以下无从查询，为 [StandbyBucket.UNKNOWN]
 */
data class KeepAliveStatus(
    val batteryOptimizationIgnored: Boolean,
    val notificationsEnabled: Boolean,
    val standbyBucket: StandbyBucket = StandbyBucket.UNKNOWN,
) {

    /** 需要引导用户去电池优化白名单（未加入）。 */
    val needsBatteryWhitelist: Boolean get() = !batteryOptimizationIgnored

    /** 通知被关掉了：前台服务的可见性没了。 */
    val notificationsOff: Boolean get() = !notificationsEnabled

    /** App Standby 处于会明显限制后台的分桶。 */
    val standbyRestricted: Boolean get() = standbyBucket.isRestricted

    /**
     * 是否存在**已知**的后台限制。
     *
     * 为 true 不代表服务此刻没在跑（它可能刚启动），只代表"条件不满足，随时可能被清"。
     * 界面的用法是决定要不要把那几行标红并给出设置入口，而不是据此停掉服务。
     */
    val hasKnownRestriction: Boolean
        get() = needsBatteryWhitelist || notificationsOff || standbyRestricted
}

/**
 * App Standby 分桶（只保留界面要用的粒度）。
 *
 * 系统给的原值是 5/10/20/30/40/45/50 一类的整数，含义随版本扩充（API 30 才有
 * RESTRICTED，API 31 才有 NEVER）。这里按"对后台存活的影响"归成三档，
 * 避免界面跟着系统版本变化。
 */
enum class StandbyBucket {

    /** 正在使用（前台或刚用过）。 */
    ACTIVE,

    /** 常用应用（系统认为用户经常打开）。 */
    WORKING_SET,

    /** 较常用。 */
    FREQUENT,

    /** 很少用，后台任务被明显限制。 */
    RARE,

    /** 被系统限制（通常是用户或省电模式手动划定）。 */
    RESTRICTED,

    /** 查询不到（API 28 以下，或服务不可用）。界面据此隐藏这一行，而不是显示"正常"。 */
    UNKNOWN,
    ;

    /** 会明显限制后台的分桶。 */
    val isRestricted: Boolean get() = this == RARE || this == RESTRICTED

    /** 是否拿到了真实值；UNKNOWN 时界面不展示这一项。 */
    val isKnown: Boolean get() = this != UNKNOWN
}
