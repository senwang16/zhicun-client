package com.zhicun.app.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BootCompletedReceiver] 的广播筛选（2026-10-24）。
 *
 * ## 为什么只测这个函数
 * `onReceive` 的其余部分只是一句 `gateway.sync()`，判断逻辑全在
 * `FloatingBubbleController.shouldRun`（已单独覆盖）。真正会出错的是**收哪些广播**：
 * 收窄了 → 重启后气泡不回来；放宽了 → 任意广播都可能把气泡弹出来。
 *
 * ## 为什么值得单列一条"其他 action 不恢复"
 * 清单里的 intent-filter 是可以被 `tools:node` 合并修改的。
 * 如果这里写成"有 action 就恢复"，将来谁加了个无害的广播，
 * 气泡就会在最莫名其妙的时机出现 —— 而那种 bug 极难归因到这个函数。
 */
@RunWith(RobolectricTestRunner::class)
class BootCompletedReceiverTest {

    @Test
    fun isRestoreAction_trueForBootCompleted() {
        assertTrue(BootCompletedReceiver.isRestoreAction(Intent.ACTION_BOOT_COMPLETED))
    }

    /** 覆盖安装后进程会被系统杀掉，不接这条广播气泡就在每次更新后消失。 */
    @Test
    fun isRestoreAction_trueForPackageReplaced() {
        assertTrue(BootCompletedReceiver.isRestoreAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun isRestoreAction_falseForUnrelatedActions() {
        assertFalse(BootCompletedReceiver.isRestoreAction(Intent.ACTION_POWER_CONNECTED))
        assertFalse(BootCompletedReceiver.isRestoreAction(Intent.ACTION_SCREEN_ON))
        assertFalse(BootCompletedReceiver.isRestoreAction("com.zhicun.app.SOMETHING_ELSE"))
    }

    /** intent 为 null（系统在某些异常路径上会这样投递）时不能崩，也不该去起服务。 */
    @Test
    fun isRestoreAction_falseForNull() {
        assertFalse(BootCompletedReceiver.isRestoreAction(null))
    }
}
