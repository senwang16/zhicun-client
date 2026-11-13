package com.zhicun.app.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.widget.QuickCaptureWidgetProvider

/** 测试与无障碍定位用的 tag。 */
object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val BACK = "settings_back"
    const val FLOATING_SWITCH = "settings_floating_switch"
    const val AUTO_CLIPBOARD_SWITCH = "settings_auto_clipboard_switch"
    const val AUTO_RESTORE_SWITCH = "settings_auto_restore_switch"
    const val ALWAYS_VISIBLE_SWITCH = "settings_always_visible_switch"
    const val LONG_PRESS_RECORD_SWITCH = "settings_long_press_record_switch"
    const val FLOATING_PERMISSION = "settings_floating_permission"
    const val FLOATING_BLOCKED = "settings_floating_blocked"
    const val FLOATING_GRANT = "settings_floating_grant"
    const val KEEP_ALIVE_BATTERY = "settings_keepalive_battery"
    const val KEEP_ALIVE_NOTIFICATIONS = "settings_keepalive_notifications"
    const val KEEP_ALIVE_STANDBY = "settings_keepalive_standby"
    const val KEEP_ALIVE_BATTERY_ACTION = "settings_keepalive_battery_action"
    const val KEEP_ALIVE_NOTIFICATION_ACTION = "settings_keepalive_notification_action"
    const val KEEP_ALIVE_WIDGET_TIP = "settings_keepalive_widget_tip"
    const val CLOUD_SWITCH = "settings_cloud_switch"
    const val CLOUD_OFF_NOTE = "settings_cloud_off_note"
    const val TRANSCRIPTION_STATUS = "settings_transcription_status"

    // AI 服务配置（开源改造 P0）：三组 Provider 用同一套后缀，便于测试按卡片定位
    const val AI_OFFICIAL_SWITCH = "settings_ai_official_switch"
    const val AI_OFFICIAL_STATUS = "settings_ai_official_status"
    const val AI_OFFICIAL_BASE_URL = "settings_ai_official_base_url"
    const val AI_OFFICIAL_TOKEN = "settings_ai_official_token"
    const val AI_OFFICIAL_SAVE = "settings_ai_official_save"
    const val AI_VECTOR_STATUS = "settings_ai_vector_status"
    const val AI_VECTOR_REEMBED = "settings_ai_vector_reembed"
    const val AI_NOTICE = "settings_ai_notice"
    const val AI_CHAT_PROTOCOL_OPENAI = "settings_ai_chat_protocol_openai"
    const val AI_CHAT_PROTOCOL_ANTHROPIC = "settings_ai_chat_protocol_anthropic"
    const val AI_CHAT_PREFIX = "settings_ai_chat_"
    const val AI_EMBED_PREFIX = "settings_ai_embed_"
    const val AI_ASR_PREFIX = "settings_ai_asr_"
    const val AI_BASE_URL_SUFFIX = "base_url"
    const val AI_API_KEY_SUFFIX = "api_key"
    const val AI_MODEL_SUFFIX = "model"
    const val AI_SAVE_SUFFIX = "save"
    const val AI_STATUS_SUFFIX = "status"
    const val EXPORT_BUTTON = "settings_export_button"
    const val EXPORT_DIALOG = "settings_export_dialog"
    const val EXPORT_INCLUDE_DERIVED = "settings_export_include_derived"
    const val EXPORT_CONFIRM = "settings_export_confirm"
    const val EXPORT_PROGRESS = "settings_export_progress"
    const val CLEANUP_BUTTON = "settings_cleanup_button"
    const val CLEANUP_DIALOG = "settings_cleanup_dialog"
    const val CLEANUP_CONFIRM = "settings_cleanup_confirm"
    const val NOTICE = "settings_notice"
    const val RETRY_SHARE = "settings_retry_share"
    const val PRIVACY_TOGGLE = "settings_privacy_toggle"
    const val PRIVACY_DETAIL = "settings_privacy_detail"
    const val VERSION = "settings_version"
    const val FEEDBACK = "settings_feedback"

    // 语言分组（P11）：两个单选项，点一下立即切换
    const val LANGUAGE_ZH = "settings_language_zh"
    const val LANGUAGE_EN = "settings_language_en"
}

