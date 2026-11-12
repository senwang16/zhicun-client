package com.zhicun.app.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图收录的透明中转页（2026-10-27，PRD v1.2 §3）。
 *
 * ## 为什么又需要一个 Activity
 * `MediaProjection` 的授权对话框必须由 Activity 拉起并接收结果，悬浮服务没有这个能力。
 * 本页不渲染任何内容，只负责：请求授权 → 隐藏气泡 → 截一帧 → 保存 → 恢复气泡 → 退出。
 *
 * ## 前台服务类型
 * API 34+ 规定：调用 `getMediaProjection` 之前，宿主前台服务必须已经以
 * `mediaProjection` 类型进入前台（manifest 里同步声明了权限与类型）。
 * 因此顺序是 **拉起授权 → 升级前台服务类型 → 取 MediaProjection → 建 VirtualDisplay**，
 * 任何一步失败都如实提示，绝不把"没有截图"说成"已收录"。
 *
 * ## 授权对话框期间不能 noHistory
 * manifest 里刻意没有给本页 `noHistory`：授权对话框是外部 Activity，打开期间本页失焦，
 * `noHistory` 会让系统回收它，返回时拿不到 result。
 */
@AndroidEntryPoint
class ScreenCaptureActivity : ComponentActivity() {

    @Inject
    lateinit var saveRecord: SaveRecordUseCase

