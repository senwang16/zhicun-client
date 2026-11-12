package com.zhicun.app.fake

import com.zhicun.app.domain.settings.FeedbackLauncher

/** 记录"有没有真的去打开反馈界面"（M14）。 */
class FakeFeedbackLauncher : FeedbackLauncher {

    var openCount: Int = 0

    override fun open() {
        openCount++
    }
}
