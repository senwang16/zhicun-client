package com.zhicun.app.domain.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷面板摆放（2026-11-08，纯函数）。
 *
 * 钉的都是真机上"面板压住气泡、点了关不掉"的成因：窄屏/分屏下水平夹取后相交、
 * 气泡拖到中间、屏幕高不够放不下。这些在 JVM 上可穷举，真机反而难复现。
 */
class PanelGeometryTest {

    private val gap = 24 // 8dp @ xhdpi
    private val bubbleSize = 144 // 48dp @ xhdpi
    private val panelWidth = 560
    private val panelHeight = 400

    private fun place(
        edge: BubbleEdge = BubbleEdge.RIGHT,
        bubbleX: Int = 1080 - bubbleSize,
        bubbleY: Int = 300,
        viewportWidth: Int = 1080,
        viewportHeight: Int = 1920,
        panelWidth: Int = this.panelWidth,
        panelHeight: Int = this.panelHeight,
    ) = PanelGeometry.place(
        edge = edge,
        bubbleX = bubbleX,
        bubbleY = bubbleY,
        bubbleWidth = bubbleSize,
        bubbleHeight = bubbleSize,
        panelWidth = panelWidth,
        panelHeight = panelHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        gap = gap,
    )

    private fun rect(placement: PanelPlacement, width: Int = panelWidth, height: Int = panelHeight) =
        PanelRect(placement.x, placement.y, placement.x + width, placement.y + height)

    private fun bubble(bubbleX: Int = 1080 - bubbleSize, bubbleY: Int = 300) =
        PanelRect(bubbleX, bubbleY, bubbleX + bubbleSize, bubbleY + bubbleSize)

    @Test
    fun Place_wideScreenRightEdge_panelSitsLeftOfBubble() {
        val placement = place()
        assertEquals(1080 - bubbleSize - gap - panelWidth, placement.x)
        assertFalse(rect(placement).intersects(bubble()))
    }

    @Test
    fun Place_wideScreenLeftEdge_panelSitsRightOfBubble() {
        val placement = place(edge = BubbleEdge.LEFT, bubbleX = 0)
        assertEquals(bubbleSize + gap, placement.x)
        assertFalse(rect(placement).intersects(bubble()))
    }

    /** 窄屏：内侧夹到 0 后会压住气泡 —— 必须改为垂直错开。 */
    @Test
    fun Place_narrowScreen_stacksVerticallyAndNeverOverlaps() {
        val placement = place(
            viewportWidth = 320,
            bubbleX = 320 - bubbleSize,
            panelWidth = 280,
        )
        val panel = rect(placement, width = 280)
        assertFalse("面板不能压住气泡", panel.intersects(bubble(bubbleX = 320 - bubbleSize)))
        assertTrue("面板要完整在视口内", panel.left >= 0 && panel.right <= 320)
        assertTrue(panel.top >= 0 && panel.bottom <= 1920)
    }

    /** 气泡被拖到屏幕中间（还没松手吸附）：同样不能相交。 */
    @Test
    fun Place_bubbleInMiddle_stillAvoidsOverlap() {
        val placement = place(bubbleX = 460)
        assertFalse(rect(placement).intersects(bubble(bubbleX = 460)))
    }

    /** 气泡贴近屏幕底部：下方放不下，应改放上方。 */
    @Test
    fun Place_bubbleNearBottom_stacksAbove() {
        val placement = place(viewportWidth = 320, bubbleX = 320 - bubbleSize, bubbleY = 1920 - bubbleSize - 10)
        val panel = rect(placement)
        assertFalse(panel.intersects(bubble(bubbleX = 320 - bubbleSize, bubbleY = 1920 - bubbleSize - 10)))
        assertTrue(panel.bottom <= (1920 - bubbleSize - 10) - gap)
    }

    /** 面板比屏幕还宽：不可解，但坐标必须仍可见（不能被推成负数/屏外）。 */
    @Test
    fun Place_panelWiderThanScreen_keepsVisibleCoordinates() {
        val placement = place(viewportWidth = 200, panelWidth = 280)
        assertTrue(placement.x >= 0)
        assertTrue(placement.y >= 0)
    }

    @Test
    fun Place_hugePanelTallerThanScreen_keepsYInViewport() {
        val placement = place(viewportHeight = 300, panelHeight = 500)
        assertTrue(placement.y >= 0)
        assertTrue(placement.y <= (300 - 500).coerceAtLeast(0))
    }

    @Test
    fun IsOnBubble_inside_returnsTrue() {
        assertTrue(
            PanelGeometry.isOnBubble(
                bubbleX = 936, bubbleY = 300, bubbleWidth = 144, bubbleHeight = 144,
                x = 1000, y = 350, padding = 12,
            ),
        )
    }

    @Test
    fun IsOnBubble_withinPadding_returnsTrue() {
        assertTrue(
            PanelGeometry.isOnBubble(
                bubbleX = 936, bubbleY = 300, bubbleWidth = 144, bubbleHeight = 144,
                x = 930, y = 300, padding = 12,
            ),
        )
    }

    @Test
    fun IsOnBubble_farAway_returnsFalse() {
        assertFalse(
            PanelGeometry.isOnBubble(
                bubbleX = 936, bubbleY = 300, bubbleWidth = 144, bubbleHeight = 144,
                x = 100, y = 1000, padding = 12,
            ),
        )
    }
}