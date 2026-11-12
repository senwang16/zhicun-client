package com.zhicun.app.presentation.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.floating.BubbleEdge
import com.zhicun.app.presentation.theme.AppTheme

/** 气泡直径。48dp 是 Material 的最小可点区域，再小就不好按。 */
val BUBBLE_SIZE: Dp = 48.dp

/**
 * 收起态"小尾巴"的尺寸（贴边细条，2026-10-24）。
 *
 * 宽 16dp 只是**露在屏幕内**的那一条：视觉上像从屏幕边缘探出的把手，而不是一个
 * 悬浮控件，这正是"别太明显"的诉求。高度 44dp 仍保证有一定可点面积（宽度小，
 * 手指必须能按到，所以竖向给足）。
 */
val BUBBLE_TAB_WIDTH: Dp = 16.dp
val BUBBLE_TAB_HEIGHT: Dp = 44.dp

/** 收起态的底色透明度：留在屏幕上但不能抢视线。 */
private const val COLLAPSED_ALPHA = 0.72f

/**
 * 悬浮气泡本体（M13 / 2026-10-24 增加收起态）。
 *
 * ## 为什么把点击和拖动写在**同一个**手势循环里
 * 用 `detectTapGestures` + `detectDragGestures` 两个 `pointerInput` 挂在同一元素上，
 * 一次拖动在手指抬起时仍可能触发 `onTap`（`detectTapGestures` 只在越界/被消费时取消，
 * 在气泡内部移动不算取消）。结果就是"每拖一次气泡就顺手弹出面板"。
 *
 * 因此这里手写一个手势循环：位移累计超过 `touchSlop` 才转入拖动，
 * 抬起时按 `moved` 决定"这是点击"还是"拖动结束"，语义互斥、不存在两套判定打架。
 *
 * ## 收起态为什么不复用同一套手势
 * 小尾巴只有 16dp 宽，拖动它既难按也不符合语义（贴着边的东西没有"往哪拖"的问题）。
 * 收起态只认点击：点一下先展开成完整气泡，再点才执行动作 —— 这样误触的代价是
 * "气泡展开"而不是"又存了一条记录"。
 */
@Composable
fun FloatingBubble(
    expanded: Boolean,
    collapsed: Boolean,
    edge: BubbleEdge,
    onClick: () -> Unit,
    onDrag: (delta: Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = BUBBLE_SIZE,
    /** 录音进行中（2026-10-26）：气泡变红提示"正在录音"，但**不**显示计时数字（计时在顶部条）。 */
    recording: Boolean = false,
    /** 长按判定成立（进入录音）时回调；是否真正开录由服务按「长按录音」开关决定。 */
    onRecordStart: () -> Unit = {},
    /** 录音态抬手时回调（停止并保存）。 */
    onRecordEnd: () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    if (collapsed) {
        CollapsedBubble(
            edge = edge,
            onClick = onClick,
            modifier = modifier,
            containerColor = containerColor,
            contentColor = contentColor,
        )
        return
    }

    val description = stringResource(R.string.floating_bubble_description)
    val bg = if (recording) MaterialTheme.colorScheme.error else containerColor

    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(bg)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // 阶段一：在长按阈值内等待"抬起=点击"或"位移超阈值=拖动"；
                    // 超时（返回 null）即判定长按。三态互斥（spec §5.2.1-4）。
                    val gesture = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull null
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) {
                                moved += delta.getDistance()
                                if (moved > viewConfiguration.touchSlop) {
                                    change.consume()
                                    return@withTimeoutOrNull BubbleGesture.Drag
                                }
                            }
                            if (!change.pressed) {
                                change.consume()
                                return@withTimeoutOrNull BubbleGesture.Tap
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }

                    when (gesture) {
                        BubbleGesture.Tap -> onClick()

                        BubbleGesture.Drag -> {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val delta = change.position - change.previousPosition
                                if (delta != Offset.Zero) {
                                    change.consume()
                                    onDrag(delta)
                                }
                            }
                            onDragEnd()
                        }

                        // 超时（null）= 长按：回调开始录音（是否真开录由服务按开关决定），
                        // 只等抬手，不响应点击（spec AC-2.15：关闭该功能时按住超阈值抬手也不收录剪贴板）。
                        null -> {
                            onRecordStart()
                            // 手势被系统/窗口取消时 awaitRelease 会抛 CancellationException：
                            // 必须仍然走 onRecordEnd，否则麦克风会一直占用（2026-10-27）
                            try {
                                awaitRelease(down.id)
                            } finally {
                                onRecordEnd()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_floating_close else R.drawable.ic_floating_bubble,
            ),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 气泡手势判定结果：抬手=点击，位移超阈值=拖动；超时未决=长按（返回 null 表示）。 */
private enum class BubbleGesture { Tap, Drag }

/**
 * 长按/录音态下等待手指抬起：期间吞掉所有指针事件，使气泡在录音中**不**响应点击与拖动
 * （spec §5.2.1-5）。
 */
private suspend fun AwaitPointerEventScope.awaitRelease(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/**
 * 收起态：贴在屏幕某一侧的细长把手。
 *
 * 圆角只给**朝屏幕内**的一侧（贴左边就圆右边），另一侧是直角并压在屏幕边缘上，
 * 视觉上才像"从边上探出来"，而不是一个贴在边上的小圆角矩形。
 */
@Composable
private fun CollapsedBubble(
    edge: BubbleEdge,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color,
) {
    val description = stringResource(R.string.floating_bubble_collapsed_description)
    val radius = BUBBLE_TAB_WIDTH / 2
    val shape = when (edge) {
        BubbleEdge.RIGHT -> RoundedCornerShape(topStart = radius, bottomStart = radius)
        BubbleEdge.LEFT -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
    }

    Box(
        modifier = modifier
            .width(BUBBLE_TAB_WIDTH)
            .height(BUBBLE_TAB_HEIGHT)
            .shadow(elevation = 2.dp, shape = shape)
            .clip(shape)
            .background(containerColor.copy(alpha = COLLAPSED_ALPHA))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                // 收起态只认点击：拖动语义上说不通，而且 16dp 宽的位置很容易被"滑"过去
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        // 一道竖线当"把手纹"，比再塞一个图标更不抢眼
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.9f)),
        )
    }
}

@Preview
@Composable
private fun FloatingBubblePreview() {
    AppTheme {
        FloatingBubble(
            expanded = false,
            collapsed = false,
            edge = BubbleEdge.RIGHT,
            onClick = {},
            onDrag = {},
            onDragEnd = {},
        )
    }
}

@Preview
@Composable
private fun FloatingBubbleCollapsedPreview() {
    AppTheme {
        FloatingBubble(
            expanded = false,
            collapsed = true,
            edge = BubbleEdge.RIGHT,
            onClick = {},
            onDrag = {},
            onDragEnd = {},
        )
    }
}
