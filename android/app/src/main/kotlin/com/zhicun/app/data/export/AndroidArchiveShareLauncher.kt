package com.zhicun.app.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.zhicun.app.R
import com.zhicun.app.domain.export.ArchiveShareLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用系统分享面板发送导出的 ZIP（M15）。
 *
 * ## 为什么必须走 FileProvider
 * `file://` URI 从 API 24 起禁止跨应用传递（`FileUriExposedException`）。
 * 导出的包在 `cacheDir` 里，属于应用私有目录，必须用 content URI 授权给接收方。
 *
 * ## 为什么带 `FLAG_GRANT_READ_URI_PERMISSION`
 * 分享面板是**另一个应用**（甚至是文档应用、聊天应用），没有这个 flag 它读不到文件，
 * 用户会看到"已发送"但对方收到的是空文件/失败 —— 这类问题极难被发现。
 */
@Singleton
class AndroidArchiveShareLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ArchiveShareLauncher {

    override fun share(archive: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, authority(), archive)
        } catch (e: IllegalArgumentException) {
            // 路径不在 file_paths.xml 声明的范围内 / 文件已被系统清掉
            Log.e(TAG, "无法为导出包生成 content URI", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "生成 content URI 失败", e)
            return false
        }

        return launch(uri)
    }

    /**
     * 真正拉起分享面板，返回是否成功。
     *
     * 与 [shareIntent] 同样单独抽出来的理由：`FileProvider.getUriForFile` 在 Windows 上的
     * Robolectric 里恒失败，导致 [share] 的成功路径在 JVM 上永远走不到 ——
     * 于是"面板没起来但界面已经说导出完成"这个 bug 没有任何测试能钉住。
     * 把"起面板"这一步拆成独立方法后，传一个手搭的 URI 就能把两条分支都覆盖。
     */
    internal fun launch(uri: Uri): Boolean = try {
        context.startActivity(
            Intent.createChooser(shareIntent(uri), context.getString(R.string.export_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        // 极端裁剪 ROM 上连分享面板都起不来。包还在 cacheDir 里，界面要给"重新打开"的出口，
        // 否则用户看到"导出完成"却没有任何办法把文件取出去
        Log.e(TAG, "打开分享面板失败", e)
        false
    }

    /**
     * 把"分享这个 content URI"描述成一个 Intent。
     *
     * 单独抽出来是为了能在单测里直接断言那两样**错了也不报错**的东西：
     * `type`（接收方靠它决定要不要收下）与 `FLAG_GRANT_READ_URI_PERMISSION`
     * （少了它对方读到的是空文件）。整条 `share()` 在测试环境里跑不通 ——
     * FileProvider 的根路径匹配依赖系统行为，JVM 上无法复现。
     */
    internal fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_ZIP
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_share_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 与 Manifest 里 `android:authorities` 保持一致，靠 applicationId 拼接避免硬编码包名。 */
    private fun authority(): String = "${context.packageName}.fileprovider"

    private companion object {
        const val TAG = "ArchiveShare"
        const val MIME_ZIP = "application/zip"
    }
}
