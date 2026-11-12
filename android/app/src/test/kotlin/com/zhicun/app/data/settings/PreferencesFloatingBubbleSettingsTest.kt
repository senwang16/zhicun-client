package com.zhicun.app.data.settings

import com.zhicun.app.domain.floating.BubbleEdge
import com.zhicun.app.domain.floating.BubblePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 悬浮设置的 SharedPreferences 实现（M13）。
 *
 * 除了"存得下、读得回"，重点测**读坏数据不崩**：prefs 里可能留着旧版本写的值，
 * 也可能被 `adb` / 备份恢复改成任意字符串。悬浮入口是锦上添花的功能，
 * 一个坏字符串把它变成启动崩溃是不划算的。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesFloatingBubbleSettingsTest {

    private fun newSettings() = PreferencesFloatingBubbleSettings(RuntimeEnvironment.getApplication())

    private fun rawPrefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("floating_bubble", android.content.Context.MODE_PRIVATE)

    @Test
    fun Defaults_switchOffAndRightEdge() {
        val settings = newSettings()

        assertFalse("默认必须是关的：未经用户同意就显示悬浮窗不可接受", settings.enabled)
        assertEquals(BubbleEdge.RIGHT, settings.position.edge)
        assertEquals(BubblePosition.DEFAULT_Y, settings.position.y)
    }

    @Test
    fun SetEnabled_persistsAcrossInstances() {
        newSettings().setEnabled(true)

        // 换一个实例读：服务与设置页是两个独立注入点，必须共享同一份值
        assertTrue(newSettings().enabled)
    }

    @Test
    fun SavePosition_persistsEdgeAndY() {
        newSettings().savePosition(BubblePosition(edge = BubbleEdge.LEFT, y = 640))

        val restored = newSettings().position
        assertEquals(BubbleEdge.LEFT, restored.edge)
        assertEquals(640, restored.y)
    }

    /** 枚举名对不上（旧版本枚举改名 / 手工改坏）时退回默认侧，不能让读取抛异常。 */
    @Test
    fun Position_unknownEdgeName_fallsBackToDefaultEdge() {
        rawPrefs().edit().putString("edge", "TOP_LEFT").putInt("y", 512).commit()

        val restored = newSettings().position
        assertEquals(BubbleEdge.RIGHT, restored.edge)
        // y 是能用的，不该跟着一起丢
        assertEquals(512, restored.y)
    }

    /**
     * 存储层不做夹取：屏幕尺寸只有在拿到真实视口后才能判断，
     * 那是 `BubbleGeometry.clampY` 的职责。这里原样保存，避免两处各夹一次、规则还不一致。
     */
    @Test
    fun Position_extremeY_isStoredAsIs() {
        newSettings().savePosition(BubblePosition(edge = BubbleEdge.LEFT, y = 999_999))

        assertEquals(999_999, newSettings().position.y)
    }

    @Test
    fun SetEnabled_falseAfterTrue_isPersisted() {
        val settings = newSettings()
        settings.setEnabled(true)
        settings.setEnabled(false)

        assertFalse(settings.enabled)
    }

    // ------------------------------------------------ 自动收录剪贴板（B2；附录 T 改默认）

    /**
     * 默认必须是**开**的（2026-10-24 附录 T 拍板）：首启引导教的第一个动作就是
     * "点一下气泡存剪贴板"，默认关会让引导文案与实际行为对不上。
     * 刻意关掉的用户 prefs 里有显式 false，不受默认值影响。
     */
    @Test
    fun AutoClipboard_defaultIsOn() {
        assertTrue(newSettings().autoClipboard)
    }

    @Test
    fun AutoClipboard_persistsAcrossInstances() {
        newSettings().setAutoClipboard(false)

        // 服务（决定点气泡行为）与设置页是两个独立注入点，必须共享同一份值
        assertFalse(newSettings().autoClipboard)
    }

    // ------------------------------------------------ 开机自动恢复（2026-10-24）

    /**
     * 默认必须是**开**的。
     *
     * 这个开关是后加的，在此之前"重启后恢复气泡"是硬编码行为。默认给关，
     * 所有老用户升级后重启都会发现气泡没了 —— 而他们从没关过任何东西，
     * 只会当成 bug 来报，不会想到去设置里找一个新开关。
     */
    @Test
    fun AutoRestoreOnBoot_defaultIsOn() {
        assertTrue(newSettings().autoRestoreOnBoot)
    }

    /** 关掉后要能持久化：Receiver 在另一个进程启动路径上读它，不能靠内存态。 */
    @Test
    fun AutoRestoreOnBoot_persistsOffAcrossInstances() {
        newSettings().setAutoRestoreOnBoot(false)

        assertFalse(newSettings().autoRestoreOnBoot)
    }

    // ------------------------------------------------ 悬浮窗常在（2026-10-26）

    /**
     * 默认必须是**关**的：这是新开关，默认开会静默改变所有老用户"一摸别处就贴边收成小尾巴"
     * 的既有行为。
     */
    @Test
    fun AlwaysVisible_defaultIsOff() {
        assertFalse(newSettings().alwaysVisible)
    }

    @Test
    fun AlwaysVisible_persistsAcrossInstances() {
        newSettings().setAlwaysVisible(true)

        assertTrue(newSettings().alwaysVisible)
    }

    // ------------------------------------------------ 长按录音（2026-10-26）

    /** 默认 **开**：按住说话更符合原生直觉，且贴边细条不响应长按，无"误触在细条上录音"之虞。 */
    @Test
    fun LongPressRecord_defaultIsOn() {
        assertTrue(newSettings().longPressRecord)
    }

    @Test
    fun LongPressRecord_persistsOffAcrossInstances() {
        newSettings().setLongPressRecord(false)

        assertFalse(newSettings().longPressRecord)
    }
}
