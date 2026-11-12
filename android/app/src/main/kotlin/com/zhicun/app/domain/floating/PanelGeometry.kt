package com.zhicun.app.domain.floating

/**
 * 面板相对气泡的左上角坐标（纯数据）。
 *
 * 与 [BubblePosition] 一样只描述"摆在哪"：面板尺寸由内容决定，
 * 每次展开/拖动/旋转都重新算一遍，不持久化。
 */
data class PanelPlacement(val x: Int, val y: Int)

/** 屏幕上的矩形（左闭右开，像素）。抽出来只为让"是否压住气泡"可单测。 */
data class PanelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun intersects(other: PanelRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * 快捷面板的摆放规则（2026-11-08 用户反馈：屏幕窄时面板压住气泡）。
 *
 * ## 为什么不能只算"水平内侧"
 * 原实现只有一条路：气泡贴右就把面板放在气泡左侧，贴左就放右侧，再把 x 夹进屏幕。
 * 大屏上没问题；窄屏（分屏、横屏、小屏机）夹取之后面板会盖住气泡 ——
 * 用户点气泡想关菜单，点到的其实是面板，表现为"点不动、关不掉"。
 *
 * ## 现在的优先级
 * 1. **水平内侧**：最自然，面板像从气泡旁边滑出来；
 * 2. **垂直错开**：水平放不下时，面板与气泡同侧对齐、改成贴在气泡下方/上方，
 *    两者矩形不相交即可（用户说的"错开位置"）；
 * 3. 屏幕太窄/太矮、怎么放都会相交时，退到屏幕另一端；仍放不下就接受重叠，
 *    但保证坐标可见 —— 面板比屏幕还大属于不可解，不应因此把面板放到屏幕外。
 *
 * 纯函数：所有分支都能在 JVM 上穷举，真机上"面板跑到屏幕外/压住气泡"很难复现。
 */
object PanelGeometry {

    /**
     * @param edge 气泡贴哪一侧（决定水平内侧方向）
     * @param bubbleX 气泡窗口左上角 x（屏幕坐标）
     * @param bubbleY 气泡窗口左上角 y
     * @param bubbleWidth 气泡窗口宽
     * @param bubbleHeight 气泡窗口高
     * @param panelWidth 面板宽（首次布局后才知道）
     * @param panelHeight 面板高
     * @param viewportWidth 可用视口宽
     * @param viewportHeight 可用视口高
     * @param gap 面板与气泡之间的间隙
     */
    fun place(
        edge: BubbleEdge,
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        gap: Int,
    ): PanelPlacement {
        val bubble = PanelRect(
            left = bubbleX,
            top = bubbleY,
            right = bubbleX + bubbleWidth,
            bottom = bubbleY + bubbleHeight,
        )
        val maxX = (viewportWidth - panelWidth).coerceAtLeast(0)
        val maxY = (viewportHeight - panelHeight).coerceAtLeast(0)

        // 1) 水平内侧
        val innerX = when (edge) {
            BubbleEdge.RIGHT -> bubbleX - gap - panelWidth
            BubbleEdge.LEFT -> bubbleX + bubbleWidth + gap
        }
        val horizontalX = innerX.coerceIn(0, maxX)
        val horizontalY = bubbleY.coerceIn(0, maxY)
        if (!PanelRect(horizontalX, horizontalY, horizontalX + panelWidth, horizontalY + panelHeight)
                .intersects(bubble)
        ) {
            return PanelPlacement(horizontalX, horizontalY)
        }

        // 2) 垂直错开：同侧对齐，优先气泡下方，其次上方
        val stackedX = when (edge) {
            BubbleEdge.RIGHT -> (bubbleX + bubbleWidth - panelWidth).coerceIn(0, maxX)
            BubbleEdge.LEFT -> bubbleX.coerceIn(0, maxX)
        }
        val below = bubbleY + bubbleHeight + gap
        val above = bubbleY - gap - panelHeight
        val stackedY = when {
            below + panelHeight <= viewportHeight -> below
            above >= 0 -> above
            else -> {
                // 上下都放不下（横屏/超长面板）：夹进屏幕，取离气泡更远的一侧
                val clampedBelow = below.coerceIn(0, maxY)
                val clampedAbove = above.coerceIn(0, maxY)
                val gapBelow = clampedBelow - bubble.bottom
                val gapAbove = bubble.top - (clampedAbove + panelHeight)
                if (gapBelow >= gapAbove) clampedBelow else clampedAbove
            }
        }
        if (!PanelRect(stackedX, stackedY, stackedX + panelWidth, stackedY + panelHeight)
                .intersects(bubble)
        ) {
            return PanelPlacement(stackedX, stackedY)
        }

        // 3) 退路：屏幕另一端
        val oppositeX = if (edge == BubbleEdge.RIGHT) 0 else maxX
        if (!PanelRect(oppositeX, stackedY, oppositeX + panelWidth, stackedY + panelHeight)
                .intersects(bubble)
        ) {
            return PanelPlacement(oppositeX, stackedY)
        }

        // 不可解：面板比可用空间还大。宁可接受重叠也不能把坐标推出去，
        // 否则用户彻底看不到面板。调用方会记一条警告日志。
        return PanelPlacement(oppositeX, stackedY)
    }

    /** 点击坐标是否落在气泡上（含 [padding] 容差）。面板关闭判定用。 */
    fun isOnBubble(
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        x: Int,
        y: Int,
        padding: Int,
    ): Boolean = PanelRect(
        left = bubbleX - padding,
        top = bubbleY - padding,
        right = bubbleX + bubbleWidth + padding,
        bottom = bubbleY + bubbleHeight + padding,
    ).contains(x, y)
}