/**
 * 设置页（有状态壳）。
 *
 * ## 为什么要监听 ON_RESUME
 * "去系统设置授权"会让用户离开本页再回来，这条路径上**没有任何回调**能告诉应用
 * 权限变了。唯一的时机就是回到前台。不刷新的话，用户明明已经授权，页面还写着"未授权"，
 * 开关也拨不过来。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    SettingsContent(
        uiState = uiState,
        onBack = onBack,
        onFloatingToggled = viewModel::onFloatingToggled,
        onAutoClipboardToggled = viewModel::onAutoClipboardToggled,
        onAutoRestoreToggled = viewModel::onAutoRestoreOnBootToggled,
        onAlwaysVisibleToggled = viewModel::onAlwaysVisibleToggled,
        onLongPressRecordToggled = viewModel::onLongPressRecordToggled,
        onGrantPermission = viewModel::onGrantPermissionClick,
        onOpenBatterySettings = viewModel::onOpenBatterySettingsClick,
        onOpenNotificationSettings = viewModel::onOpenNotificationSettingsClick,
        onCloudAiToggled = viewModel::onCloudAiToggled,
        onUseOfficialToggled = viewModel::onUseOfficialServiceToggled,
        onSaveOfficial = viewModel::onSaveOfficialService,
        onSaveChat = viewModel::onSaveChatProvider,
        onChatProtocolSelected = viewModel::onChatProtocolSelected,
        onSaveEmbed = viewModel::onSaveEmbedProvider,
        onSaveAsr = viewModel::onSaveAsrProvider,
        onReEmbed = viewModel::onReEmbedClick,
        onLanguageSelected = { language ->
            viewModel.onLanguageSelected(language)
            // 小组件不在 Activity 资源链上，不主动重绘会停在旧语言（P11-05）
            QuickCaptureWidgetProvider.refreshAll(context)
            // Android 13+ 系统已按 applicationLocales 重建，再调一次就是双重建闪两下
            if (viewModel.recreateRequiredAfterSelect) activity?.recreate()
        },
        onFeedbackClick = viewModel::onFeedbackClick,
        onExportConfirmed = viewModel::onExportConfirmed,
        onCleanupConfirmed = viewModel::onCleanupConfirmed,
        onRetryShare = viewModel::onRetryShare,
        modifier = modifier,
    )
}

/**
 * 设置页（无状态）。
 *
 * @param onFloatingToggled 悬浮气泡开关回调，参数是**目标状态**（不是取反）。
 * @param onAutoClipboardToggled 点气泡自动收录剪贴板开关回调（B2），同样是目标状态。
 * @param onAutoRestoreToggled 开机自动恢复气泡开关回调，同样是目标状态。
 * @param onGrantPermission 点"去系统设置授权"回调。
 * @param onOpenBatterySettings 点保活自检里"去设置"（电池优化）回调。
 * @param onOpenNotificationSettings 点保活自检里"去设置"（通知）回调。
 * @param onCloudAiToggled 云端 AI 开关回调，同样是目标状态。
 * @param onUseOfficialToggled "使用官方服务"开关回调（开源改造 P0）。
 * @param onSaveOfficial 保存官方服务（地址, 令牌）回调；令牌留空表示沿用已保存的值。
 * @param onSaveChat 保存摘要/标签 Provider（地址, API Key, 模型）回调。
 * @param onChatProtocolSelected 切换 Chat 协议方言（OpenAI 兼容 / Anthropic）回调。
 * @param onSaveEmbed 保存向量 Provider 回调。
 * @param onSaveAsr 保存转写 Provider 回调。
 * @param onReEmbed 批量重建向量回调。
 * @param onLanguageSelected 语言单选回调（P11）；切换只写偏好，是否重建由调用方决定。
 * @param onFeedbackClick 点"意见反馈"回调。
 * @param onExportConfirmed 导出确认回调，参数是"是否包含 AI 派生内容"。
 * @param onCleanupConfirmed 清理确认回调。
 * @param onRetryShare 分享面板没打开时"重新打开"的回调（M16）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onFloatingToggled: (Boolean) -> Unit,
    onGrantPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onAutoClipboardToggled: (Boolean) -> Unit = {},
    onAutoRestoreToggled: (Boolean) -> Unit = {},
    onAlwaysVisibleToggled: (Boolean) -> Unit = {},
    onLongPressRecordToggled: (Boolean) -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onCloudAiToggled: (Boolean) -> Unit = {},
    onUseOfficialToggled: (Boolean) -> Unit = {},
    onSaveOfficial: (String, String) -> Unit = { _, _ -> },
    onSaveChat: (String, String, String) -> Unit = { _, _, _ -> },
    onChatProtocolSelected: (com.zhicun.app.domain.ai.ProviderProtocol) -> Unit = {},
    onSaveEmbed: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveAsr: (String, String, String) -> Unit = { _, _, _ -> },
    onReEmbed: () -> Unit = {},
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onExportConfirmed: (Boolean) -> Unit = {},
    onCleanupConfirmed: () -> Unit = {},
    onRetryShare: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(SettingsTestTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 居中标题 + 箭头图标返回（2026-10-24 U-11）：与详情页同款二级页顶栏
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(SettingsTestTags.BACK),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_back),
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                title = { Text(text = stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 六个分组在 411dp 宽的屏上远超一屏，必须能滚
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle(text = stringResource(R.string.settings_section_floating))
            SettingsGroup {
                FloatingBubbleSection(
                    uiState = uiState,
                    onFloatingToggled = onFloatingToggled,
                    onAutoClipboardToggled = onAutoClipboardToggled,
                    onAutoRestoreToggled = onAutoRestoreToggled,
                    onAlwaysVisibleToggled = onAlwaysVisibleToggled,
                    onLongPressRecordToggled = onLongPressRecordToggled,
                    onGrantPermission = onGrantPermission,
                )
                // 保活自检（2026-10-24）：只在悬浮开着且已读到系统状态时出现。
                // 放在同一张卡片里，是因为它回答的正是"刚打开的这个东西为什么会自己消失"。
                if (uiState.showKeepAlive) {
                    uiState.keepAlive?.let { status ->
                        KeepAliveSection(
                            status = status,
                            autoRestoreOnBoot = uiState.autoRestoreOnBoot,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onOpenNotificationSettings = onOpenNotificationSettings,
                        )
                    }
                }
            }

            SectionTitle(text = stringResource(R.string.settings_section_cloud_ai))
            SettingsGroup {
                CloudAiSection(uiState = uiState, onCloudAiToggled = onCloudAiToggled)
            }

            SectionTitle(text = stringResource(R.string.settings_section_ai_provider))
            SettingsGroup {
                AiProviderSection(
                    uiState = uiState,
                    onUseOfficialToggled = onUseOfficialToggled,
                    onSaveOfficial = onSaveOfficial,
                    onSaveChat = onSaveChat,
                    onChatProtocolSelected = onChatProtocolSelected,
                    onSaveEmbed = onSaveEmbed,
                    onSaveAsr = onSaveAsr,
                    onReEmbed = onReEmbed,
                )
            }

            SectionTitle(text = stringResource(R.string.settings_section_transcription))
            SettingsGroup { TranscriptionSection(uiState = uiState) }

            SectionTitle(text = stringResource(R.string.settings_section_data))
            SettingsGroup {
                DataSection(
                    uiState = uiState,
                    onExportConfirmed = onExportConfirmed,
                    onCleanupConfirmed = onCleanupConfirmed,
                    onRetryShare = onRetryShare,
                )
            }

            SectionTitle(text = stringResource(R.string.settings_section_privacy))
            SettingsGroup { PrivacySection() }

            SectionTitle(text = stringResource(R.string.settings_section_language))
            SettingsGroup {
                LanguageSection(
                    current = uiState.language,
                    onSelect = onLanguageSelected,
                )
            }

            SectionTitle(text = stringResource(R.string.settings_section_about))
            SettingsGroup { AboutSection(uiState = uiState, onFeedbackClick = onFeedbackClick) }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 设置分组卡片（2026-10 改版）：每组收进一张白底圆角卡片，组名退为灰色小标签。
 * 只包视觉容器，组内行与测试 tag 原样保留。
 */
@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/** 从任意被包裹的 Context 里找到宿主 Activity（语言切换要靠它 `recreate()`）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
