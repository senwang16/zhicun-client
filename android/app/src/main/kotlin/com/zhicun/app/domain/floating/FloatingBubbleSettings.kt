package com.zhicun.app.domain.floating

/**
 * 悬浮气泡的开关与位置（M13）。
 *
 * ## 为什么把"开关"和"系统权限"分开
 * 这个开关只表达**用户意愿**。真正能不能显示由 `Settings.canDrawOverlays()` 决定，
 * 而那是框架状态、会随用户在系统设置里的操作变化，不该被复制进 prefs
 * （复制就会出现"开关是开的、权限已撤销、界面还说已开启"的假象）。
 * 界面因此要同时呈现"开关"和"权限状态"两件事，见 `SettingsViewModel`。
 */
interface FloatingBubbleSettings {

    /** 用户是否希望显示悬浮入口。 */
    val enabled: Boolean

    /**
     * 点气泡是否直接自动收录剪贴板（B2；默认值 2026-10-24 随附录 T 改为 **true**）。
     *
     * 打开后点气泡直接走剪贴板收录：有内容就地保存（重复内容去重），
     * 剪贴板为空时改为展开快捷面板（用户可以在面板里点「记录」手动记一条）。
     * 关闭后点气泡只展开面板，不读取剪贴板。
     *
     * 默认值为什么从 false 翻成 true：首启引导（附录 T）教的第一个动作就是
     * "点一下气泡存剪贴板"，默认关会让引导教的和实际发生的对不上——这是比
     * "读剪贴板要弹一次系统粘贴确认"更伤的体验。B2 当初默认关的顾虑仍然成立，
     * 所以保留关闭出口，只是不再作为默认。
     */
    val autoClipboard: Boolean

    /**
     * 重启手机 / 覆盖安装后是否自动把气泡挂回来（2026-10-24）。
     *
     * ## 为什么需要一个独立开关，而不是硬绑在 [enabled] 上
     * 恢复动作（`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`）在用户看来是**后台行为**：
     * 有人希望气泡一直在，也有人只在用的时候才想看到它、不愿它每次开机都冒出来。
     * 绑定在 [enabled] 上时，"少一点后台行为"只能靠彻底关掉气泡来实现，
     * 而那是两件不同的事。默认 **true**：这是加入此开关之前就有的行为，
     * 默认关会静默改变所有老用户的升级后表现。
     *
     * ## 与 [enabled] 是"与"关系，不是"或"
     * 这个开关只能**减少**恢复，不能凭空让一个已被关掉的气泡开机弹出来。
     * 判据见 `FloatingBubbleController.shouldRestoreOnBoot`。
     */
    val autoRestoreOnBoot: Boolean

    /**
     * 「悬浮窗常在」（2026-10-26）：开启后气泡不再因屏幕其它位置的触摸（外部触摸，
     * 即 `ACTION_OUTSIDE`）自动收成贴边细条，始终保持完整气泡形态。默认 **false**
     * （保留既有"一摸别处就收边"的行为，不静默改变老用户体验）。
     *
     * 与 [enabled] 是"与"关系；**不**影响"打开智存主界面时临时隐藏气泡"这一既有行为
     * （那是 `FloatingBubbleService` 里的 `visibleWanted`，与形态无关）。
     */
    val alwaysVisible: Boolean

    /**
     * 「长按录音」（2026-10-26）：开启后，在**完整气泡**上按住超过 500 毫秒进入录音，
     * 抬手即停止并保存为一条语音记录。默认 **true**（按住说话更符合原生直觉）。
     *
     * 仅在 [enabled] 为开时有意义，且贴边细条形态不响应长按。
     */
    val longPressRecord: Boolean

    /** 上次停靠的位置；从未拖动过时返回默认值。 */
    val position: BubblePosition

    fun setEnabled(enabled: Boolean)

    fun setAutoClipboard(enabled: Boolean)

    fun setAutoRestoreOnBoot(enabled: Boolean)

    fun setAlwaysVisible(enabled: Boolean)

    fun setLongPressRecord(enabled: Boolean)

    fun savePosition(position: BubblePosition)
}
