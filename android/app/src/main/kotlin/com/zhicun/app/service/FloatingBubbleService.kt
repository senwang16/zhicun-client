package com.zhicun.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.zhicun.app.MainActivity
import com.zhicun.app.domain.audio.VoiceRecorder
import com.zhicun.app.domain.floating.BubbleEdge
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.domain.usecase.SaveVoiceNoteUseCase
import com.zhicun.app.presentation.floating.BUBBLE_TAB_HEIGHT
import com.zhicun.app.presentation.floating.BUBBLE_TAB_WIDTH
import com.zhicun.app.presentation.floating.QuickCaptureAction
import com.zhicun.app.presentation.floating.WindowComposeHost
import com.zhicun.app.presentation.floating.routeFor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 悬浮气泡的宿主 Service（M13，T-007 决策：原生 `TYPE_APPLICATION_OVERLAY`）。
 *
 * ## 为什么必须是前台服务
 * 气泡要在**用户使用其他应用时**一直存在。Android 8 起后台 Service 会被系统随时回收，
 * 普通 Service 挂上去的窗口会随之消失 —— 用户会看到气泡"用着用着就没了"。
 * 前台服务 + 一条低优先级常驻通知是唯一稳定做法，代价是通知栏多一行（文案已说明用途）。
 *
 * ## 为什么"从后台拉起 Activity"能成立
 * Android 10 起禁止后台启动 Activity，但**持有 `SYSTEM_ALERT_WINDOW` 权限的应用是豁免的**。
 * 本 Service 只在悬浮窗权限已授予时才会运行，因此面板里的四个入口都能直接 startActivity。
 * 这也解释了为什么权限一旦撤销就必须停掉服务：不停的话服务还在，但点什么都没反应。
 *
 * ## 为什么气泡和面板是两个窗口
 * 一个窗口装两者时，窗口尺寸要随"展开/收起"变化，而 `WRAP_CONTENT` 的窗口在改变尺寸后
 * 贴边位置会漂移，需要二次校正。两个独立窗口各自固定尺寸，位置由贴边规则直接算出，
 * 逻辑上简单得多，也不需要在气泡内部再做一层偏移布局。
 *
 * ## 收起态（2026-10-24 二次改版）：别人一摸屏幕就收到旁边
 * 常显的 48dp 实心圆在别人应用上非常抢眼。初版用闲置 4 秒定时收起，用户反馈不要：
 * 定时器要么收得太早（正在阅读刚弹出的内容）要么太晚（早就该收了）。
 * 现在改成**事件驱动**：窗口带 `FLAG_WATCH_OUTSIDE_TOUCH`，屏幕上任何不属于
 * 气泡的触摸**开始**时，窗口会收到一个 `ACTION_OUTSIDE` —— 收到就收成贴边
 * 细条（[BUBBLE_TAB_WIDTH] × [BUBBLE_TAB_HEIGHT]，半透明）。点一下只负责
 * "叫回完整气泡"，再点才执行动作 —— 误触的代价从"多存一条记录"降为"气泡张开了"。
 * 用户在气泡上点击/拖拽走的是正常事件管道，不会误发 OUTSIDE；主界面在前台
 * （气泡隐藏）时收到 OUTSIDE 直接忽略。
 * 面板同机制：点面板外 = 关面板。
 *
 * 窗口尺寸是 `LayoutParams` 里显式给死的，形态切换时由 [applyBubbleLayout] 同时重算
 * 尺寸与贴边坐标，因此不存在"改小窗口后贴边漂移"的问题。
 *
 * 本文件只保留生命周期、窗口编排的入口与动作分发
 * （AGENTS.md §2.1 单文件 400 行上限，2026-10-25 拆分）：
 * - 通知构建见 [FloatingNotification]；
 * - WindowManager 工具见 `FloatingWindows.kt`；
 * - 气泡窗口编排（挂载/贴边/拖动/收起态）见 `FloatingBubbleWindow.kt`；
 * - 快捷面板窗口见 `FloatingPanel.kt`。
 *
 * 后两者的实现是**本类的扩展函数**（不是独立类）：它们的函数体与本类的实例状态
 * （窗口句柄、LayoutParams、形态状态）耦合很深，拆成独立类需要额外注入多个依赖与
 * 回调才能等价。扩展函数让函数体逐行照搬、行为零变化，代价是相关成员放宽到 `internal`。
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    @Inject
    lateinit var settings: FloatingBubbleSettings

    @Inject
    lateinit var voiceRecorder: VoiceRecorder

    @Inject
    lateinit var saveVoiceNote: SaveVoiceNoteUseCase

    /**
     * 「长按录音」编排（2026-10-26）。在 [onCreate] 建立，负责录音与顶部计时条窗口。
     * 必须晚于 [composeHost.attach]：计时条也是 ComposeView，需要同一套 ViewTree owner。
     */
    internal lateinit var recording: BubbleRecordingController

    internal lateinit var windowManager: WindowManager
    internal val composeHost = WindowComposeHost()

    internal var bubbleView: ComposeView? = null
    internal var panelView: ComposeView? = null

    /**
     * 面板最近一次被「窗外触摸」关闭的时刻（`elapsedRealtime`）。
     *
     * 面板开着时点气泡，这次 DOWN 会同时到达面板（OUTSIDE）和气泡（点击）：
     * 若 OUTSIDE 先关掉面板，气泡的点击随后会把"已关闭"当成"未打开"，
     * 又去读剪贴板、为空时再开菜单 —— 菜单关了又开。用这个时间戳把紧随其后的
     * 点击吞掉（见 `togglePanel` 的宽限期）。
     */
    internal var panelDismissedAt: Long = 0L

    internal val bubbleParams = newFloatingLayoutParams()

    /**
     * 气泡的显示形态，**唯一的真源**（2026-10-24 实测修正）。
     *
     * ## 为什么改成 Compose 状态
     * 旧写法在形态变化时再调一次 `ComposeView.setContent { ... }` 来"换内容"。实测（模拟器
     * API 35）第二次 `setContent` 并不会换掉已挂载的 composition：窗口已经变成 48dp 的
     * 方形，画出来的仍是 16dp 小尾巴贴满整个窗口 —— 收缩正常、展开时"尾巴变大尾巴"。
     * 顺带说明旧代码里"面板展开时把图标换成叉号"其实也从未生效（面板挡住气泡，没人发现）。
     *
     * 改成 `mutableStateOf` 后只有一份状态，`BubbleContent` 读它、重组由 Compose 自己驱动，
     * 不再依赖"再 setContent 一次"这种没有保证的行为。
     *
     * 另外：窗口是定尺寸的，Compose 拿到的约束是紧的（min == max == 窗口边长），
     * 所以内容尺寸被强制等于窗口尺寸 —— 两者必须由 [applyBubbleLayout] 一起算，不能各算各的。
     */
    internal val bubbleForm = mutableStateOf(BubbleForm())

    internal val expanded: Boolean get() = bubbleForm.value.expanded
    internal val collapsed: Boolean get() = bubbleForm.value.collapsed

    internal fun updateBubbleForm(transform: (BubbleForm) -> BubbleForm) {
        bubbleForm.value = transform(bubbleForm.value)
    }

    /**
     * 用户是否希望气泡可见（主界面在前台时临时隐藏，不改用户设置）。
     *
     * ## 为什么初值是 `false` 而不是 `visibleWanted`（2026-11-10 Bug 3）
     * 原来在字段初始化时读静态意图，于是存在一个窗口：Service 实例构造完成、还没执行到
     * [onCreate] 末尾的 `instance = this` 时，`MainActivity.onPause` 把意图改成 true 并调
     * [setBubbleVisible] —— 那一刻 `instance` 还是 null，请求只落在静态意图上，而本字段
     * 已经是过期的 false。之后 `ensureBubble()` 按 false 把窗口挂成 GONE，用户看到的是
     * "服务活着、气泡不见了"，要再进出一次 App 才恢复。
     *
     * 因此本字段不再自己读一次意图：可见性只在 [onStartCommand] 里
     * `applyBubbleVisibility(visibleWanted)` 回放一次，读的永远是那一刻的事实。
     */
    internal var visible = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 显示变化监听（2026-10-24，修"横屏回来气泡就不见了"）。
     *
     * 真正的原因是**没人告诉气泡"屏幕换了尺寸"**，见 [relocateForViewport]。
     * 这里选 `DisplayListener` 而不是只靠 [onConfigurationChanged]：远控/投屏类应用
     * 会改显示器分辨率而未必触发配置变化，而 `onDisplayChanged` 恰好是"显示器参数变了"
     * 这个事实的直系信号（且在指标更新之后回调，算出来的尺寸是新的）。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            // 外接屏/虚拟屏也会走到这里，只认手机主屏
            if (displayId != Display.DEFAULT_DISPLAY) return
            scheduleViewportSync()
        }
    }

    /**
     * 视口同步的**合并器**：两个触发信号在 [VIEWPORT_SYNC_DELAY_MS] 内先后到达时只算一次。
     *
     * ## 为什么不能立刻算（2026-10-24 实测，API 35）
     * 改屏幕尺寸那一刻 `onDisplayChanged` 先到，但 `currentWindowMetrics` 还是**旧**尺寸：
     * 立刻算会把气泡先挪到旧位置，40ms 后配置回调再挪回来 —— 两次 `updateViewLayout`，
     * 慢设备上就是肉眼可见的「跳一下」。延迟一小段等指标落地，两个信号自然合并成一次。
     *
     * 代价是重新贴边晚 100ms，肉眼无感。
     */
    private val viewportSync = Runnable { relocateForViewport() }

    private fun scheduleViewportSync() {
        mainHandler.removeCallbacks(viewportSync)
        mainHandler.postDelayed(viewportSync, VIEWPORT_SYNC_DELAY_MS)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        composeHost.attach()
        recording = BubbleRecordingController(this, voiceRecorder, saveVoiceNote)
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(displayListener, mainHandler)
        instance = this
    }

    /**
     * 旋转 / 分屏 / 远控改了分辨率时的兜底触发点。
     *
     * 与 [displayListener] 是**同一个动作的两个触发信号**，不是两套判据：
     * 两者都只调 [scheduleViewportSync]，由它合并成一次计算。
     * 留两条是因为 `Service` 的配置回调在不同 ROM 上时机差异较大，
     * 而显示器回调也不是所有定制 ROM 都老实派发。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
        scheduleViewportSync()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(viewportSync)
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)
        removeBubble()
        removePanel()
        recording.onDestroy()
        composeHost.detach()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 权限可能在服务运行期间被撤销（用户去系统设置里关掉）。没有权限时 addView 会抛
        // SecurityException，不如在这里就收摊，并让设置页如实显示"未授权"。
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，服务不启动")
            // 仍然先走一次 startForeground 再停：本服务也可能由 BootCompletedReceiver 从
            // **后台**用 startForegroundService 拉起（2026-10-24），而系统要求这种服务
            // 必须在 5 秒内进入前台。直接 stopSelf 会触发
            // ForegroundServiceDidNotStartInTimeException —— 一个只在"判闸通过后权限被撤销"
            // 的极端竞态里才出现、且现场看着毫无道理的崩溃。先进前台再退出是安全的：
            // 通知最多闪一帧，而崩溃会杀掉整个进程。
            startFloatingForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        startFloatingForeground()
        ensureBubble()
        // 可见性只在这里按"当前事实"回放（2026-11-10 Bug 3）。
        // 构造到 onStartCommand 之间可能已有 onPause / 截图隐藏改过可见性：
        // 静态意图是唯一真源，实例字段与窗口状态都从它派生，不留第二份判据。
        applyBubbleVisibility(visibleWanted)
        return START_STICKY
    }

    /**
     * 用户划掉最近任务时的兜底（2026-10-24 荣耀 MagicOS 实机反馈）。
     *
     * AOSP 上这里没什么可做的：前台服务不随任务结束，气泡本来就还在（API 35 模拟器实测）。
     * 但个别 ROM 在划掉任务时不会再走一次 `MainActivity.onPause`，于是"主界面已离开"
     * 这个结论没被送达，气泡可能停留在 GONE。这里补上这一笔。
     *
     * 它救不了"划掉即强停"的系统：进程都没了，回调也跑不到；那类系统只能靠
     * 锁最近任务 + 自启动/后台活动白名单，见设置页「后台常驻」的说明。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "任务被移除，服务仍在运行，确认气泡可见")
        // 静态意图也要同步：若进程存活而服务之后再重建，新实例仍按"可见"初始化
        visibleWanted = true
        applyBubbleVisibility(true)
    }

    /**
     * 不提供绑定：气泡没有对外暴露的接口，调用方只需要"起/停"，
     * 那是 `startService` / `stopService` 的事。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 动作

    internal fun onAction(action: QuickCaptureAction) {
        collapsePanel()
        when (action) {
            // 截图需要 MediaProjection 授权对话框：由透明 Activity 拉起并接收结果
            QuickCaptureAction.SCREENSHOT -> startActivity(
                Intent(this, ScreenCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            // 剪贴板必须在"有前台 Activity"时读（Android 10+ 限制），因此走透明中转页
            QuickCaptureAction.CLIPBOARD -> startActivity(
                ClipboardCaptureActivity.launchIntent(this),
            )

            else -> {
                val route = routeFor(action)
                if (route == null) {
                    // 新增动作若既没有路由也没在这里登记，宁可留一条日志也不要猜一个目标页
                    Log.w(TAG, "动作 $action 没有可达入口，已忽略")
                } else {
                    openApp(route)
                }
            }
        }
    }

    private fun openApp(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // ---------------------------------------------------------------- 可见性

    /** 主界面进出时由 `FloatingBubbleController` 调用；不改用户的开关设置。 */
    fun applyBubbleVisibility(visible: Boolean) {
        this.visible = visible
        bubbleView?.visibility = if (visible) View.VISIBLE else View.GONE
        // 隐藏气泡时面板必须一起收掉，否则会留下一个孤儿面板在屏幕上
        if (!visible) collapsePanel()
    }

    companion object {
        private const val TAG = "FloatingBubble"

        /**
         * 视口变化后等多久再重算坐标。
         *
         * 100ms 的依据：实测同一轮改尺寸里，`onDisplayChanged` 与 `onConfigurationChanged`
         * 相差约 40ms，而前一棒读到的 `WindowMetrics` 还是旧值。一个够长到能盖住这段错位、
         * 又短到不被用户察觉的量级。
         */
        private const val VIEWPORT_SYNC_DELAY_MS = 100L

        @Volatile
        internal var instance: FloatingBubbleService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * "希望气泡可见"的全局意图。
         *
         * 必须存在伴生对象上而不是只存在实例字段里：`startForegroundService` 是异步的，
         * [MainActivity.onResume] 调 [setBubbleVisible] 的那一刻服务很可能还没创建，
         * `instance` 为 null 时请求会被直接丢掉 —— 等它创建出来就按默认值显示，
         * 于是气泡浮在用户自己的界面上。存成静态意图后，晚到的服务会读到正确的值。
         */
        @Volatile
        private var visibleWanted: Boolean = true

        /** 供 Activity 通知"主界面进来了/离开了"。服务已存在时立即生效，否则记录意图。 */
        fun setBubbleVisible(visible: Boolean) {
            visibleWanted = visible
            val service = instance
            if (service == null) {
                // 服务可能还在启动途中：意图已经记下，服务 onStartCommand 会回放它
                Log.d(TAG, "服务尚未就绪，记录可见性意图 visible=$visible")
                return
            }
            service.applyBubbleVisibility(visible)
        }

        /**
         * 截图授权期间临时隐藏气泡（2026-10-28 修复）。
         *
         * 与 [setBubbleVisible] 的区别：**只改当前实例的可见性，不改 [visibleWanted]**。
         * 授权对话框期间 `MainActivity` 可能被带到前台（Android 14+ 单应用授权模式），
         * 它的 `onResume` 会用 [setBubbleVisible] 把意图置为 false；如果这里先把意图改成
         * false，截图结束时就无法区分"用户在主界面"与"我们临时隐藏"，气泡要么残留、
         * 要么永久消失。
         */
        fun hideBubbleForCapture() {
            instance?.applyBubbleVisibility(false)
        }

        /**
         * 截图结束：按**当前用户意图**恢复气泡（2026-10-28 修复）。
         *
         * - 从桌面/其他应用截图：`visibleWanted` 仍为 true → 恢复显示；
         * - 授权流程把主界面带到前台：主界面 `onResume` 已把意图置为 false → 保持隐藏，
         *   不会出现"主界面上还压着一个气泡"的假状态；
         * - 服务本来就没运行（用户没开气泡）→ 实例为空，空操作。
         */
        fun restoreBubbleAfterCapture() {
            if (visibleWanted) instance?.applyBubbleVisibility(true)
        }

        /**
         * 请求展开快捷面板（附录 T：剪贴板为空时点气泡 = 展开面板）。
         *
         * 由 [ClipboardCaptureActivity] 在"剪贴板读不到内容"时调用——读剪贴板必须
         * 在前台 Activity 里做，而展开面板是 Service 的窗口操作，只能回到这里。
         * 服务没在跑（气泡本来就不在）时静默忽略：面板没有锚点，展开无意义。
         */
        fun requestPanelExpand() {
            val service = instance ?: return
            // 剪贴板中转页可能是在气泡已收起时被通知栏拉起的：先恢复完整气泡并确保可见，
            // 否则面板贴着一个 16dp 的尾巴展开，用户看不出菜单是从哪来的。
            service.applyBubbleVisibility(true)
            service.setCollapsed(false)
            service.expandPanel()
        }

        /**
         * 「悬浮窗常在」开关变化时即时生效（2026-10-26）：若气泡处于贴边细条，
         * 立刻展开为完整气泡。服务没在跑时静默忽略（气泡本来就不在）。
         */
        fun applyAlwaysVisible() {
            instance?.expandIfCollapsed()
        }
    }
}

/**
 * 气泡窗口的形态快照（2026-10-24）。
 *
 * 三个字段放在一个不可变对象里，是为了让"形态变化"变成一次 `copy` + 一次赋值：
 * 若拆成三个独立状态字段，展开与收起之间必然出现"expanded=true 而 collapsed=true"
 * 的中间帧，那一帧的窗口尺寸与内容对不上，看起来就是闪一下。
 */
internal data class BubbleForm(
    val expanded: Boolean = false,
    val collapsed: Boolean = false,
    val edge: BubbleEdge = BubbleEdge.RIGHT,
)
