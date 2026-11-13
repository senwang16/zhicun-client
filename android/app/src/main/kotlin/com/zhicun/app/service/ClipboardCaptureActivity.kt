package com.zhicun.app.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zhicun.app.MainActivity
import com.zhicun.app.R
import com.zhicun.app.domain.usecase.ClipboardCaptureResult
import com.zhicun.app.domain.usecase.CaptureClipboardUseCase
import com.zhicun.app.localizedContext
import com.zhicun.app.presentation.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 剪贴板收录的透明中转页（M13）。
 *
 * ## 为什么非要一个 Activity
 * Android 10 起只有默认输入法或**获得焦点**的应用能读剪贴板。悬浮球由 Service 显示，
 * App 此时不在前台，Service 里读剪贴板只会稳定拿到 `null` —— 用户看到的就是"点了没反应"。
 * 因此必须拉起一个 Activity 拿到前台状态，读完立刻 `finish()` 把用户交还回去。
 *
 * 它以 `Theme.ZhiCun.ShareReceiver`（全透明、无动画）显示，视觉上等同于"点一下就有提示"。
 *
 * ## 非成功路径一律回到快捷面板（PRD v1.2 §2.2、§3.1）
 * - **空：不提示，直接展开悬浮面板**（2026-11-08 用户反馈）；
 * - 非文字 / 读不到：各有各的提示，然后展开面板，用户可在那里记一笔或截图；
 * - 重复：轻提示 + 展开面板（PRD 明确要求"不新增记录，打开菜单"）；
 * - 保存失败：明确提示失败并跳新建页（内容还在剪贴板里，手动粘贴是可靠的补救），
 *   绝不显示"已保存"。
 *
 * 成功的保存**不展开面板**：用户要的只是"存下来"，多弹一个菜单反而打断。
 */
@AndroidEntryPoint
class ClipboardCaptureActivity : ComponentActivity() {

    /** 语言偏好早于任何组件生命周期（P11-05）：Toast/RemoteViews 必须用包裹后的资源。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    @Inject
    lateinit var captureClipboard: CaptureClipboardUseCase

    /** 保证只读一次：焦点回调与兜底计时器可能都想触发。 */
    private var captured = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 硬兜底：从 `onCreate` 起最多容忍这么久，到点无条件 `finish()`。
     *
     * ## 为什么必须有一个"与业务无关"的截止时间（2026-11-10 Bug 1）
     * 真机日志两次出现 `Activity top resumed state loss timeout` /
     * `Activity pause timeout for ActivityRecord{... ClipboardCaptureActivity}`：
     * 透明页卡在栈顶没结束，之后每次 `startActivity(LAUNCH_MULTIPLE)` 都复用这个
     * 已死的 task，`onCreate` 不再执行 —— 用户看到的是"点气泡彻底没反应，
     * 直到 force-stop 应用"。
     *
     * 协程里的每一步都可能被系统拖住（等焦点、数据库锁、Toast 队头），
     * 因此这里不再"假设某一步一定会返回"：到点就结束，宁可这次收录失败，
     * 也不把一个吞触摸的窗口留在用户屏幕上。取 2 秒——正常路径（≤600ms 等焦点 +
     * 一次落库）远小于它，又明显低于系统的生命周期超时。
     */
    private val finishWatchdog = Runnable {
        if (!isFinishing) {
            Log.w(TAG, "中转页 ${HARD_FINISH_TIMEOUT_MS}ms 内未完成，强制结束")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        // 透明页 + 无界面：不需要 setContent，窗口背景由主题置空
        //
        // **不能在这里直接读剪贴板**（2026-10-24 全项目审查 M-3）：类注释里"必须获得焦点"
        // 是这个功能的前提，而 onCreate 时窗口还没拿到焦点，`getPrimaryClip` 会稳定返回 null，
        // 表现为"点剪贴板每次都跳去新建页"。改为等焦点就绪（见 onWindowFocusChanged）。
        //
        // 兜底：个别 ROM / 场景（从桌面、通知栏冷启动）可能不发焦点回调，不能让这个透明页
        // 一直挂在最上层把输入吃掉 —— 超时后无论如何读一次，行为退回到改造前的水平。
        window.decorView.postDelayed({ captureOnce() }, FOCUS_TIMEOUT_MS)
        // 与业务无关的硬截止：无论上面哪一步被系统拖住，到点都要把窗口交还出去
        mainHandler.postDelayed(finishWatchdog, HARD_FINISH_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) captureOnce()
    }

    /**
     * 提前一次尝试（2026-11-08 性能优化，2026-11-10 修正为"下一轮消息"）。
     *
     * 冷启动时窗口常已获得焦点、只是 `onWindowFocusChanged` 还要晚一帧派发，
     * 这里先看 `hasWindowFocus()` 抢一拍。
     *
     * ## 为什么改成 `post` 而不是在 onResume 里同步读（2026-11-10 Bug 1）
     * 读剪贴板失败/成功都会很快走到 `finish()`。若在 onResume 的调用栈里就把 Activity
     * 销毁，系统还没走完"top resumed"状态落定，真机上就会出现
     * `Activity top resumed state loss timeout`，并留下一个无法再被复用的死 task ——
     * 之后每次点气泡都"没反应"。post 到下一轮消息：resume 已落定，仍早于焦点回调那一帧。
     */
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        window.decorView.post {
            if (!isFinishing && window.decorView.hasWindowFocus()) captureOnce()
        }
    }

