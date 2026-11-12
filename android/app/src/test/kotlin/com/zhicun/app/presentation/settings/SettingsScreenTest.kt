package com.zhicun.app.presentation.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.floating.StandbyBucket
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页布局（Compose + Robolectric）。
 *
 * 只测无状态 [SettingsContent]。M13 验收里最容易漏的是"开关开了但没权限"这个中间态：
 * 用户看不出为什么没反应，所以界面必须同时给出**原因**和**下一步**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var toggles = mutableListOf<Boolean>()
    private var cloudToggles = mutableListOf<Boolean>()
    private var autoClipboardToggles = mutableListOf<Boolean>()
    private var autoRestoreToggles = mutableListOf<Boolean>()
    private var grantClicks = 0
    private var batterySettingsClicks = 0
    private var notificationSettingsClicks = 0
    private var backClicks = 0
    private var feedbackClicks = 0

    // M15：导出与清理
    private var exports = mutableListOf<Boolean>()
    private var cleanups = 0

    // M16：分享面板没打开时的重试
    private var retryShares = 0

    private fun render(uiState: SettingsUiState) {
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    uiState = uiState,
                    onBack = { backClicks++ },
                    onFloatingToggled = { toggles += it },
                    onAutoClipboardToggled = { autoClipboardToggles += it },
                    onAutoRestoreToggled = { autoRestoreToggles += it },
                    onGrantPermission = { grantClicks++ },
                    onOpenBatterySettings = { batterySettingsClicks++ },
                    onOpenNotificationSettings = { notificationSettingsClicks++ },
                    onCloudAiToggled = { cloudToggles += it },
                    onFeedbackClick = { feedbackClicks++ },
                    onExportConfirmed = { exports += it },
                    onCleanupConfirmed = { cleanups++ },
                    onRetryShare = { retryShares++ },
                )
            }
        }
    }

    @Test
    fun Default_switchIsOffAndNoGrantEntry() {
        render(SettingsUiState(floatingEnabled = false, hasOverlayPermission = false))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_SWITCH).assertIsOff()
        composeRule.onNodeWithText("悬浮窗权限：未授权，需要到系统设置里开启").assertIsDisplayed()
        // 开关本来就关着的时候提示授权，属于用户没问就答
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_GRANT).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_BLOCKED).assertDoesNotExist()
    }

    @Test
    fun Granted_showsGrantedStatusWithoutGrantButton() {
        render(SettingsUiState(floatingEnabled = true, hasOverlayPermission = true))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_SWITCH).assertIsOn()
        composeRule.onNodeWithText("悬浮窗权限：已授权").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_GRANT).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_BLOCKED).assertDoesNotExist()
    }

    /** 核心中间态：开关在"开"、权限没有。原因 + 出口都必须出现。 */
    @Test
    fun EnabledWithoutPermission_showsReasonAndGrantButton() {
        render(SettingsUiState(floatingEnabled = true, hasOverlayPermission = false))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_SWITCH).assertIsOn()
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_BLOCKED).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_GRANT).assertIsDisplayed()
    }

    @Test
    fun ClickGrant_notifiesOnce() {
        render(SettingsUiState(floatingEnabled = true, hasOverlayPermission = false))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_GRANT).performClick()

        assertEquals(1, grantClicks)
    }

    /** 回调必须带**目标状态**而不是让 ViewModel 自己取反：取反在快速连点时会对不上。 */
    @Test
    fun ClickSwitch_reportsTargetState() {
        render(SettingsUiState(floatingEnabled = false, hasOverlayPermission = true))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_SWITCH).performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun ClickSwitchOn_reportsFalseWhenTurningOff() {
        render(SettingsUiState(floatingEnabled = true, hasOverlayPermission = true))

        composeRule.onNodeWithTag(SettingsTestTags.FLOATING_SWITCH).performClick()

        assertEquals(listOf(false), toggles)
    }

    // ------------------------------------------------ 自动收录剪贴板（B2）

    /** 悬浮本身没开时子开关是死配置：置灰可见，而不是藏起来。 */
    @Test
    fun AutoClipboardSwitch_disabledWhenFloatingOff() {
        render(SettingsUiState(floatingEnabled = false, autoClipboardEnabled = false))

        composeRule.onNodeWithTag(SettingsTestTags.AUTO_CLIPBOARD_SWITCH)
            .assertIsOff()
            .assertIsNotEnabled()
    }

    @Test
    fun AutoClipboardSwitch_reportsTargetStateWhenFloatingOn() {
        render(SettingsUiState(floatingEnabled = true, autoClipboardEnabled = false))

        composeRule.onNodeWithTag(SettingsTestTags.AUTO_CLIPBOARD_SWITCH).performClick()

        assertEquals(listOf(true), autoClipboardToggles)
    }

    // ------------------------------------------------ 开机自动恢复（2026-10-24）

    /**
     * 主开关的说明必须写清"关掉气泡，开机自启也一并关掉"。
     *
     * 这条文案是用户判断"关气泡会不会顺带关掉后台行为"的唯一依据。
     * 行为上它一直成立（判据是 `enabled && permission`），但从前界面一个字都没提，
     * 于是这个问题只能靠猜。删掉这句话，用户就又回到猜的状态。
     */
    @Test
    fun FloatingHint_statesThatDisablingAlsoDisablesBootRestore() {
        render(SettingsUiState(floatingEnabled = false))

        composeRule.onNodeWithText("开机自启就一并关掉了", substring = true)
            .assertIsDisplayed()
    }

    /** 默认展示为开（与 prefs 一致），且悬浮开着时可操作。 */
    @Test
    fun AutoRestoreSwitch_onAndEnabledWhenFloatingOn() {
        render(SettingsUiState(floatingEnabled = true))

        composeRule.onNodeWithTag(SettingsTestTags.AUTO_RESTORE_SWITCH)
            .performScrollTo()
            .assertIsOn()
            .assertIsEnabled()
    }

    /**
     * 悬浮气泡关掉时它没有意义：置灰可见，但**值保留**（重新打开悬浮时沿用）。
     * 断言"仍然是开"正是为了钉住这一点 —— 顺手把它归零会让用户开了悬浮回来后
     * 发现自己没动过的设置变了。
     */
    @Test
    fun AutoRestoreSwitch_disabledButKeepsValueWhenFloatingOff() {
        render(SettingsUiState(floatingEnabled = false, autoRestoreOnBoot = true))

        composeRule.onNodeWithTag(SettingsTestTags.AUTO_RESTORE_SWITCH)
            .performScrollTo()
            .assertIsOn()
            .assertIsNotEnabled()
    }

    @Test
    fun AutoRestoreSwitch_reportsTargetState() {
        render(SettingsUiState(floatingEnabled = true, autoRestoreOnBoot = true))

        composeRule.onNodeWithTag(SettingsTestTags.AUTO_RESTORE_SWITCH).performScrollTo().performClick()

        assertEquals(listOf(false), autoRestoreToggles)
    }

    /**
     * 关掉开机恢复后，"后台常驻"那句说明不能再写"重启后会自动恢复"。
     * 文案与真值对不上，是用户最没辙的一类误导：他会照着一个已经被自己关掉的说明去等。
     */
    @Test
    fun KeepAlive_hintReflectsAutoRestoreOff() {
        render(
            SettingsUiState(
                floatingEnabled = true,
                autoRestoreOnBoot = false,
                keepAlive = keepAlive(),
            ),
        )

        composeRule.onNodeWithText("「开机自动恢复气泡」已关闭", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun ClickBack_notifiesOnce() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.BACK).performClick()

        assertEquals(1, backClicks)
    }

    @Test
    fun FlowingSectionHeaderIsVisible() {
        render(SettingsUiState())

        composeRule.onNodeWithText("悬浮入口").assertIsDisplayed()
        composeRule.onNodeWithText("显示悬浮气泡").assertIsDisplayed()
    }

    // ------------------------------------------------------ 云端 AI（M14）

    @Test
    fun CloudAiEnabled_showsWhatTurnsOffAndNoWarning() {
        render(SettingsUiState(cloudAiEnabled = true))

        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_SWITCH)
            .performScrollTo()
            .assertIsOn()
        // 开着的时候不该出现"已关闭"的红字提示
        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_OFF_NOTE).assertDoesNotExist()
    }

    /**
     * 关掉云端后必须说清两件事：什么时候生效、什么仍然可用。
     * 用户最担心的是"我关掉之后是不是连搜索都不能用了"，事实并非如此。
     */
    @Test
    fun CloudAiDisabled_showsScopeNote() {
        render(SettingsUiState(cloudAiEnabled = false))

        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_SWITCH)
            .performScrollTo()
            .assertIsOff()
        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_OFF_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun CloudAiSwitch_reportsTargetState() {
        render(SettingsUiState(cloudAiEnabled = true))

        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_SWITCH)
            .performScrollTo()
            .performClick()

        assertEquals(listOf(false), cloudToggles)
    }

    /** 转写没有独立开关，只反映"跟随云端开关"的结果。 */
    @Test
    fun TranscriptionStatus_followsCloudSwitch() {
        render(SettingsUiState(cloudAiEnabled = false))

        composeRule.onNodeWithTag(SettingsTestTags.TRANSCRIPTION_STATUS)
            .performScrollTo()
            .assertTextEquals("当前不可用（云端 AI 已关闭）")
    }

    // -------------------------------------------------- 隐私与关于（M14）

    @Test
    fun PrivacyDetail_hiddenUntilExpanded() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_DETAIL).assertDoesNotExist()

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_TOGGLE).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_DETAIL)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun PrivacyDetail_canBeCollapsedAgain() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_TOGGLE).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_DETAIL).assertExists()

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_TOGGLE).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_DETAIL).assertDoesNotExist()
    }

    @Test
    fun Version_isRenderedFromState() {
        render(SettingsUiState(versionName = "0.1.0", versionCode = 1))

        composeRule.onNodeWithTag(SettingsTestTags.VERSION)
            .performScrollTo()
            .assertTextEquals("版本 0.1.0（1）")
    }

    /** 版本名缺失时（例如被裁剪的构建）不显示"版本 （0）"这种半截文字。 */
    @Test
    fun Version_hiddenWhenNameMissing() {
        render(SettingsUiState(versionName = "", versionCode = 0))

        composeRule.onNodeWithTag(SettingsTestTags.VERSION).assertDoesNotExist()
    }

    @Test
    fun FeedbackClick_notifiesOnce() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.FEEDBACK).performScrollTo().performClick()

        assertEquals(1, feedbackClicks)
    }

    // ------------------------------------------------------------ 导出（M15）

    /**
     * 导出物可能外流，因此**点按钮绝不能直接开始导出** ——
     * 必须先弹一次说明"包里有什么、可能含敏感信息"，由用户确认。
     */
    @Test
    fun ExportButton_opensDialogInsteadOfExporting() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BUTTON).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DIALOG).assertIsDisplayed()
        assertTrue("点按钮不该直接触发导出", exports.isEmpty())
    }

    @Test
    fun ExportDialog_defaultExcludesDerivedContent() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM).performClick()

        assertEquals(listOf(false), exports)
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun ExportDialog_reportsIncludeDerivedFlag() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_INCLUDE_DERIVED).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_CONFIRM).performClick()

        assertEquals(listOf(true), exports)
    }

    @Test
    fun ExportDialog_cancelDoesNotStartExport() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithText("取消").performClick()

        assertTrue(exports.isEmpty())
        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun ExportProgress_showsCountsWhenTotalIsKnown() {
        render(SettingsUiState(exportRunning = true, exportDone = 3, exportTotal = 10))

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_PROGRESS)
            .performScrollTo()
            .assertTextEquals("已处理 3 / 10")
    }

    /** 总数还不知道时只说"正在导出…"—— 显示 "已处理 0 / 0" 像是在报错。 */
    @Test
    fun ExportProgress_fallsBackToRunningText() {
        render(SettingsUiState(exportRunning = true, exportDone = 0, exportTotal = 0))

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_PROGRESS)
            .performScrollTo()
            .assertTextEquals("正在导出…")
    }

    /** 长任务进行中两个按钮都要失效：否则会同时写两个几十 MB 的包。 */
    @Test
    fun Busy_disablesBothActionButtons() {
        render(SettingsUiState(exportRunning = true, exportDone = 1, exportTotal = 4))

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_BUTTON)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_BUTTON)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun Notice_exportDoneCarriesNumbers() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportDone(records = 12, assets = 5, missingAssets = 0),
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.NOTICE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出完成：12 条记录、5 个附件").assertIsDisplayed()
    }

    /** 缺了附件还宣称"导出完成"等于骗人：必须把不完整说出来。 */
    @Test
    fun Notice_exportDoneWithMissingAssetsSaysSo() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportDone(records = 12, assets = 5, missingAssets = 2),
            ),
        )

        composeRule.onNodeWithText("导出完成：12 条记录、5 个附件（有 2 个附件文件已丢失，没能打进包）")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun Notice_exportFailedIsShown() {
        render(SettingsUiState(notice = SettingsNotice.ExportFailed))

        composeRule.onNodeWithText("导出失败，请稍后重试").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------ 分享面板没打开（M16）

    /**
     * 审核 P2：包写好了但分享面板没起来时，**不能**显示"导出完成" —— 包在应用私有缓存里，
     * 用户没有任何入口能自己翻到它。必须说清现状并给出口。
     */
    @Test
    fun Notice_exportNotSharedSaysPackageExistsButPanelFailed() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportNotShared(records = 12, assets = 5, missingAssets = 0),
                canRetryShare = true,
            ),
        )

        composeRule.onNodeWithText(
            "导出包已生成（12 条记录、5 个附件），但系统分享面板没能打开。" +
                "包暂存在应用缓存里，可以点下面的按钮再试一次。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.RETRY_SHARE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun Notice_exportNotSharedMentionsMissingAssets() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportNotShared(records = 12, assets = 5, missingAssets = 2),
                canRetryShare = true,
            ),
        )

        composeRule.onNodeWithText(
            "导出包已生成（12 条记录、5 个附件，其中 2 个附件文件已丢失），" +
                "但系统分享面板没能打开。包暂存在应用缓存里，可以点下面的按钮再试一次。",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RetryShareButton_reportsOnce() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportNotShared(records = 1, assets = 0, missingAssets = 0),
                canRetryShare = true,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.RETRY_SHARE).performScrollTo().performClick()

        assertEquals(1, retryShares)
    }

    /** 包已被系统回收时不能给按钮：点了只会再失败一次，等于骗用户再试。 */
    @Test
    fun RetryShareButton_hiddenWhenArchiveIsGone() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportNotShared(records = 1, assets = 0, missingAssets = 0),
                canRetryShare = false,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.RETRY_SHARE).assertDoesNotExist()
    }

    /** 别的提示不该带着"重新打开分享面板"的按钮。 */
    @Test
    fun RetryShareButton_onlyOnExportNotSharedNotice() {
        render(
            SettingsUiState(
                notice = SettingsNotice.ExportDone(records = 1, assets = 0, missingAssets = 0),
                canRetryShare = true,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.RETRY_SHARE).assertDoesNotExist()
    }

    // ------------------------------------------------------------ 清理（M15）

    @Test
    fun CleanupButton_opensConfirmationDialog() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_BUTTON).performScrollTo().performClick()

        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_DIALOG).assertIsDisplayed()
        assertEquals("必须经确认才删", 0, cleanups)
    }

    @Test
    fun CleanupDialog_confirmReportsOnce() {
        render(SettingsUiState())

        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_CONFIRM).performClick()

        assertEquals(1, cleanups)
        composeRule.onNodeWithTag(SettingsTestTags.CLEANUP_DIALOG).assertDoesNotExist()
    }

    @Test
    fun Notice_cleanupNothingIsNotAnError() {
        render(SettingsUiState(notice = SettingsNotice.CleanupNothing))

        composeRule.onNodeWithText("没有需要清理的记录").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun Notice_cleanupDoneCarriesNumbers() {
        render(
            SettingsUiState(
                notice = SettingsNotice.CleanupDone(records = 3, assets = 7, failedFiles = 0),
            ),
        )

        composeRule.onNodeWithText("已清理 3 条记录、7 个附件").performScrollTo().assertIsDisplayed()
    }

    /** 有文件没删掉时必须说清"磁盘空间没释放干净"，否则用户会以为清理白做了。 */
    @Test
    fun Notice_cleanupWithUndeletableFilesSaysSo() {
        render(
            SettingsUiState(
                notice = SettingsNotice.CleanupDone(records = 3, assets = 7, failedFiles = 2),
            ),
        )

        composeRule.onNodeWithText("已清理，但有 2 个附件文件没能删除，可稍后再试")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------ 后台常驻自检（2026-10-24）

    private fun keepAlive(
        batteryIgnored: Boolean = true,
        notifications: Boolean = true,
        bucket: StandbyBucket = StandbyBucket.ACTIVE,
    ) = KeepAliveStatus(
        batteryOptimizationIgnored = batteryIgnored,
        notificationsEnabled = notifications,
        standbyBucket = bucket,
    )

    /** 气泡没开时不显示自检：那时候这些限制没有任何后果。 */
    @Test
    fun KeepAlive_hiddenWhenFloatingDisabled() {
        render(SettingsUiState(floatingEnabled = false, keepAlive = keepAlive(batteryIgnored = false)))

        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_BATTERY).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_WIDGET_TIP).assertDoesNotExist()
    }

    /**
     * 防后台清理的首要建议（用户实测）：桌面小组件 + 启动管理三项。
     * 文案必须把"三项"点名，用户才知道去系统设置里勾哪几个。
     */
    @Test
    fun KeepAlive_showsWidgetAndLaunchManagementTip() {
        render(SettingsUiState(floatingEnabled = true, keepAlive = keepAlive()))

        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_WIDGET_TIP)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("自启动、关联启动、后台活动", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun KeepAlive_showsBlockedBatteryWithSettingsEntry() {
        render(SettingsUiState(floatingEnabled = true, keepAlive = keepAlive(batteryIgnored = false)))

        composeRule.onNodeWithText("未加入白名单，系统可能在省电时限制后台")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_BATTERY_ACTION).performClick()

        assertEquals(1, batterySettingsClicks)
    }

    @Test
    fun KeepAlive_showsBlockedNotificationsWithSettingsEntry() {
        render(SettingsUiState(floatingEnabled = true, keepAlive = keepAlive(notifications = false)))

        composeRule.onNodeWithText("已关闭，前台服务可能被当成后台进程清理")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_NOTIFICATION_ACTION).performClick()

        assertEquals(1, notificationSettingsClicks)
    }

    /**
     * 一切正常时不给按钮。
     *
     * 摆一个"去设置"在那里，用户会以为点进去还能优化什么。
     */
    @Test
    fun KeepAlive_allGood_hasNoSettingsEntry() {
        render(SettingsUiState(floatingEnabled = true, keepAlive = keepAlive()))

        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_BATTERY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_BATTERY_ACTION).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_NOTIFICATION_ACTION).assertDoesNotExist()
    }

    /** 分桶查不到的版本整行不显示，而不是显示"正常"。 */
    @Test
    fun KeepAlive_hidesStandbyRowWhenBucketUnknown() {
        render(
            SettingsUiState(
                floatingEnabled = true,
                keepAlive = keepAlive(bucket = StandbyBucket.UNKNOWN),
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_BATTERY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.KEEP_ALIVE_STANDBY).assertDoesNotExist()
    }
}
