package com.zhicun.app.domain.onboarding

/**
 * 首启引导的完成状态（附录 T）。
 *
 * ## 为什么是版本号而不是布尔
 * 用 `completedVersion < CURRENT_VERSION` 判定：将来追加新引导屏（或新功能说明）时
 * 把 [CURRENT_VERSION] 加一，老用户升级后会再看一次新内容，而已看过的不重复打扰。
 * 布尔只能表达"看过没有"，表达不了"看过的是哪一版"。
 *
 * ## 为什么用 SharedPreferences 而不是 Room
 * 只有两个低频键（完成版本、横幅关闭标记），没有查询与迁移需求 ——
 * 与 [com.zhicun.app.domain.floating.FloatingBubbleSettings] 同一取向。
 */
interface OnboardingSettings {

    /** 用户已看完并确认过的引导版本；从未看过为 0。 */
    val completedVersion: Int

    /** 收件箱"开启悬浮窗"引导横幅是否已被用户手动关掉（附录 T：横幅只此一条、可关闭）。 */
    val bubbleGuideBannerDismissed: Boolean

    /** 记录"已看完第 [version] 版引导"。任一出口（走完 / 跳过）都调用。 */
    fun complete(version: Int)

    fun dismissBubbleGuideBanner()

    companion object {

        /**
         * 当前引导的目标版本。
         *
         * **只升不减**：`completedVersion > CURRENT_VERSION`（理论上不该出现）同样视为
         * 已完成，避免回退版本号时把老用户重新拽进引导。
         */
        const val CURRENT_VERSION = 1
    }
}
