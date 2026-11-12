package com.zhicun.app.data.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.zhicun.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/**
 * 把导出包交给分享面板（M15）。
 *
 * ## 这里为什么不断言"分享面板真的弹出来了"
 * `share()` 的第一步是 `FileProvider.getUriForFile`，它的根路径匹配依赖系统行为；
 * 在 Windows 上的 Robolectric 里恒失败（路径本身一致，仍报
 * "Failed to find configured root"），所以"成功路径"在 JVM 上无法复现。
 * 与其写一条永远只走失败分支的假测试，不如把**最容易错、错了又不报错**的两点抽出来直接断言：
 *
 * 1. `FLAG_GRANT_READ_URI_PERMISSION`：少了它分享面板照常弹出、也"发送成功"，
 *    但接收方读到的是空文件 —— 用户不会怀疑到这个 App 头上；
 * 2. `type = application/zip`：接收方（聊天/邮件/文档应用）靠它决定收不收、怎么存。
 *
 * 另外两条走得通的分支照样钉住：路径超出声明范围时必须**安静放弃**，不能把导出带崩。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidArchiveShareLauncherTest {

    private lateinit var application: Application
    private lateinit var launcher: AndroidArchiveShareLauncher

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        launcher = AndroidArchiveShareLauncher(application)
    }

    @Test
    fun ShareIntent_grantsReadPermissionAndDeclaresZip() {
        val uri = Uri.parse("content://${application.packageName}.fileprovider/exports/a.zip")

        val intent = launcher.shareIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(
            "没有读权限，接收方拿到的是空文件",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(
            "邮件/聊天应用会把主题当成标题显示",
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
    }

    /**
     * 可见范围必须**逐个窄目录声明**，不能出现整个 filesDir / cacheDir。
     *
     * `file_paths.xml` 里每多一行，就把私有数据（数据库、附件、草稿）多摆到一个
     * URI 可指的位置上。这条断言没有技术含量，但它是唯一一个"改坏了会静默"的地方：
     * 多加一行 `<files-path name="all" path="/" />` 功能照样正常，只有安全边界没了。
     *
     * 2026-10-24 新增 `files-path:assets/`：详情页要把附件交给系统应用打开
     * （PDF 阅读器 / 相册 / 播放器），私有 `file://` 从 API 24 起不能跨应用传，
     * 必须经 FileProvider。**仍然只开 assets 子目录**，不是整个 filesDir。
     */
    @Test
    fun FilePathsXml_exposesOnlyNarrowSubdirectories() {
        val parser = application.resources.getXml(R.xml.file_paths)
        val declared = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name != "paths") {
                declared += "${parser.name}:${parser.getAttributeValue(null, "path")}"
            }
        }

        assertEquals(listOf("cache-path:exports/", "files-path:assets/"), declared)
        assertTrue(
            "任何一条声明都不许指向存储根目录，实际 $declared",
            declared.none { it.endsWith(":/") || it.endsWith(":") },
        )
    }

    /**
     * 拿不到 content URI 时必须是"安静放弃"，而且**要把失败告诉调用方**。
     *
     * 从前这里返回 `Unit`，于是设置页永远显示"导出完成" —— 包躺在 cacheDir 里，
     * 用户没有任何入口能取到它。现在返回值就是界面唯一的信号（审核 P2）。
     */
    @Test
    fun Share_unavailableUriReportsFailure() {
        val outside = File(application.cacheDir, "not-exported.zip").apply { writeText("x") }

        val shared = launcher.share(outside)

        assertFalse("拿不到 content URI 必须返回 false", shared)
        assertNull("也不该拉起任何界面", shadowOf(application).nextStartedActivity)
    }

    /** 面板正常拉起时返回 true —— 界面据此才敢说"导出完成"。 */
    @Test
    fun Launch_panelOpenedReturnsTrue() {
        val uri = Uri.parse("content://${application.packageName}.fileprovider/exports/a.zip")

        assertTrue(launcher.launch(uri))
        assertNotNull("必须真的拉起过界面", shadowOf(application).nextStartedActivity)
    }

    /**
     * 极端裁剪的 ROM 上 `startActivity` 会抛 `ActivityNotFoundException`。
     * 这时必须返回 false 而不是把已完成的导出变成一次崩溃。
     */
    @Test
    fun Launch_panelUnavailableReportsFailure() {
        val uri = Uri.parse("content://${application.packageName}.fileprovider/exports/a.zip")
        shadowOf(application).checkActivities(true)

        assertFalse("起不来面板就是没分享成", launcher.launch(uri))
    }
}
