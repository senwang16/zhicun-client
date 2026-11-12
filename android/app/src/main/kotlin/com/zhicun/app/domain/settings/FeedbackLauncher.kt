package com.zhicun.app.domain.settings

/**
 * "意见反馈"入口（M14）。
 *
 * 只有一件事要做：把用户送到能写邮件的地方。抽成接口是为了让设置页的
 * ViewModel 不碰 `Intent` / `Uri`（domain 与 presentation 都不该知道怎么发邮件）。
 */
interface FeedbackLauncher {

    /** 打开发送反馈的界面。打开失败（设备上没有邮件应用）由实现自己兜底，不上抛。 */
    fun open()
}
