package com.zhicun.app.service

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.zhicun.app.domain.floating.BubbleGeometry
import com.zhicun.app.presentation.floating.BUBBLE_SIZE
import com.zhicun.app.presentation.floating.BUBBLE_TAB_HEIGHT
import com.zhicun.app.presentation.floating.BUBBLE_TAB_WIDTH
import com.zhicun.app.presentation.floating.FloatingBubble
import com.zhicun.app.presentation.floating.px
import com.zhicun.app.presentation.theme.AppTheme

// 本文件承接从 FloatingBubbleService.kt 原样移出的「气泡窗口编排」区块
// （AGENTS.md §2.1 单文件 400 行上限）。
//
// 为什么用扩展函数而不是拆出独立类：这些方法的函数体与 Service 实例状态
// （窗口句柄、LayoutParams、形态状态）耦合很深，拆成独立类需要注入六七个
// 依赖 + 回调才能等价，改动面远大于"换个文件"。扩展函数能让函数体**逐行照搬**，
// 行为零变化；代价是 Service 的相关成员要放宽到 `internal`（同模块可见）。
//
// TAG 与 FloatingBubbleService 的伴生常量同值：日志标签必须保持一致，
// 否则按 tag 过滤日志的既有排查习惯会被打断。

private const val TAG = "FloatingBubble"

/**
 * 首次挂上气泡窗口。
 *
 * 位置按持久化的 [com.zhicun.app.domain.floating.FloatingBubbleSettings.position] 推导，
 * 与 [relocateForViewport] 共用同一条几何规则（纯函数见 `BubbleGeometry`）。
 */
internal fun FloatingBubbleService.ensureBubble() {
    if (bubbleView != null) return

    val size = BUBBLE_SIZE.px(this)
    val position = settings.position
    val (screenWidth, screenHeight) = floatingViewportSize()
    val (x, y) = BubbleGeometry.offsetOf(
        position = position,
        viewportWidth = screenWidth,
        viewportHeight = screenHeight,
        bubbleSize = size,
    )
    bubbleParams.width = size
    bubbleParams.height = size
    bubbleParams.x = x
    bubbleParams.y = y
    // 先落状态再 setContent：composition 第一帧就要读到正确的贴边侧
    updateBubbleForm { it.copy(edge = position.edge) }

    val view = ComposeView(this).apply {
        // 别人一摸屏幕就收（2026-10-24 用户裁决，替代闲置计时）。
        // setCollapsed(true) 是幂等的：已是尾巴时直接返回；面板开着时
        // 连带面板一起收掉（setCollapsed 会 removePanel）。
        watchOutsideTouch { collapseOnOutsideTouch() }
    }
    composeHost.installOn(view)
    view.setContent { BubbleContent() }

    windowManager.addViewSafely(view, bubbleParams) { stopSelf() }
    bubbleView = view
    view.visibility = if (visible) View.VISIBLE else View.GONE
}

/** 气泡窗口里的 Compose 内容。只读 [bubbleForm]，形态变化由重组完成，不再重复 `setContent`。 */
@Composable
internal fun FloatingBubbleService.BubbleContent() {
    val form = bubbleForm.value
    AppTheme {
        FloatingBubble(
            expanded = form.expanded,
            collapsed = form.collapsed,
            edge = form.edge,
            onClick = { togglePanel() },
            onDrag = { delta ->
                moveBubbleBy(delta.x.toInt(), delta.y.toInt())
            },
            onDragEnd = { snapBubble() },
            recording = recording.recording.value,
            onRecordStart = { FloatingBubbleService.startRecording() },
            onRecordEnd = { FloatingBubbleService.stopRecording() },
        )
    }
}

internal fun FloatingBubbleService.removeBubble() {
    bubbleView?.let { windowManager.removeViewSafely(it) }
    bubbleView = null
}

/**
 * 按当前形态（完整气泡 / 贴边小尾巴）重算窗口尺寸与贴边坐标。
 *
 * 尺寸与坐标必须**同一处**改：`WindowManager` 的宽高和 x/y 是两套字段，
 * 只改宽高会让"贴着右边的尾巴"因为窗口变窄而整体内缩一段，看起来像漂移。
 * 内容不需要跟着重画 —— [bubbleForm] 变化会触发重组。
 */
