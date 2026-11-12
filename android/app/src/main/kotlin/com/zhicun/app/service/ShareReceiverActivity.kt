package com.zhicun.app.service

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zhicun.app.service.share.MimeTypeLookup
import com.zhicun.app.service.share.ShareFeedback
import com.zhicun.app.service.share.ShareIntakeCoordinator
import com.zhicun.app.service.share.referrerPackageOf
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 系统分享接收页（M4 的主入口）。
 *
 * 它是一个**没有界面的 Activity**：只做"接住 Intent → 交给 [ShareIntakeCoordinator] →
 * 轻提示 → 结束"，主题透明、`noHistory`、`excludeFromRecents`，
 * 用户不会有"被带到另一个 App"的感觉（PRD §5.1 第 5 条）。
 *
 * 用 `lifecycleScope` 而不是 `WorkManager`：保存必须**在授权有效期内**完成
 * （分享给的 URI 读权限是临时的），交给异步队列可能等到权限失效才跑，
 * 那时复制必然失败。长时间任务（AI 处理）才是 WorkManager 的范畴（M6）。
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var intake: ShareIntakeCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 只有 Toast、没有 UI，因此无需 setContent。
        // 旋转/进程恢复时 savedInstanceState 非空：说明这条分享已在处理或已处理完，
        // 直接结束，避免同一条内容被保存两次。
        if (savedInstanceState != null) {
            Log.d(TAG, "Activity 被重建，跳过重复处理")
            finish()
            return
        }

        lifecycleScope.launch {
            // referrer 只有 Activity 能拿到（Intent 上没有这个信息），用它识别「从哪个 App 分享过来」；
            // mimeTypeLookup 用于把相册常见的通配声明类型回查成真实类型。
            //
            // referrerPackage 必须走 referrerPackageOf：浏览器/Web 中转给的是 `https://weibo.com`
            // 这类 URI，authority 是域名不是包名，直接拿去 getApplicationInfo 必抛
            // NameNotFoundException，界面会显示"来源应用：weibo.com"的裸域名。
            val feedback = intake.handle(
                intent = intent,
                packageManager = packageManager,
                referrerPackage = referrerPackageOf(referrer),
                mimeTypeLookup = MimeTypeLookup { uri -> contentResolver.getType(uri) },
            )
            showFeedback(feedback)
        }
    }

    /** 统一的收尾：提示 → 结束自己，把用户交还给来源 App。 */
    private fun showFeedback(feedback: ShareFeedback) {
        Toast.makeText(
            this,
            getString(feedback.messageRes, *feedback.args.toTypedArray()),
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private companion object {
        const val TAG = "ShareReceiver"
    }
}
