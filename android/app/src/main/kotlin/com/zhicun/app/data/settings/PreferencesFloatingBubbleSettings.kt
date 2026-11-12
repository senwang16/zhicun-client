package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zhicun.app.domain.floating.BubbleEdge
import com.zhicun.app.domain.floating.BubblePosition
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FloatingBubbleSettings] 的 SharedPreferences 实现（M13）。
 *
 * ## 为什么不缓存
 * 位置是在**拖动结束时**写入、在**服务重建时**读取，两边都不频繁。
 * 缓存反而会引入"设置页关了开关、服务还拿着旧值"的时序问题。
 *
 * ## 读取要容错
 * prefs 里的值可能是旧版本写的、也可能被手工改坏（`adb` / 备份恢复）。
 * 枚举名解析失败时退回默认值，绝不让一个坏字符串把启动流程炸掉 ——
 * 悬浮入口属于"锦上添花"的功能，它挂了不该影响 App 其他部分。
 */
@Singleton
class PreferencesFloatingBubbleSettings @Inject constructor(
    @ApplicationContext context: Context,
) : FloatingBubbleSettings {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * 默认 **true**（2026-10-24 附录 T 拍板）：首启引导教的第一个动作就是"点一下气泡
     * 存剪贴板"，默认关会让引导文案与实际行为对不上。这是刻意**推翻 B2 当初的
     * "默认关"**——B2 默认关的理由是"自动读剪贴板强侵入"，但产品方向已把
     * 点气泡收录定为核心交互；仍想避免读剪贴板的用户可以关掉（关闭后点气泡只展开面板）。
     * 老用户（内测）里刻意关过的：prefs 里已有显式 false，不受默认值影响。
     */
    override val autoClipboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLIPBOARD, true)

    /**
     * 默认 **true**：这个开关是 2026-10-24 才加的，之前开机恢复是硬编码行为。
     * 默认给 false 会让所有老用户升级后"重启气泡就不见了"，而那看起来像 bug 而不是设置。
     */
    override val autoRestoreOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTORE_ON_BOOT, true)

    /**
     * 默认 **false**：2026-10-26 新增开关。默认关保留既有"外部触摸收成贴边细条"的行为，
     * 不静默改变所有老用户的体验；想要"一直保持完整气泡"的用户自行开启。
     */
    override val alwaysVisible: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_VISIBLE, false)

    /**
     * 默认 **true**：2026-10-26 新增。长按录音（按住说话）更接近原生直觉，定为默认开启。
     */
    override val longPressRecord: Boolean
        get() = prefs.getBoolean(KEY_LONG_PRESS_RECORD, true)

    override val position: BubblePosition
        get() {
            val edge = prefs.getString(KEY_EDGE, null)
                ?.let { name -> BubbleEdge.entries.firstOrNull { it.name == name } }
                ?: BubblePosition().edge
            return BubblePosition(
                edge = edge,
                y = prefs.getInt(KEY_Y, BubblePosition.DEFAULT_Y),
            )
        }

    override fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    override fun setAutoClipboard(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_CLIPBOARD, enabled) }
    }

    override fun setAutoRestoreOnBoot(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_RESTORE_ON_BOOT, enabled) }
    }

    override fun setAlwaysVisible(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ALWAYS_VISIBLE, enabled) }
    }

    override fun setLongPressRecord(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LONG_PRESS_RECORD, enabled) }
    }

    override fun savePosition(position: BubblePosition) {
        prefs.edit {
            putString(KEY_EDGE, position.edge.name)
            putInt(KEY_Y, position.y)
        }
    }

    private companion object {
        const val PREFS_NAME = "floating_bubble"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_CLIPBOARD = "auto_clipboard"
        const val KEY_AUTO_RESTORE_ON_BOOT = "auto_restore_on_boot"
        const val KEY_ALWAYS_VISIBLE = "always_visible"
        const val KEY_LONG_PRESS_RECORD = "long_press_record"
        const val KEY_EDGE = "edge"
        const val KEY_Y = "y"
    }
}
