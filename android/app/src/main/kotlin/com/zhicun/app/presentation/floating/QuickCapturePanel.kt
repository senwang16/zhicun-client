package com.zhicun.app.presentation.floating

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.presentation.navigation.Routes
import com.zhicun.app.presentation.theme.AppTheme

/**
 * 快捷动作。枚举保留通知栏 / 桌面小组件仍在用的入口（剪贴板、录音、收件箱），
 * 但悬浮面板**只展示** [PANEL_ACTIONS]（PRD v1.2 §3.2：恰好三个）。
 */
enum class QuickCaptureAction(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    SCREENSHOT(R.string.floating_action_screenshot, R.drawable.ic_action_screenshot),
    TEXT(R.string.floating_action_text, R.drawable.ic_action_text),
    HOME(R.string.floating_action_home, R.drawable.ic_action_home),
    CLIPBOARD(R.string.floating_action_clipboard, R.drawable.ic_action_clipboard),
    VOICE(R.string.floating_action_voice, R.drawable.ic_action_voice),
    INBOX(R.string.floating_action_inbox, R.drawable.ic_action_inbox),
    ;

    /** UI 测试用标签：与显示文案解耦，改文案不必改测试。 */
    val testTag: String get() = "quick_capture_${name.lowercase()}"
}

/**
 * 悬浮面板的三个入口（PRD v1.2 §3.2，顺序即 2×2 网格里的点击顺序）：
 * 截图 → 记一笔 → 打开主界面。收件箱入口不再单独出现，由"打开主界面"承接。
 */
val PANEL_ACTIONS: List<QuickCaptureAction> = listOf(
    QuickCaptureAction.SCREENSHOT,
    QuickCaptureAction.TEXT,
    QuickCaptureAction.HOME,
)

/**
 * 动作 → 主界面路由。面板点击、通知栏动作按钮（B1）与小组件（B3）共用这一张表，
 * 避免"面板进新建页、通知按钮进别的页"这种两套判据各改一半的漂移。
 *
 * 返回 null 的动作需要 Service 另行处理：
 * - [QuickCaptureAction.SCREENSHOT] 走 `ScreenCaptureActivity`（MediaProjection 授权）；
 * - [QuickCaptureAction.CLIPBOARD] 走 `ClipboardCaptureActivity`（Android 10+ 要求前台 Activity）。
 */
fun routeFor(action: QuickCaptureAction): String? = when (action) {
    QuickCaptureAction.SCREENSHOT -> null
    QuickCaptureAction.CLIPBOARD -> null
    QuickCaptureAction.TEXT -> Routes.CREATE
    QuickCaptureAction.HOME -> Routes.INBOX
    QuickCaptureAction.VOICE -> Routes.VOICE
    QuickCaptureAction.INBOX -> Routes.INBOX
}

/** 面板根节点的测试标签：用来判断面板是否已展开。 */
object QuickCaptureTestTags {
    const val PANEL: String = "quick_capture_panel"
}

/**
 * 快捷面板（M13 / PRD v1.2 §3.2）：点气泡后展开的三个入口。
 *
 * ## 为什么面板不自己处理"打开哪个页面"
 * 面板是纯展示，点击只上报 [QuickCaptureAction]。真正的动作（拉起截图/剪贴板中转页、
 * 启动 MainActivity 到某个路由）需要 `Context.startActivity` + Intent flags，
 * 属于 Service 的职责。这样面板能在普通 Compose 测试里跑，不需要悬浮窗环境。
 *
 * ## 为什么图标靠形状而非颜色区分
 * 悬浮面板出现在任意应用之上，背景不可控。只靠颜色区分在深/浅壁纸下会失效，
 * 主色调 + 形状差异在任何背景上都成立。
 */
@Composable
fun QuickCapturePanel(
    onAction: (QuickCaptureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(QuickCaptureTestTags.PANEL),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        // 2026-10 改版：竖排列表 → 2x2 网格（设计稿 03），每格上图标下文字
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PANEL_ACTIONS.chunked(GRID_COLUMNS).forEach { rowActions ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowActions.forEach { action ->
                        QuickCaptureTile(
                            action = action,
                            onClick = { onAction(action) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 补齐空位，保证每格等宽（三个动作时第二行只有一格）
                    repeat(GRID_COLUMNS - rowActions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 网格列数（设计稿 03 的 2x2 布局）。 */
private const val GRID_COLUMNS = 2

@Composable
private fun QuickCaptureTile(
    action: QuickCaptureAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(action.testTag),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(action.labelRes),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Preview
@Composable
private fun QuickCapturePanelPreview() {
    AppTheme {
        QuickCapturePanel(onAction = {})
    }
}