    private var projectionManager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var finished = false

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "用户未授权截图")
            toast(R.string.floating_screenshot_denied)
            finishCapture()
            return@registerForActivityResult
        }
        capture(result.resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 气泡/面板必须在截图前消失，否则会出现在截出来的图里。
        // 只隐藏本页这一刻的实例，**不**改写“用户希望气泡可见”的全局意图：
        // 授权期间主界面可能被拉到前台（见 cleanupAfterCapture），那时它的 onResume
        // 会把 visibleWanted 置回 false，恢复时必须以那个意图为准。
        FloatingBubbleService.hideBubbleForCapture()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            Log.e(TAG, "MediaProjectionManager 不可用")
            toast(R.string.floating_screenshot_unavailable)
            finishCapture()
            return
        }
        projectionManager = manager
        try {
            consentLauncher.launch(captureConsentIntent(manager))
        } catch (e: Exception) {
            Log.e(TAG, "拉起截图授权失败", e)
            toast(R.string.floating_screenshot_unavailable)
            finishCapture()
        }
    }

    /**
     * 截图授权 Intent。
     *
     * ## 为什么要显式指定“默认显示器”（2026-10-28 模拟器实测）
     * 无参的 `createScreenCaptureIntent()` 在 Android 15（API 35）默认弹出
     * “Share or record an app”：用户点「截图」后还要再选一个应用，选到智存自己时
     * 截到的就是智存界面，而且会把智存带到前台 —— 与“一键截取当前屏幕”的语义不符。
     * `createConfigForDefaultDisplay()` 让系统直接以整个屏幕的授权流程呈现。
     *
     * API 34 才有 `MediaProjectionConfig`；Android 10–13 仍然走无参重载。
     */
    private fun captureConsentIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }

    private fun capture(resultCode: Int, data: Intent) {
        val manager = projectionManager ?: return finishCapture()
        val upgradeStarted = FloatingBubbleService.raiseMediaProjectionForeground()
        // API 29+ 起 MediaProjection 就要求宿主处于前台服务；服务不在时继续调用只会抛异常
        if (!upgradeStarted) {
            Log.e(TAG, "前台服务未以 mediaProjection 类型运行，无法截屏")
            toast(R.string.floating_screenshot_failed)
            finishCapture()
            return
        }

        var mediaProjection: MediaProjection? = null
        try {
            mediaProjection = manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            // API 34+ 缺少 mediaProjection 前台服务类型时抛 SecurityException
            Log.e(TAG, "获取 MediaProjection 失败", e)
            toast(R.string.floating_screenshot_failed)
            finishCapture()
            return
        }
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection 为空")
            toast(R.string.floating_screenshot_failed)
            finishCapture()
            return
        }

        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, null)
        lifecycleScope.launch {
            // 授权对话框关闭有一个淡出动画（2026-10-28 用户实测）。
            // 回调一到就建虚拟屏，会把正在消失的确认框截进画面底部。
            //
            // 固定起步延迟（2026-11-07 回退）：上一版改为「等画面稳定」的状态机后，
            // 真机上经常拖到 3 秒总超时、超时那一刻缓冲区又恰好为空，直接失败
            // —— 用户实测就是「卡半天然后截屏失败」，比原来的固定延迟方案差得多。
            // 因此退回「固定延迟 + 轮询取帧」：500ms 覆盖 1x 动画缩放下约 200~300ms 的淡出，
            // 更慢的动画由随后的取帧轮询兜底。偶有底部残留可接受，透明页不长时间挂最上层优先。
            delay(CONSENT_DISMISS_DELAY_MS)
            val file = try {
                captureFrame(mediaProjection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "截图捕获失败", e)
                null
            }
            if (file == null) {
                toast(R.string.floating_screenshot_failed)
                finishCapture()
                return@launch
            }
            persist(file)
        }
    }

    /** 建虚拟屏、等一帧、写成 PNG。全部在主流程度过，写盘切到 IO。 */
    private suspend fun captureFrame(mediaProjection: MediaProjection): File? {
        val (width, height) = screenSize()
        if (width <= 0 || height <= 0) return null

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val display = mediaProjection.createVirtualDisplay(
            "zhicun-screenshot",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        virtualDisplay = display

        val image = awaitImage(reader) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val target = File(cacheDir, "$TEMP_DIR/screenshot-${System.currentTimeMillis()}.png")
                target.parentFile?.mkdirs()
                writePng(image, target)
                target
            } finally {
                image.close()
            }
        }
    }

    /**
     * 轮询取一帧（2026-11-07 回退）。
     *
     * ## 为什么不再「等画面稳定」
     * 上一版每帧按网格采样求"画面指纹"、先观察变化再等连续稳定帧才交付，
     * 真机上经常拖到总超时；超时那一刻缓冲区又可能恰好为空，直接失败
     * —— 用户实测就是「卡半天然后截屏失败」。帧指纹状态机对"授权页失焦后
     * 虚拟屏间歇性不出帧"没有兜底，越等越容易踩空。
     *
     * ## 现在的判据
     * 建完虚拟屏、等完 [CONSENT_DISMISS_DELAY_MS] 后，**拿到任意一帧就交付**。
     * 正常设备首帧几十毫秒内到达；[FRAME_RETRIES] × [FRAME_INTERVAL_MS]
     * （约 1.5 秒）仍拿不到，说明系统拒绝或画面受保护，如实报失败即可。
     *
     * ## Image 生命周期
     * `acquireLatestImage()` 会关闭 reader 队列里的旧帧，调用方持有并负责 close 返回的这张。
     */
    private suspend fun awaitImage(reader: ImageReader): Image? {
        repeat(FRAME_RETRIES) {
            reader.acquireLatestImage()?.let { return it }
            delay(FRAME_INTERVAL_MS)
        }
        Log.w(TAG, "等待截图首帧超时")
        return null
    }

    private fun writePng(image: Image, target: File) {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val paddedWidth = image.width + rowPadding / plane.pixelStride
        val padded = createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            }
            try {
                FileOutputStream(target).use { out ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("PNG 编码失败")
                    }
                }
            } finally {
                if (cropped !== padded) cropped.recycle()
            }
        } finally {
            padded.recycle()
        }
    }

    private suspend fun persist(file: File) {
        val result = try {
            saveRecord(
                input = RecordInput(
                    text = null,
                    supportedUris = listOf(
                        InputUri(uri = "file://${file.absolutePath}", mimeType = "image/png"),
                    ),
                ),
                captureSource = CaptureSource.SCREENSHOT,
                now = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "截图保存失败", e)
            null
        }

        if (result == null) {
            // 临时文件保留：截图内容只在这里有一份，删了就真的没了
            toast(R.string.floating_screenshot_failed)
        } else if (result.savedAssetCount == 0 || result.failedAssetCount > 0) {
            // 记录建了但图片没落盘（复制失败，或 MIME 被判为不支持而跳过）：
            // 不能告诉用户"已收录"，否则他去详情页会发现是空的
            Log.e(
                TAG,
                "截图附件落盘失败 recordId=${result.recordId} " +
                    "saved=${result.savedAssetCount} failed=${result.failedAssetCount} " +
                    "skipped=${result.skippedAssetCount}",
            )
            toast(R.string.floating_screenshot_failed)
        } else {
            file.delete()
            toast(R.string.floating_screenshot_saved)
        }
        finishCapture()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户从系统状态栏停止共享：释放资源，但不再重复 finish（正常路径已释放）
            releaseResources(stopProjection = false)
        }
    }

    private fun releaseResources(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val active = projection
        projection = null
        if (active != null) {
            runCatching { active.unregisterCallback(projectionCallback) }
            if (stopProjection) {
                runCatching { active.stop() }
            }
        }
    }

    private fun finishCapture() {
        if (finished) return
        finished = true
        cleanupAfterCapture()
        finish()
    }

    /**
     * 恢复全局状态（幂等）：释放投影 → 降回前台服务类型 → 让气泡按**用户意图**恢复可见。
     *
     * 必须与 [finishCapture] 共用：授权期间按返回键、系统回收或"不保留活动"会让
     * 本页直接销毁而不走任何结果回调，漏掉这一步就会留下"气泡永久不可见 +
     * 服务一直占用 mediaProjection 类型"的假状态（PRD v1.2 §3.2）。
     *
     * 气泡不能在这里无条件显示（2026-10-28 修复）：单应用授权模式会把主界面带到
     * 前台，它的 `onResume` 已经按"主界面在前台就不显示气泡"把意图置为 false；
     * 无条件 `setBubbleVisible(true)` 会把气泡重新压到主界面上。恢复交给
     * [FloatingBubbleService.restoreBubbleAfterCapture]，由它读取当前意图决定。
     */
    private fun cleanupAfterCapture() {
        releaseResources(stopProjection = true)
        FloatingBubbleService.downgradeMediaProjectionForeground()
        FloatingBubbleService.restoreBubbleAfterCapture()
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            cleanupAfterCapture()
        }
        releaseResources(stopProjection = true)
        super.onDestroy()
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "ScreenCapture"
        const val TEMP_DIR = "screenshots"

        /**
         * 建虚拟屏后的起步延迟：等授权对话框的淡出动画走完（2026-11-07 回退为固定值）。
         *
         * 500ms 覆盖 1x 动画缩放下约 200~300ms 的淡出；开发者选项把动画拖到 10x 时
         * 仍可能截到底部残留，但换来的是「不再卡到超时、不再随机失败」——
         * 用户实测的这个优先级更高。
         */
        const val CONSENT_DISMISS_DELAY_MS = 500L

        /** 两次取帧的轮询间隔 */
        const val FRAME_INTERVAL_MS = 50L

        /** 取帧重试次数：50ms × 30 ≈ 1.5 秒，超出即判定失败（不再干等 3 秒） */
        const val FRAME_RETRIES = 30
    }
}
