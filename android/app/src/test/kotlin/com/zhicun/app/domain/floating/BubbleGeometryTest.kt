package com.zhicun.app.domain.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡几何计算（M13，纯函数）。
 *
 * 这里钉的都是真机上"气泡消失、再也点不到"的成因：
 * 拖动越界、屏幕旋转后旧坐标超出新高度、分屏时屏幕比气泡还窄。
 * 这些情况在真机上极难复现，但一旦出现用户只能去设置里关掉再开。
 */
class BubbleGeometryTest {

    private val size = 144 // 48dp @ xhdpi
    private val width = 1080
    private val height = 1920

    // ------------------------------------------------------------------ snap

    @Test
    fun Snap_leftHalf_snapsToLeftEdge() {
        val position = BubbleGeometry.snap(
            centerX = 100,
            centerY = 600,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(BubbleEdge.LEFT, position.edge)
    }

    @Test
    fun Snap_rightHalf_snapsToRightEdge() {
        val position = BubbleGeometry.snap(
            centerX = 1000,
            centerY = 600,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(BubbleEdge.RIGHT, position.edge)
    }

    /**
     * 中线本身算右侧。这是个纯约定，但必须钉住：
     * 左右抖动一像素就换边的话，气泡会在中线上"跳"，用户会觉得手抖。
     */
    @Test
    fun Snap_exactCenter_snapsToRight() {
        val position = BubbleGeometry.snap(
            centerX = width / 2,
            centerY = 600,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(BubbleEdge.RIGHT, position.edge)
    }

    @Test
    fun Snap_storesTopLeftYNotCenterY() {
        // 中心 y = 600 → 左上角 y = 600 - 144/2 = 528
        val position = BubbleGeometry.snap(
            centerX = 100,
            centerY = 600,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(528, position.y)
    }

    @Test
    fun Snap_draggedAboveTop_clampsToZero() {
        val position = BubbleGeometry.snap(
            centerX = 100,
            centerY = -500,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(0, position.y)
    }

    @Test
    fun Snap_draggedBelowBottom_clampsToMaxY() {
        val position = BubbleGeometry.snap(
            centerX = 100,
            centerY = 5000,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(height - size, position.y)
    }

    // ------------------------------------------------------------------ offsetOf

    @Test
    fun OffsetOf_leftEdge_isZeroX() {
        val (x, _) = BubbleGeometry.offsetOf(
            position = BubblePosition(edge = BubbleEdge.LEFT, y = 300),
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(0, x)
    }

    @Test
    fun OffsetOf_rightEdge_isFlushToRight() {
        val (x, _) = BubbleGeometry.offsetOf(
            position = BubblePosition(edge = BubbleEdge.RIGHT, y = 300),
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(width - size, x)
    }

    /**
     * 分屏 / 小窗下屏幕可能比气泡还窄。此时右对齐会算出负数，
     * 窗口坐标给负值会被系统当成"贴到屏幕外"，表现就是气泡不见了。
     */
    @Test
    fun OffsetOf_viewportNarrowerThanBubble_clampsXToZero() {
        val (x, y) = BubbleGeometry.offsetOf(
            position = BubblePosition(edge = BubbleEdge.RIGHT, y = 100),
            viewportWidth = 100,
            viewportHeight = 100,
            bubbleSize = size,
        )

        assertEquals(0, x)
        assertEquals(0, y)
    }

    /**
     * 横屏或旋转后可用高度变小：旧位置必须被夹回可见范围，
     * 否则气泡停在屏幕下方之外，用户以为功能坏了。
     */
    @Test
    fun OffsetOf_afterRotationAboveNewHeight_clampsY() {
        val saved = BubblePosition(edge = BubbleEdge.RIGHT, y = 1800)

        val (_, y) = BubbleGeometry.offsetOf(
            position = saved,
            viewportWidth = width,
            viewportHeight = 800,
            bubbleSize = size,
        )

        assertEquals(800 - size, y)
    }

    /**
     * 横竖屏来回一趟必须**回到原位**（2026-10-24，修"横屏远控退出后气泡消失"）。
     *
     * bug 现场：横屏贴边算出 `x = 1920 - 144`，回竖屏后没人重算，`x` 还是 1776，
     * 而竖屏宽只有 1080，加上 `FLAG_LAYOUT_NO_LIMITS` 不裁剪 → 气泡整个在屏幕外，
     * 点不到也拖不回来。所以正确做法是**视口一变就按持久化位置重算**，而不是沿用旧值。
     *
     * 同时钉住"不能在当前 y 上夹取"：竖屏 y=1700 → 横屏夹成 936 → 若拿 936 当新基准，
     * 转回竖屏气泡就永久停在 936（自己往上跑）。以 `BubblePosition` 重算才是幂等的。
     */
    @Test
    fun OffsetOf_portraitLandscapePortrait_returnsToOriginalSpot() {
        val saved = BubblePosition(edge = BubbleEdge.RIGHT, y = 1700)

        val (landscapeX, landscapeY) = BubbleGeometry.offsetOf(
            position = saved,
            viewportWidth = 1920,
            viewportHeight = 1080,
            bubbleSize = size,
        )
        val landscapeLabel = "横屏内必须完整可见：($landscapeX,$landscapeY)"
        assertTrue(landscapeLabel, landscapeX <= 1920 - size)
        assertTrue(landscapeLabel, landscapeY <= 1080 - size)

        val (portraitX, portraitY) = BubbleGeometry.offsetOf(
            position = saved,
            viewportWidth = width,
            viewportHeight = height,
            bubbleSize = size,
        )

        assertEquals(width - size, portraitX)
        assertEquals(1700, portraitY)
    }

    // ------------------------------------------------------------------ clampY

    @Test
    fun ClampY_negativeInput_returnsZero() {
        assertEquals(0, BubbleGeometry.clampY(-30, viewportHeight = height, bubbleSize = size))
    }

    @Test
    fun ClampY_withinRange_returnsSameValue() {
        assertEquals(500, BubbleGeometry.clampY(500, viewportHeight = height, bubbleSize = size))
    }

    @Test
    fun ClampY_screenSmallerThanBubble_returnsZero() {
        assertEquals(0, BubbleGeometry.clampY(50, viewportHeight = 100, bubbleSize = size))
    }

    @Test
    fun ClampY_screenExactlyBubbleSize_returnsZero() {
        assertEquals(0, BubbleGeometry.clampY(50, viewportHeight = size, bubbleSize = size))
    }

    // ------------------------------------------------------------------ 不变量

    /**
     * 无论输入多荒唐，算出来的坐标都必须让气泡**完整落在屏幕内**。
     * 这条是"气泡永远不会消失"的守门断言，比逐个 case 更能防止后续改动破坏它。
     */
    @Test
    fun OffsetOf_alwaysKeepsBubbleInsideViewport() {
        val widths = listOf(0, 50, size, 720, width)
        val heights = listOf(0, 50, size, 800, height)
        val ys = listOf(-9999, -1, 0, 300, 1800, 9999)

        for (w in widths) {
            for (h in heights) {
                for (edge in BubbleEdge.entries) {
                    for (y in ys) {
                        val (x, ty) = BubbleGeometry.offsetOf(
                            position = BubblePosition(edge = edge, y = y),
                            viewportWidth = w,
                            viewportHeight = h,
                            bubbleSize = size,
                        )
                        val label = "w=$w h=$h edge=$edge y=$y → ($x,$ty)"

                        assertTrue("x 越界：$label", x >= 0)
                        assertTrue("y 越界：$label", ty >= 0)
                        // 屏幕比气泡窄/矮时允许被裁，但不允许把左上角推出屏幕
                        assertTrue("x 超出右边界：$label", x <= (w - size).coerceAtLeast(0))
                        assertTrue("y 超出下边界：$label", ty <= (h - size).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    // ------------------------------------------------- 收起态小尾巴（2026-10-24）

    private val tabWidth = 48 // 16dp @ xhdpi
    private val tabHeight = 132 // 44dp @ xhdpi

    @Test
    fun OffsetOfEdge_rightEdge_isFlushToRight() {
        val (x, _) = BubbleGeometry.offsetOfEdge(
            position = BubblePosition(edge = BubbleEdge.RIGHT, y = 300),
            viewportWidth = width,
            viewportHeight = height,
            viewWidth = tabWidth,
            viewHeight = tabHeight,
        )

        assertEquals(width - tabWidth, x)
    }

    @Test
    fun OffsetOfEdge_leftEdge_isZeroX() {
        val (x, _) = BubbleGeometry.offsetOfEdge(
            position = BubblePosition(edge = BubbleEdge.LEFT, y = 300),
            viewportWidth = width,
            viewportHeight = height,
            viewWidth = tabWidth,
            viewHeight = tabHeight,
        )

        assertEquals(0, x)
    }

    /**
     * 收起时高度变小（144 → 132），如果沿用旧 y，气泡会往上跳 12px。
     * 保持竖直中心不动，用户看到的是"原地收窄"。
     */
    @Test
    fun KeepCenterY_collapse_keepsVerticalCenter() {
        val y = BubbleGeometry.keepCenterY(
            oldY = 528,
            oldHeight = size,
            newHeight = tabHeight,
            viewportHeight = height,
        )

        assertEquals(534, y)
        assertEquals("中心不变", 528 + size / 2, y + tabHeight / 2)
    }

    @Test
    fun KeepCenterY_expand_keepsVerticalCenter() {
        val y = BubbleGeometry.keepCenterY(
            oldY = 534,
            oldHeight = tabHeight,
            newHeight = size,
            viewportHeight = height,
        )

        assertEquals(528, y)
    }

    /** 贴着屏幕底部时按中心对齐会把尾巴推出屏幕，必须夹回可见范围。 */
    @Test
    fun KeepCenterY_nearBottom_clampsIntoViewport() {
        val y = BubbleGeometry.keepCenterY(
            oldY = 1800,
            oldHeight = size,
            newHeight = tabHeight,
            viewportHeight = height,
        )

        assertEquals(height - tabHeight, y)
    }

    /** 尾巴更窄更矮，也不能被推到屏幕外（旋转/分屏后尤其容易）。 */
    @Test
    fun OffsetOfEdge_alwaysKeepsTabInsideViewport() {
        val widths = listOf(0, 40, tabWidth, 720, width)
        val heights = listOf(0, 40, tabHeight, 800, height)
        val ys = listOf(-9999, -1, 0, 300, 1800, 9999)

        for (w in widths) {
            for (h in heights) {
                for (edge in BubbleEdge.entries) {
                    for (y in ys) {
                        val (x, ty) = BubbleGeometry.offsetOfEdge(
                            position = BubblePosition(edge = edge, y = y),
                            viewportWidth = w,
                            viewportHeight = h,
                            viewWidth = tabWidth,
                            viewHeight = tabHeight,
                        )
                        val label = "w=$w h=$h edge=$edge y=$y → ($x,$ty)"

                        assertTrue("x 越界：$label", x >= 0)
                        assertTrue("y 越界：$label", ty >= 0)
                        assertTrue("x 超出右边界：$label", x <= (w - tabWidth).coerceAtLeast(0))
                        assertTrue("y 超出下边界：$label", ty <= (h - tabHeight).coerceAtLeast(0))
                    }
                }
            }
        }
    }
}