internal fun FloatingBubbleService.applyBubbleLayout() {
    val (width, height) = if (collapsed) {
        BUBBLE_TAB_WIDTH.px(this) to BUBBLE_TAB_HEIGHT.px(this)
    } else {
        BUBBLE_SIZE.px(this).let { it to it }
    }
    val (screenWidth, screenHeight) = floatingViewportSize()
    val oldHeight = bubbleParams.height
    // 以"当前竖直中心"为锚点：展开/收起两侧高度不同，按中心对齐才不会看到气泡跳动
    val centerY = bubbleParams.y + oldHeight / 2

    bubbleParams.width = width
    bubbleParams.height = height
    bubbleParams.x = BubbleGeometry.edgeX(bubbleForm.value.edge, screenWidth, width)
    bubbleParams.y = BubbleGeometry.keepCenterY(
        oldY = centerY - oldHeight / 2,
        oldHeight = oldHeight,
        newHeight = height,
        viewportHeight = screenHeight,
    )

    bubbleView?.let { windowManager.updateViewSafely(it, bubbleParams) }
}

/**
 * 视口变了（横竖屏切换、分屏、远控/投屏改分辨率）之后，按**持久化的位置**重新推导窗口矩形。
 *
 * ## 为什么必须显式做（2026-10-24 用户实测：横屏远控退出后气泡消失）
 * `bubbleParams.x` 是我们自己算出来的死值，**系统不会跟着屏幕旋转重算**；窗口又带
 * `FLAG_LAYOUT_NO_LIMITS`，越界既不报错也不裁剪。于是：横屏时贴边 `x = 2400 - 宽`
 * （或横屏里收起/展开时算了一次），回竖屏（宽 1080）后气泡整个留在屏幕外 ——
 * 竖屏看不见、再进横屏又能看见，而用户点不到也拖不回来，只能去设置里关掉再开。
 *
 * ## 为什么以 `settings.position` 为基准，而不是当前 x/y
 * 与 [ensureBubble] 必须用同一条规则，否则"横屏里被夹小的 y"会成为新的基准：
 * 竖屏 y=2000 → 横屏夹成 948 → 转回竖屏还留在 948，气泡自己往上跑了。
 * 以持久化位置重算则 **竖屏→横屏→竖屏 幂等**（几何是纯函数，见 BubbleGeometry）。
 *
 * ## 去重
 * 算出来的值与当前一致就不碰窗口：两个触发点可能前后脚到达，重复 `updateViewLayout`
 * 会让窗口闪一下。
 */
internal fun FloatingBubbleService.relocateForViewport() {
    val view = bubbleView ?: return
    val (screenWidth, screenHeight) = floatingViewportSize()
    val (x, y) = BubbleGeometry.offsetOfEdge(
        position = settings.position,
        viewportWidth = screenWidth,
        viewportHeight = screenHeight,
        viewWidth = bubbleParams.width,
        viewHeight = bubbleParams.height,
    )
    if (bubbleParams.x == x && bubbleParams.y == y) return

    Log.i(TAG, "视口变为 ${screenWidth}x$screenHeight，气泡重新贴边 x=$x y=$y")
    bubbleParams.x = x
    bubbleParams.y = y
    windowManager.updateViewSafely(view, bubbleParams)
    // 面板是贴着气泡算的，气泡挪了必须跟着走，否则会留在屏幕外
    if (expanded) positionPanel()
}

/**
 * 切换收起态。
 *
 * 收起时顺手把面板收掉：面板是贴在气泡旁边展开的，气泡一旦变成 16dp 的尾巴，
 * 面板就会悬在一个几乎看不见的锚点旁边，用户既不知道为什么还在，也不知道怎么关。
 */
internal fun FloatingBubbleService.setCollapsed(value: Boolean) {
    if (collapsed == value) return
    updateBubbleForm { it.copy(collapsed = value, expanded = if (value) false else it.expanded) }
    if (value) removePanel()
    applyBubbleLayout()
}

/**
 * 屏幕上任何不属于气泡的触摸开始（`ACTION_OUTSIDE`）→ 收成贴边尾巴。
 *
 * 面板开着时同样触发：`setCollapsed(true)` 会连面板一起收 ——
 * "用户去操作别处了"这个事实对面板同样成立，把它留在原地只会挡视线。
 * 面板自身也收 OUTSIDE（点面板外 = 关面板），两边都调 [collapsePanel]/[removePanel]，
 * 均为幂等操作，重复调用无害。
 */
