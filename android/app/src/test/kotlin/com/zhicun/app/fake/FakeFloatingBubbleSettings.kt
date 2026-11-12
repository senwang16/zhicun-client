package com.zhicun.app.fake

import com.zhicun.app.domain.floating.BubbleEdge
import com.zhicun.app.domain.floating.BubblePosition
import com.zhicun.app.domain.floating.FloatingBubbleSettings

/**
 * 内存版悬浮设置（M13）。
 *
 * 与 [PreferencesFloatingBubbleSettings] 一样**不做**校验：写入什么就存什么，
 * 好让测试能构造出"y 是个荒唐大数""枚举名对不上"这类真机上由旧版本或备份恢复产生的坏数据。
 *
 * 属性是 `private set`：接口里已经有 `setEnabled` 方法，Kotlin 为 `var enabled` 生成的
 * `setEnabled(Z)V` 会与它签名冲突。要预置初值走构造参数。
 */
class FakeFloatingBubbleSettings(
    enabled: Boolean = false,
    autoClipboard: Boolean = true,
    /**
     * 默认值与 [PreferencesFloatingBubbleSettings] 保持一致（true）。
     * 假实现给出不同默认值，测出来的就是假象 —— 生产里默认恢复、测试里默认不恢复。
     */
    autoRestoreOnBoot: Boolean = true,
    /** 默认与 [PreferencesFloatingBubbleSettings] 保持一致（false）。 */
    alwaysVisible: Boolean = false,
    /** 默认与 [PreferencesFloatingBubbleSettings] 保持一致（true）。 */
    longPressRecord: Boolean = true,
    position: BubblePosition = BubblePosition(edge = BubbleEdge.RIGHT, y = BubblePosition.DEFAULT_Y),
) : FloatingBubbleSettings {

    override var enabled: Boolean = enabled
        private set

    override var autoClipboard: Boolean = autoClipboard
        private set

    override var autoRestoreOnBoot: Boolean = autoRestoreOnBoot
        private set

    override var alwaysVisible: Boolean = alwaysVisible
        private set

    override var longPressRecord: Boolean = longPressRecord
        private set

    override var position: BubblePosition = position
        private set

    /** 记录写入历史，用来断言"先落偏好再同步"这类顺序约束。 */
    val enabledWrites: MutableList<Boolean> = mutableListOf()
    val autoClipboardWrites: MutableList<Boolean> = mutableListOf()
    val autoRestoreOnBootWrites: MutableList<Boolean> = mutableListOf()
    val alwaysVisibleWrites: MutableList<Boolean> = mutableListOf()
    val longPressRecordWrites: MutableList<Boolean> = mutableListOf()
    val savedPositions: MutableList<BubblePosition> = mutableListOf()

    override fun setEnabled(enabled: Boolean) {
        enabledWrites += enabled
        this.enabled = enabled
    }

    override fun setAutoClipboard(enabled: Boolean) {
        autoClipboardWrites += enabled
        this.autoClipboard = enabled
    }

    override fun setAutoRestoreOnBoot(enabled: Boolean) {
        autoRestoreOnBootWrites += enabled
        this.autoRestoreOnBoot = enabled
    }

    override fun setAlwaysVisible(enabled: Boolean) {
        alwaysVisibleWrites += enabled
        this.alwaysVisible = enabled
    }

    override fun setLongPressRecord(enabled: Boolean) {
        longPressRecordWrites += enabled
        this.longPressRecord = enabled
    }

    override fun savePosition(position: BubblePosition) {
        savedPositions += position
        this.position = position
    }
}
