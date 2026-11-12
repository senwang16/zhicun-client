package com.zhicun.app.service

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import com.zhicun.app.fake.FakeFloatingBubbleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 剪贴板中转页与开机恢复的**契约守卫**（2026-11-10 Bug 1 / Bug 2）。
 *
 * ## 为什么这两组断言值得存在
 * 两个 bug 的共同形态都是"某个中间态没人兜"：
 * - 中转页卡死后，系统会一直复用那个死 task，**代码里任何"下次会重建"的假设都不成立**，
 *   只有启动参数（`CLEAR_TASK`）与清单声明能保证下一次是从头开始的；
 * - 开机恢复被系统拒绝后，原实现把异常吞在 `start()` 里，调用方无从补救。
 *   这里的 `restoreOnBoot` 断言把"判据不过就必须跳过、不许照调 sync"钉住
 *   （PLAYBOOK 悬浮气泡一节的既有裁决），防止回归成"开机未经允许把气泡弹出来"。
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardCaptureContractTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun launchIntent_replacesAnyDeadTaskAndSkipsAnimation() {
        val intent = ClipboardCaptureActivity.launchIntent(app)

        assertEquals(
            ClipboardCaptureActivity::class.java.name,
            intent.component?.className,
        )
        assertTrue(
            "从 Service 发起必须新建 task",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(
            "必须清掉上次遗留的 task：死 task 在栈顶时后续点击连 onCreate 都不执行",
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0,
        )
        assertTrue(
            "透明页不该参与转场动画",
            intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0,
        )
    }

    @Test
    fun manifest_declaresClipboardPageAsTransient() {
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, ClipboardCaptureActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse("中转页是内部入口，不能暴露给外部应用", info.exported)
        assertTrue(
            "离开即销毁：不允许它留在最近任务或回退栈里",
            info.flags and ActivityInfo.FLAG_NO_HISTORY != 0,
        )
        assertTrue(
            "任务亲和性置空，避免并入智存主任务栈（否则返回会回到主界面）",
            info.taskAffinity.isNullOrEmpty(),
        )
    }

    // ------------------------------------------------------------ 开机恢复判据（Bug 2）

    @Test
    fun restoreOnBoot_autoRestoreOff_skipsWithoutStartingService() {
        val settings = FakeFloatingBubbleSettings(enabled = true, autoRestoreOnBoot = false)

        assertEquals(
            BootRestoreResult.SKIPPED,
            FloatingBubbleController.restoreOnBoot(app, settings),
        )
        assertNull(
            "判据不过时绝不能照调 sync —— 那会把一个用户明确关掉的气泡在开机时弹出来",
            shadowOf(app).nextStartedService,
        )
    }

    @Test
    fun restoreOnBoot_noOverlayPermission_skipsWithoutStartingService() {
        // Robolectric 默认没有 SYSTEM_ALERT_WINDOW 授权
        assertFalse(FloatingBubbleController.hasOverlayPermission(app))

        val settings = FakeFloatingBubbleSettings(enabled = true, autoRestoreOnBoot = true)
        assertEquals(
            BootRestoreResult.SKIPPED,
            FloatingBubbleController.restoreOnBoot(app, settings),
        )
        assertNull(shadowOf(app).nextStartedService)
    }
}