internal fun FloatingBubbleService.collapseOnOutsideTouch() {
    // 气泡被主界面隐藏（GONE）时窗口仍能收到 OUTSIDE：不收，等重新可见再说
    if (!visible) return
    // 「悬浮窗常在」（2026-10-26）：开启时不因外部触摸收起。每次实时读设置，
    // 用户在设置页拨动后立即生效，无需重启服务。
    if (settings.alwaysVisible) return
    // 录音进行中保持完整形态，不因外部触摸收起（spec §5.2.1-6）
    if (recording.recording.value) return
    setCollapsed(true)
}

/**
 * 「悬浮窗常在」开启时的即时生效（2026-10-26）：若气泡此刻是贴边细条，立刻展开成完整气泡。
 * 服务没在跑 / 已是完整形态时是空操作。
 */
internal fun FloatingBubbleService.expandIfCollapsed() {
    if (collapsed) setCollapsed(false)
}

internal fun FloatingBubbleService.togglePanel() {
    // 收起态下的第一次点击只负责"把气泡叫回来"，不执行任何动作。
    // 否则用户只是想看一眼气泡，手指一碰就保存了一条剪贴板内容。
    if (collapsed) {
        setCollapsed(false)
        return
    }
    // 面板刚被窗外触摸关掉，且这次点的是气泡：面板自己先收到 OUTSIDE 并关闭，
    // 气泡随后才收到这一击。若继续按"未展开"处理会重新读剪贴板、为空时又把面板弹开，
    // 表现为"点了关不掉"。宽限期内的这一击直接吞掉（2026-11-08 用户反馈）。
    if (!expanded && SystemClock.elapsedRealtime() - panelDismissedAt < PANEL_DISMISS_GRACE_MS) {
        panelDismissedAt = 0L
        return
    }
    // 面板展开时，气泡上的图标已经是"关闭"：点它必须关面板，不能再读剪贴板。
    // 否则用户想关菜单时会误存一条（PRD v1.2 §3.1 的关闭按钮）。
    if (expanded) {
        collapsePanel()
        return
    }
    // 点气泡先尝试收录剪贴板（PRD v1.2 §2.2 的核心交互）。
    // 有内容就地保存并提示；为空 / 不可读 / 重复时展开快捷面板，由用户改用手动方式。
    // 设置页可显式关闭（默认开），关闭后点气泡只展开面板、不读剪贴板。
    if (settings.autoClipboard) {
        // 启动参数由中转页自己给出（NEW_TASK + CLEAR_TASK + 无动画），
        // 见 ClipboardCaptureActivity.launchIntent
        startActivity(ClipboardCaptureActivity.launchIntent(this))
        return
    }
    expandPanel()
}

internal fun FloatingBubbleService.moveBubbleBy(dx: Int, dy: Int) {
    val (screenWidth, screenHeight) = floatingViewportSize()
    bubbleParams.x = (bubbleParams.x + dx)
        .coerceIn(0, (screenWidth - bubbleParams.width).coerceAtLeast(0))
    bubbleParams.y = (bubbleParams.y + dy)
        .coerceIn(0, (screenHeight - bubbleParams.height).coerceAtLeast(0))

    bubbleView?.let { windowManager.updateViewSafely(it, bubbleParams) }
    // 拖动时面板跟着走，否则会看到面板留在原地、与气泡脱节
    if (expanded) positionPanel()
}

/** 松手：吸附到最近一侧并持久化，下次启动按新位置恢复。 */
internal fun FloatingBubbleService.snapBubble() {
    val (screenWidth, screenHeight) = floatingViewportSize()
    val position = BubbleGeometry.snap(
        centerX = bubbleParams.x + bubbleParams.width / 2,
        centerY = bubbleParams.y + bubbleParams.height / 2,
        viewportWidth = screenWidth,
        viewportHeight = screenHeight,
        bubbleSize = bubbleParams.width,
    )
    val (x, y) = BubbleGeometry.offsetOf(
        position = position,
        viewportWidth = screenWidth,
        viewportHeight = screenHeight,
        bubbleSize = bubbleParams.width,
    )
    bubbleParams.x = x
    bubbleParams.y = y
    bubbleView?.let { windowManager.updateViewSafely(it, bubbleParams) }
    settings.savePosition(position)
    // 拖到另一侧后，收起态的圆角方向要跟着换（贴右圆左边、贴左圆右边）
    updateBubbleForm { it.copy(edge = position.edge) }
    if (expanded) positionPanel()
}