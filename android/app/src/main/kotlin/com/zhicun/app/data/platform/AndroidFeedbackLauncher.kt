package com.zhicun.app.data.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.zhicun.app.R
import com.zhicun.app.domain.settings.FeedbackLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用邮件应用发送反馈（M14）。
 *
 * ## 为什么用 mailto 而不是网页表单
 * 项目没有服务端，也不该为一个反馈按钮引入统计 SDK。邮件地址与主题写在 strings 里，
 * 上线前替换即可（见 DEVELOPMENT_PLAN.md M14 的 T-008）。
 *
 * ## 为什么两道兜底
 * 只发 `mailto:` 时，没装邮件应用的设备会直接抛 `ActivityNotFoundException`，
 * 用户看到的是"点了没反应"（异常被吞）或崩溃（没吞）。因此：
 * 1. 先试 `ACTION_SENDTO`（只有真正的邮件/短信类应用会响应，比 `ACTION_SEND` 精准）；
 * 2. 不行再退成 `ACTION_SEND` 的分享面板，用户至少能选微信/备忘录把反馈记下来。
 */
@Singleton
class AndroidFeedbackLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FeedbackLauncher {

    override fun open() {
        val email = context.getString(R.string.settings_feedback_email)
        val subject = context.getString(R.string.settings_feedback_subject)

        val mailTo = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("mailto", email, null),
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(mailTo)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有可处理 mailto 的应用，退到分享面板", e)
        } catch (e: Exception) {
            Log.e(TAG, "打开发送反馈界面失败", e)
            return
        }

        val fallback = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settings_feedback_body))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(fallback, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // 连分享面板都起不来（极端裁剪 ROM）：留痕即可，用户会改用别的方式反馈
            Log.e(TAG, "分享面板也打不开", e)
        }
    }

    private companion object {
        const val TAG = "FeedbackLauncher"
    }
}
