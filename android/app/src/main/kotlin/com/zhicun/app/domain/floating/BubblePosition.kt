package com.zhicun.app.domain.floating

/** 气泡吸附在屏幕哪一侧。 */
enum class BubbleEdge {
    LEFT,
    RIGHT,
}

/**
 * 气泡的位置（M13，纯数据，无 Android 依赖）。
 *
 * ## 为什么只存"贴哪一侧 + 竖直位置"，不存绝对 x
 * 屏幕会旋转、会分屏、会被系统栏改尺寸。存绝对 `x` 的话，横竖屏切换后气泡可能停在屏幕外
 * （用户再也点不到，只能去设置里关掉再开）。横坐标永远由"贴边"重新算出来，
 * 竖坐标在恢复时按当前可用高度夹取一次，旋转/换设备都不会丢。
 *
 * @param edge 吸附侧
 * @param y 竖直方向的位置（**像素**，相对屏幕顶部；由 [BubbleGeometry] 负责夹取）。
 *   旧注释写的是 dp，与实际存的值不符 —— 这里存的就是 `bubbleParams.y` 的像素值，
 *   换设备/改 dpi 后同样的数字含义会变，但每次恢复都会按当前视口夹一次，不会跑到屏幕外。
 */
data class BubblePosition(
    val edge: BubbleEdge = BubbleEdge.RIGHT,
    val y: Int = DEFAULT_Y,
) {
    companion object {
        /** 首次出现时的竖直位置：偏上一点，避开拇指最常滑动的区域。 */
        const val DEFAULT_Y: Int = 320
    }
}

/**
 * 气泡的几何计算（M13，纯函数）。
 *
 * 抽成纯函数是为了能在 JVM 上穷举边界：拖动越界、屏幕旋转、屏幕比气泡还窄、
 * 负数坐标（多窗口负偏移）这些情况在真机上很难复现，但不处理就会"气泡消失"。
 */
object BubbleGeometry {

    /**
     * 把拖动后的坐标吸附到最近的一侧。
     *
     * @param centerX 气泡中心的 x（拖动过程中的实时值）
     * @param centerY 气泡中心的 y
     * @param viewportWidth 可用宽度（不含系统栏）
     * @param viewportHeight 可用高度
     * @param bubbleSize 气泡边长（正方形）
     */
    fun snap(
        centerX: Int,
        centerY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        bubbleSize: Int,
    ): BubblePosition {
        val edge = if (centerX < viewportWidth / 2) BubbleEdge.LEFT else BubbleEdge.RIGHT
        return BubblePosition(edge = edge, y = clampY(centerY - bubbleSize / 2, viewportHeight, bubbleSize))
    }

    /** 恢复位置时的左上角坐标：横坐标由贴边算出，竖坐标夹取。 */
    fun offsetOf(
        position: BubblePosition,
        viewportWidth: Int,
        viewportHeight: Int,
        bubbleSize: Int,
    ): Pair<Int, Int> = offsetOfEdge(
        position = position,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        viewWidth = bubbleSize,
        viewHeight = bubbleSize,
    )

    /**
     * 与 [offsetOf] 相同，但允许**宽高不同**（贴边收起后的"小尾巴"是细长条，不是正方形）。
     *
     * 单开一个函数而不是给 [offsetOf] 加两个带默认值的参数：默认值会让调用方
     * 漏传高度时静默按宽度算，而"尾巴比气泡窄"这件事恰好只在宽高不等时才有意义。
     */
    fun offsetOfEdge(
        position: BubblePosition,
        viewportWidth: Int,
        viewportHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Int, Int> = edgeX(position.edge, viewportWidth, viewWidth) to
        clampY(position.y, viewportHeight, viewHeight)

    /** 贴边后的横坐标：左贴 0，右贴贴着右边界。 */
    fun edgeX(edge: BubbleEdge, viewportWidth: Int, viewWidth: Int): Int = when (edge) {
        BubbleEdge.LEFT -> 0
        BubbleEdge.RIGHT -> (viewportWidth - viewWidth).coerceAtLeast(0)
    }

    /**
     * 形态切换（完整气泡 ⇄ 小尾巴）时的竖坐标：**保持竖直中心不动**。
     *
     * 直接用旧 y 会让气泡看起来"往上跳了一截"（高度从 48dp 变成 44dp 时差 2dp 不明显，
     * 但反过来展开就会有可见的位移）。中心对齐后再交给 [clampY] 夹取。
     */
    fun keepCenterY(oldY: Int, oldHeight: Int, newHeight: Int, viewportHeight: Int): Int =
        clampY(oldY + (oldHeight - newHeight) / 2, viewportHeight, newHeight)

    /**
     * 竖直位置夹取到"完全可见"的区间。
     *
     * 屏幕比气泡还小时（极端分屏）`max` 会小于 0，此时返回 0：宁可被裁掉一部分，
     * 也不能返回负值把气泡推到状态栏上面去。
     */
    fun clampY(y: Int, viewportHeight: Int, bubbleSize: Int): Int {
        val max = viewportHeight - bubbleSize
        if (max <= 0) return 0
        return y.coerceIn(0, max)
    }
}
