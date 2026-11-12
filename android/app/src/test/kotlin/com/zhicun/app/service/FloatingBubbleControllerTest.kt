package com.zhicun.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡启停判据（M13）。
 *
 * 整条链路里唯一有分支的地方就是这个纯函数，所以穷举它的真值表。
 * 特别注意"没权限"那一列：必须**不**启动 —— 否则 `addView` 会抛
 * `SecurityException`，而服务是前台服务，崩溃表现是反复重启的通知栏闪烁。
 */
class FloatingBubbleControllerTest {

    @Test
    fun ShouldRun_enabledAndGranted_isTrue() {
        assertTrue(FloatingBubbleController.shouldRun(enabled = true, hasOverlayPermission = true))
    }

    @Test
    fun ShouldRun_enabledButNoPermission_isFalse() {
        assertFalse(FloatingBubbleController.shouldRun(enabled = true, hasOverlayPermission = false))
    }

    @Test
    fun ShouldRun_disabledButGranted_isFalse() {
        assertFalse(FloatingBubbleController.shouldRun(enabled = false, hasOverlayPermission = true))
    }

    @Test
    fun ShouldRun_bothFalse_isFalse() {
        assertFalse(FloatingBubbleController.shouldRun(enabled = false, hasOverlayPermission = false))
    }

    // ------------------------------------------------ 开机 / 覆盖安装后的恢复（2026-10-24）

    @Test
    fun ShouldRestoreOnBoot_allThree_true() {
        assertTrue(
            FloatingBubbleController.shouldRestoreOnBoot(
                enabled = true,
                hasOverlayPermission = true,
                autoRestoreOnBoot = true,
            ),
        )
    }

    /**
     * 这条是本次改动的核心：用户关掉「开机自动恢复气泡」后，**即使气泡开关还开着、
     * 权限也还在**，重启后也不该自己冒出来。
     *
     * 反过来说，如果这里写成 `enabled && hasOverlayPermission`（漏掉第三个条件），
     * 开关就成了摆设：用户点了没反应，而且只有重启手机才看得出来。
     */
    @Test
    fun ShouldRestoreOnBoot_autoRestoreOff_isFalseEvenIfEnabled() {
        assertFalse(
            FloatingBubbleController.shouldRestoreOnBoot(
                enabled = true,
                hasOverlayPermission = true,
                autoRestoreOnBoot = false,
            ),
        )
    }

    @Test
    fun ShouldRestoreOnBoot_enabledButNoPermission_isFalse() {
        assertFalse(
            FloatingBubbleController.shouldRestoreOnBoot(
                enabled = true,
                hasOverlayPermission = false,
                autoRestoreOnBoot = true,
            ),
        )
    }

    @Test
    fun ShouldRestoreOnBoot_disabled_isFalse() {
        assertFalse(
            FloatingBubbleController.shouldRestoreOnBoot(
                enabled = false,
                hasOverlayPermission = true,
                autoRestoreOnBoot = true,
            ),
        )
    }

    /**
     * 恢复只能是运行的**子集**：任何让 `shouldRun` 为假的情况都必须让恢复也为假。
     * 穷举一遍，防止将来有人在 [FloatingBubbleController.shouldRun] 里加了条件却忘了这里。
     */
    @Test
    fun ShouldRestoreOnBoot_neverTrueWhenShouldRunFalse() {
        for (enabled in listOf(true, false)) {
            for (granted in listOf(true, false)) {
                for (autoRestore in listOf(true, false)) {
                    val run = FloatingBubbleController.shouldRun(enabled, granted)
                    val restore = FloatingBubbleController.shouldRestoreOnBoot(enabled, granted, autoRestore)
                    if (!run) {
                        assertFalse("shouldRun=false 时恢复必须为 false（enabled=$enabled granted=$granted）", restore)
                    }
                }
            }
        }
    }

    // ------------------------------------------------ 服务健康检查（2026-10-27，PRD v1.2 §2.1）

    @Test
    fun HealthStatus_enabledGrantedButNotRunning_isMissing() {
        assertEquals(
            BubbleServiceHealth.MISSING,
            FloatingBubbleController.healthStatus(
                enabled = true,
                hasOverlayPermission = true,
                isRunning = false,
            ),
        )
    }

    @Test
    fun HealthStatus_enabledGrantedAndRunning_isRunning() {
        assertEquals(
            BubbleServiceHealth.RUNNING,
            FloatingBubbleController.healthStatus(
                enabled = true,
                hasOverlayPermission = true,
                isRunning = true,
            ),
        )
    }

    /** 用户自己关掉气泡不算异常；提示"服务没运行"只会让人莫名其妙。 */
    @Test
    fun HealthStatus_disabled_isNotNeeded() {
        assertEquals(
            BubbleServiceHealth.NOT_NEEDED,
            FloatingBubbleController.healthStatus(
                enabled = false,
                hasOverlayPermission = true,
                isRunning = false,
            ),
        )
    }

    /** 没有权限时服务本就不该运行（addView 会抛 SecurityException），同样不算异常。 */
    @Test
    fun HealthStatus_noPermission_isNotNeeded() {
        assertEquals(
            BubbleServiceHealth.NOT_NEEDED,
            FloatingBubbleController.healthStatus(
                enabled = true,
                hasOverlayPermission = false,
                isRunning = false,
            ),
        )
    }
}