    /**
     * 同一个实例被再次拉起时的入口（配合清单/启动参数的去重与 `CLEAR_TASK`）。
     *
     * 透明页只活几百毫秒，正常路径上这里几乎不会触发；一旦触发，说明上一次已经收尾
     * （或正在收尾）却又被点了第二下 —— 允许重读一次，避免用户看到"点了没反应"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent")
        if (isFinishing) return
        captured = false
        captureOnce()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(finishWatchdog)
        Log.i(TAG, "onDestroy finishing=$isFinishing")
        super.onDestroy()
    }

    private fun captureOnce() {
        if (captured) return
        captured = true
        lifecycleScope.launch { capture() }
    }

    private suspend fun capture() {
        try {
            val result = readClipboard()
            when (result) {
                is ClipboardCaptureResult.Saved -> {
                    toast(getString(R.string.floating_clipboard_saved, result.preview))
                }

                // 去重命中：内容没变就说明这已经是同一次收藏，不要再建一条。
                // PRD v1.2 §2.4：重复时保留剪贴板内容并打开菜单，方便用户改用手动方式。
                is ClipboardCaptureResult.Duplicate -> {
                    toast(getString(R.string.floating_clipboard_duplicate))
                    FloatingBubbleService.requestPanelExpand()
                }

                ClipboardCaptureResult.Empty -> {
                    // 2026-11-08 用户反馈：剪贴板是空的就别提示了，直接开快捷菜单。
                    // 原实现先 toast「剪贴板为空」再展开面板，用户要连看两条反馈、
                    // 多一次打扰；空是最常见的状态（大多数人点气泡前没复制过东西），
                    // 静默展开菜单、把「记一笔 / 截图」摆到眼前更直接。
                    FloatingBubbleService.requestPanelExpand()
                }

                ClipboardCaptureResult.Unsupported -> {
                    toast(getString(R.string.floating_clipboard_unsupported))
                    FloatingBubbleService.requestPanelExpand()
                }

                ClipboardCaptureResult.Unavailable -> {
                    toast(getString(R.string.floating_clipboard_unavailable))
                    FloatingBubbleService.requestPanelExpand()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失败（磁盘满、库锁）时内容还在剪贴板里，引导用户进新建页再存一次
            Log.e(TAG, "剪贴板收录失败", e)
            toast(getString(R.string.floating_clipboard_failed))
            openCreatePage()
        } finally {
            if (!isFinishing) {
                Log.i(TAG, "capture 结束，finish 中转页")
                finish()
            }
        }
    }

    /**
     * 读剪贴板；读到空但**尚未获得窗口焦点**时先等焦点再重读一次。
     *
     * ## 为什么不能"读到空就判空"（2026-11-08 用户反馈）
     * Android 10+ 规定：只有默认输入法或**获得焦点**的应用能读剪贴板，未获焦点时
     * `getPrimaryClip()` 不抛异常、而是返回空。本页是透明中转页，从桌面/通知栏冷启动
     * 或某些 ROM 上 `onWindowFocusChanged(true)` 会晚一帧到达；若此时按"空"处理，
     * 用户明明复制了内容，点气泡却直接弹出快捷菜单 —— 看起来就是"有内容也不收录"。
     *
     * 因此空结果一律先等焦点（最多 [FOCUS_RETRY_MS]），拿到焦点后**重读**：
     * - 重读到内容 → 正常收录（用户要的"有内容就存"）；
     * - 重读仍空且已获焦点 → 真·空，静默展开菜单；
     * - 始终拿不到焦点 → 系统拒绝，按"读不到"提示（不能把系统限制说成"空"）。
     */
    private suspend fun readClipboard(): ClipboardCaptureResult {
        var result = captureClipboard()
        if (result !is ClipboardCaptureResult.Empty || window.decorView.hasWindowFocus()) {
            return result
        }

        val focused = withTimeoutOrNull(FOCUS_RETRY_MS) {
            while (!window.decorView.hasWindowFocus()) delay(FOCUS_POLL_MS)
            true
        } ?: false
        if (!focused) {
            Log.w(TAG, "等待焦点超时，按读不到剪贴板处理")
            return ClipboardCaptureResult.Unavailable
        }

        result = captureClipboard()
        if (result is ClipboardCaptureResult.Empty && !window.decorView.hasWindowFocus()) {
            Log.w(TAG, "重读时仍未获焦点，按读不到剪贴板处理")
            return ClipboardCaptureResult.Unavailable
        }
        return result
    }

