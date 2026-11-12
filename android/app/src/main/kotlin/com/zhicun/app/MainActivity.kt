package com.zhicun.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.presentation.navigation.AppNavGraph
import com.zhicun.app.presentation.navigation.ExternalRoutes
import com.zhicun.app.presentation.theme.AppTheme
import com.zhicun.app.service.BubbleServiceHealth
import com.zhicun.app.service.FloatingBubbleController
import com.zhicun.app.service.FloatingBubbleService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 智存唯一 Activity。
 *
 * - `@AndroidEntryPoint`：使 Hilt 可向本 Activity 及其作用域下的 ViewModel 注入依赖。
 * - `enableEdgeToEdge()`：targetSdk 36 起系统强制 Edge-to-Edge，此处统一开启并向后兼容至 API 26；
 *   内容区通过各页 `Scaffold` 的 innerPadding（safe drawing insets）规避状态栏/导航栏遮挡。
 * - 页面与导航图自 M3 起由 [AppNavGraph] 承载，本类不再持有任何页面代码。
 *
 * ## M13：悬浮气泡的两处协作
 * 1. **面板入口跳转**：气泡面板要能把用户送到"新建/录音/收件箱"。Activity 已存在时
 *    再 `startActivity` 不会重建，只触发 [onNewIntent] —— 因此路由请求走一个
 *    [Channel] 交给 Compose 侧消费，而不是塞进启动参数（复用场景下永远读不到）。
 * 2. **进主界面隐藏气泡**：气泡浮在自己 App 上是纯噪声（挡住自己要看的界面），
 *    因此 `onResume` 隐藏、`onPause` 恢复。只改可见性，不动用户开关。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var floatingSettings: FloatingBubbleSettings

    /**
     * 待处理的跳转请求。用 `Channel` 而不是 `StateFlow`：
     * 同一路由可能被连续请求两次（点"录音"、返回、又点"录音"），
     * `StateFlow` 会因为在比较时"值没变"而丢掉第二次。
     */
    private val routeRequests = Channel<String>(Channel.BUFFERED)
    private val routes: Flow<String> = routeRequests.receiveAsFlow()

    /**
     * 悬浮服务健康检查（2026-10-27，PRD v1.2 §2.1）。
     *
     * 服务可能因权限撤销、系统回收或厂商后台清理而消失。`onCreate` 的 sync 只覆盖冷启动，
     * 进程存活期间从后台回来时没人重新对齐 —— 这里在 `onResume` 延迟复查一次：
     * 先尝试自动拉起，仍起不来才提示，避免把"用户自己关了"误报成异常。
     */
    private var healthCheckJob: Job? = null

    /** 每次进入前台只提示一次，防止用户在设置与主界面之间来回切时反复弹同一句。 */
    private var healthPrompted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统级开屏（附录 T）：必须在 super.onCreate 之前调用 —— 它负责读开屏主题
        // （Theme.ZhiCun.Starting）、把 postSplashScreenTheme 切回正式主题。
        // 不人为延迟：开屏时长 = 冷启动真实时长，退出动画由系统默认的
        // "图标放大淡出"承接，开屏结束即进收件箱（引导页路由由附录 T 后续接入）。
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        intent?.getStringExtra(EXTRA_START_ROUTE)?.let { dispatchRoute(it, "onCreate") }

        // 冷启动时对齐一次：上次开着气泡、进程被杀后重启，服务不会自动回来
        FloatingBubbleController.sync(this, floatingSettings)

        setContent {
            AppTheme {
                // Surface 兜住窗口背景：Compose 的 Scaffold 只覆盖自身区域，
                // 手势导航条后方由这一层提供底色，避免露出启动窗口的白色
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(navigateRequests = routes)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须 setIntent，否则后续 getIntent() 读到的还是旧的启动参数
        setIntent(intent)
        intent.getStringExtra(EXTRA_START_ROUTE)?.let { dispatchRoute(it, "onNewIntent") }
    }

    /**
     * 把外部塞进来的路由字符串交给 Compose 侧导航。
     *
     * **必须走白名单**（2026-10-24 全项目审查 L-1）：`EXTRA_START_ROUTE` 是 Intent extra，
     * 属于"外部可写"的输入——本 Activity 是 `exported=true` 的启动入口，虽然当前 Manifest
     * 没有声明自定义 scheme（普通应用无法直接构造一次精准的启动），但 adb、同签名的调试工具、
     * 以及将来若为深链加 scheme，都能把这个值设成任意字符串。之前直接 `trySend` 等于让外部
     * 数据决定内部导航目标：轻则落到一个不存在的路由（导航图抛异常/白屏），重则绕过入口处的
     * 状态约束。白名单让"能到达的页面"是一个封闭集合。
     */
    private fun dispatchRoute(raw: String, source: String) {
        val route = ExternalRoutes.sanitize(raw)
        if (route == null) {
            Log.w(TAG, "$source 收到未知路由，已忽略：$raw")
            return
        }
        Log.i(TAG, "$source 收到路由=$route")
        routeRequests.trySend(route)
    }

    override fun onResume() {
        super.onResume()
        // 回到自己的界面：气泡该让位（也顺手收掉可能还开着的面板）
        FloatingBubbleService.setBubbleVisible(false)
        scheduleFloatingServiceCheck()
    }

    override fun onPause() {
        super.onPause()
        FloatingBubbleService.setBubbleVisible(true)
    }

    /**
     * 延迟复查悬浮服务。
     *
     * 为什么要延迟：`startForegroundService` 只是把请求投递给系统，紧接着读 `isRunning`
     * 必然是 false —— 立刻判定会把**每次都正常**的冷启动报成"服务异常"。
     */
    private fun scheduleFloatingServiceCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = lifecycleScope.launch {
            delay(SERVICE_CHECK_DELAY_MS)
            if (FloatingBubbleService.isRunning) {
                healthPrompted = false
                return@launch
            }
            val wanted = FloatingBubbleController.shouldRun(
                enabled = floatingSettings.enabled,
                hasOverlayPermission = FloatingBubbleController.hasOverlayPermission(this@MainActivity),
            )
            if (!wanted) {
                // 用户本来就没开：不是异常，下次开启时重新允许提示
                healthPrompted = false
                return@launch
            }

            // 先补拉一次；用户在"后台弹界面/自启动"被限的机器上仍可能失败。
            // 但只有仍在 RESUMED 时才补拉：用户已经离开界面时从后台启动 FGS 既无意义，
            // 也会撞上 Android 12+ 的后台启动限制。
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            FloatingBubbleController.sync(this@MainActivity, floatingSettings)
            delay(SERVICE_RETRY_DELAY_MS)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch

            val status = FloatingBubbleController.healthStatus(
                enabled = floatingSettings.enabled,
                hasOverlayPermission = FloatingBubbleController.hasOverlayPermission(this@MainActivity),
                isRunning = FloatingBubbleService.isRunning,
            )
            if (status == BubbleServiceHealth.MISSING && !healthPrompted) {
                healthPrompted = true
                Log.w(TAG, "悬浮服务应运行但未启动")
                Toast.makeText(
                    this@MainActivity,
                    R.string.floating_service_not_running,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDestroy() {
        healthCheckJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivityRoute"

        /** 服务启动是异步的：晚一点再读运行态，避免把正常冷启动误判成异常。 */
        private const val SERVICE_CHECK_DELAY_MS = 1_200L

        /** 自动补拉后再等一轮；仍不在才提示用户去检查系统限制。 */
        private const val SERVICE_RETRY_DELAY_MS = 1_500L

        /**
         * 启动/复用 Activity 时要落地到哪个路由，取值必须落在
         * [com.zhicun.app.presentation.navigation.ExternalRoutes.allowed] 里，
         * 否则会被 [dispatchRoute] 丢弃（白名单见那里）。
         */
        const val EXTRA_START_ROUTE: String = "com.zhicun.app.extra.START_ROUTE"
    }
}
