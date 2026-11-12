package com.zhicun.app.fake

import com.zhicun.app.domain.onboarding.OnboardingSettings

/**
 * [OnboardingSettings] 的进程内假实现。
 *
 * 与 [FakeFloatingBubbleSettings] 同一取向：写操作记账（`completions` /
 * `bannerDismissals`），让测试能断言"确实写了一次"而不只是"读到的是新值"。
 */
class FakeOnboardingSettings(
    completedVersion: Int = 0,
    bannerDismissed: Boolean = false,
) : OnboardingSettings {

    override var completedVersion: Int = completedVersion
        private set

    override var bubbleGuideBannerDismissed: Boolean = bannerDismissed
        private set

    /** 每次 [complete] 的入参记录，按顺序。 */
    val completions: MutableList<Int> = mutableListOf()

    var bannerDismissals: Int = 0
        private set

    override fun complete(version: Int) {
        completions += version
        completedVersion = version
    }

    override fun dismissBubbleGuideBanner() {
        bannerDismissals += 1
        bubbleGuideBannerDismissed = true
    }
}