    private fun openCreatePage() {
        startActivity(
            Intent(this, MainActivity::class.java)
                // 带 pasteHint=true：页面顶部给一条常驻的"长按可粘贴"提示（M13 D-1 折中）。
                // 比 Snackbar 好：Snackbar 几秒就消失，而用户还要先切输入法、再长按，来得及看。
                // 只剩"保存失败"一条路走到这里（附录 T）：内容还在剪贴板里，手动粘贴最稳。
                .putExtra(MainActivity.EXTRA_START_ROUTE, Routes.CREATE_PASTE_HINT),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 拉起这个中转页的**唯一入口**（单一事实源）。
         *
         * 气泡点击与面板「剪贴板」动作原本各拼一份 Intent 与 flags，这里收成一处：
         * 以后调整启动方式（例如再补一个 flag）不会只改到其中一条路径。
         */
        internal fun launchIntent(context: Context): Intent =
            Intent(context, ClipboardCaptureActivity::class.java)
                // 从 Service（没有 Activity 栈）发起，必须新建 task
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 清掉这个透明页遗留的 task（2026-11-10 Bug 1）。它是"几百毫秒就结束"的
                // 一次性页面，复用旧 task 只会复用到一个已死的 ActivityRecord ——
                // 真机实测死 task 在栈顶时，后续点击连 onCreate 都不再执行。
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // 透明页只负责读一次剪贴板：跳过启动/退出动画，
                // 少一帧过渡就少一份"点了在等"的体感（2026-11-08）。
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

        private const val TAG = "ClipboardCapture"

        /**
         * 焦点兜底的等待上限。取值权衡：太长会让用户觉得"点了没反应"，
         * 太短则可能在焦点到达之前就去读（等于没改）。正常设备上焦点回调在首帧内就到，
         * 这个计时器基本不会生效。
         */
        const val FOCUS_TIMEOUT_MS = 250L

        /** 读到空但无焦点时，等焦点的上限。焦点一般几十毫秒内到，这里留足余量 */
        const val FOCUS_RETRY_MS = 600L

        /** 等焦点时的轮询间隔：约一帧（16ms），比 40ms 少至少一拍延迟 */
        const val FOCUS_POLL_MS = 16L

        /**
         * 整个中转页从 `onCreate` 起的最长存活时间（硬兜底，见 [finishWatchdog]）。
         *
         * 2 秒的依据：正常路径的上界是「等焦点 600ms + 一次落库」，远小于它；
         * 而系统的 Activity 生命周期超时在秒级到十几秒级，2 秒能保证我们永远先收摊。
         */
        const val HARD_FINISH_TIMEOUT_MS = 2_000L
    }
}
