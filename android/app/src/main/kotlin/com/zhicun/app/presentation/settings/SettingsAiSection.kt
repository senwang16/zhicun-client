package com.zhicun.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.ai.ProviderProtocol

/**
 * 「AI 服务配置」分组（开源改造 P0）。
 *
 * 结构（与《技术落地方案》§6.1 对齐）：
 * ```
 * 使用官方服务  [Switch]
 * └─ 开：官方地址 + 访问令牌 + 保存
 * └─ 关：摘要与标签 / 向量 / 转写  三张独立卡片，各自有地址、Key、模型与保存按钮
 *        向量卡片下方：向量状态 + 重新向量化
 * ```
 *
 * ## 三条 UX 纪律
 * 1. **Key 永不回填**：输入框初始为空，占位文案说明"留空表示不修改"。
 *    状态里也没有 Key（见 `SettingsUiState`），保存后立刻清空输入框 ——
 *    少一条把明文密钥留在界面上的路径，截图与输入法缓存都拿不到它。
 * 2. **每组独立保存**：不做"输入即保存"。用户输到一半就触发请求（或半截 URL 被写进配置）
 *    是这类设置页最常见的挫败点。
 * 3. **保存后重新加载**：卡片里的文本以服务端状态为初值，保存成功后状态变化会重置输入框，
 *    用户看到的就是"已经存进去了"。
 */
@Composable
internal fun AiProviderSection(
    uiState: SettingsUiState,
    onUseOfficialToggled: (Boolean) -> Unit,
    onSaveOfficial: (baseUrl: String, token: String) -> Unit,
    onSaveChat: (baseUrl: String, apiKey: String, model: String) -> Unit,
    onChatProtocolSelected: (ProviderProtocol) -> Unit,
    onSaveEmbed: (baseUrl: String, apiKey: String, model: String) -> Unit,
    onSaveAsr: (baseUrl: String, apiKey: String, model: String) -> Unit,
    onReEmbed: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ai_official_switch),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_ai_official_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = uiState.useOfficialService,
            onCheckedChange = onUseOfficialToggled,
            modifier = Modifier.testTag(SettingsTestTags.AI_OFFICIAL_SWITCH),
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

    if (uiState.useOfficialService) {
        OfficialServiceCard(uiState = uiState, onSave = onSaveOfficial)
    } else {
        Text(
            text = stringResource(R.string.settings_ai_custom_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderConfigCard(
            title = stringResource(R.string.settings_ai_chat_title),
            hint = stringResource(R.string.settings_ai_chat_hint),
            baseUrl = uiState.chatBaseUrl,
            model = uiState.chatModel,
            modelPlaceholder = stringResource(R.string.settings_ai_model_placeholder_chat),
            configured = uiState.chatConfigured,
            tagPrefix = SettingsTestTags.AI_CHAT_PREFIX,
            onSave = onSaveChat,
            extraContent = {
                ChatProtocolSelector(
                    protocol = uiState.chatProtocol,
                    onSelected = onChatProtocolSelected,
                )
            },
        )
        ProviderConfigCard(
            title = stringResource(R.string.settings_ai_embed_title),
            hint = stringResource(R.string.settings_ai_embed_hint),
            baseUrl = uiState.embedBaseUrl,
            model = uiState.embedModel,
            modelPlaceholder = stringResource(R.string.settings_ai_model_placeholder_embed),
            configured = uiState.embedConfigured,
            tagPrefix = SettingsTestTags.AI_EMBED_PREFIX,
            onSave = onSaveEmbed,
        )
        ProviderConfigCard(
            title = stringResource(R.string.settings_ai_asr_title),
            hint = stringResource(R.string.settings_ai_asr_hint),
            baseUrl = uiState.asrBaseUrl,
            model = uiState.asrModel,
            modelPlaceholder = stringResource(R.string.settings_ai_model_placeholder_asr),
            configured = uiState.asrConfigured,
            tagPrefix = SettingsTestTags.AI_ASR_PREFIX,
            onSave = onSaveAsr,
        )
    }

    // 向量状态：官方与自定义模式都要显示 —— 换模型这件事两种模式都会发生
    if (uiState.embedConfigured) {
        VectorStatusBlock(uiState = uiState, onReEmbed = onReEmbed)
    }

    uiState.aiNotice?.let { notice ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = aiNoticeText(notice),
            style = MaterialTheme.typography.labelMedium,
            color = if (notice is AiNotice.ReEmbedFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.testTag(SettingsTestTags.AI_NOTICE),
        )
    }
}

/** AI 区的操作结论 → 一句人话。带数字的一定把数字带上。 */
@Composable
private fun aiNoticeText(notice: AiNotice): String = when (notice) {
    is AiNotice.ReEmbedSubmitted -> stringResource(
        R.string.settings_ai_reembed_submitted,
        notice.count,
    )

    AiNotice.ReEmbedNothing -> stringResource(R.string.settings_ai_reembed_nothing)
    AiNotice.ReEmbedFailed -> stringResource(R.string.settings_ai_reembed_failed)
}

/** 官方服务配置（模式 A）。令牌输入框始终为空，保存时留空 = 沿用原令牌。 */
@Composable
private fun OfficialServiceCard(
    uiState: SettingsUiState,
    onSave: (baseUrl: String, token: String) -> Unit,
) {
    var baseUrl by rememberSaveable(uiState.officialBaseUrl) {
        mutableStateOf(uiState.officialBaseUrl)
    }
    var token by rememberSaveable { mutableStateOf("") }

    Text(
        text = stringResource(
            if (uiState.officialConfigured) {
                R.string.settings_ai_official_configured
            } else {
                R.string.settings_ai_official_unconfigured
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = if (uiState.officialConfigured) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.testTag(SettingsTestTags.AI_OFFICIAL_STATUS),
    )

    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text(text = stringResource(R.string.settings_ai_official_base_url)) },
        placeholder = {
            Text(text = stringResource(R.string.settings_ai_official_base_url_placeholder))
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(SettingsTestTags.AI_OFFICIAL_BASE_URL),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(text = stringResource(R.string.settings_ai_official_token)) },
        placeholder = {
            Text(text = stringResource(R.string.settings_ai_official_token_placeholder))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(SettingsTestTags.AI_OFFICIAL_TOKEN),
    )
    TextButton(
        onClick = {
            onSave(baseUrl, token)
            // 保存后清空明文：界面不该继续持有一份已经落库的密钥
            token = ""
        },
        modifier = Modifier.testTag(SettingsTestTags.AI_OFFICIAL_SAVE),
    ) {
        Text(text = stringResource(R.string.settings_ai_official_save))
    }
}

/**
 * 一组自定义 Provider 的卡片。
 *
 * @param baseUrl/model 服务端当前值，作为输入框初值；保存成功后由状态变化触发重置
 * @param configured "此刻能不能真的发出去"（含"勾官方且官方已配置"的情况）
 * @param tagPrefix 测试 tag 前缀（`Chat` / `Embed` / `Asr` 三张卡片用同一套布局）
 */
@Composable
private fun ProviderConfigCard(
    title: String,
    hint: String,
    baseUrl: String,
    model: String,
    modelPlaceholder: String,
    configured: Boolean,
    tagPrefix: String,
    onSave: (baseUrl: String, apiKey: String, model: String) -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
) {
    var urlInput by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var modelInput by rememberSaveable(model) { mutableStateOf(model) }

    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (configured) {
                    R.string.settings_ai_status_configured
                } else {
                    R.string.settings_ai_status_unconfigured
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (configured) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.testTag("$tagPrefix${SettingsTestTags.AI_STATUS_SUFFIX}"),
        )
    }

    extraContent?.invoke()

    OutlinedTextField(
        value = urlInput,
        onValueChange = { urlInput = it },
        label = { Text(text = stringResource(R.string.settings_ai_base_url)) },
        placeholder = { Text(text = stringResource(R.string.settings_ai_base_url_placeholder)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("$tagPrefix${SettingsTestTags.AI_BASE_URL_SUFFIX}"),
    )
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text(text = stringResource(R.string.settings_ai_api_key)) },
        placeholder = { Text(text = stringResource(R.string.settings_ai_api_key_placeholder)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("$tagPrefix${SettingsTestTags.AI_API_KEY_SUFFIX}"),
    )
    OutlinedTextField(
        value = modelInput,
        onValueChange = { modelInput = it },
        label = { Text(text = stringResource(R.string.settings_ai_model)) },
        placeholder = { Text(text = modelPlaceholder) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("$tagPrefix${SettingsTestTags.AI_MODEL_SUFFIX}"),
    )
    TextButton(
        onClick = {
            onSave(urlInput, keyInput, modelInput)
            keyInput = ""
        },
        modifier = Modifier.testTag("$tagPrefix${SettingsTestTags.AI_SAVE_SUFFIX}"),
    ) {
        Text(text = stringResource(R.string.settings_ai_save))
    }
}

/**
 * Chat 协议选择（OpenAI 兼容 / Anthropic Messages）。
 *
 * 只有 Chat 需要它：Anthropic 不提供 embeddings / ASR 接口，另外两组固定 OpenAI 兼容。
 * 选中即落盘（见 `SettingsViewModel.onChatProtocolSelected`），与"保存"按钮分开 ——
 * 用户通常先选协议，再填地址与模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatProtocolSelector(
    protocol: ProviderProtocol,
    onSelected: (ProviderProtocol) -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_ai_protocol),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = protocol == ProviderProtocol.OPENAI,
            onClick = { onSelected(ProviderProtocol.OPENAI) },
            label = { Text(text = stringResource(R.string.settings_ai_protocol_openai)) },
            modifier = Modifier.testTag(SettingsTestTags.AI_CHAT_PROTOCOL_OPENAI),
        )
        FilterChip(
            selected = protocol == ProviderProtocol.ANTHROPIC,
            onClick = { onSelected(ProviderProtocol.ANTHROPIC) },
            label = { Text(text = stringResource(R.string.settings_ai_protocol_anthropic)) },
            modifier = Modifier.testTag(SettingsTestTags.AI_CHAT_PROTOCOL_ANTHROPIC),
        )
    }
    Text(
        text = stringResource(R.string.settings_ai_protocol_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 向量状态 + 重建入口（开源改造 P1）。
 *
 * "正在统计…"与"全部使用当前模型"必须分开：统计还没跑完时显示 0 会被读成
 * "一切正常"，而此刻可能正有几十条记录进不了语义检索。
 */
@Composable
private fun VectorStatusBlock(
    uiState: SettingsUiState,
    onReEmbed: () -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Text(
        text = stringResource(R.string.settings_ai_vector_title),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = when {
            !uiState.vectorStatusLoaded -> stringResource(R.string.settings_ai_vector_loading)
            uiState.incompatibleEmbeddingCount > 0 -> stringResource(
                R.string.settings_ai_vector_pending,
                uiState.incompatibleEmbeddingCount,
            )

            else -> stringResource(R.string.settings_ai_vector_ok)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (uiState.incompatibleEmbeddingCount > 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag(SettingsTestTags.AI_VECTOR_STATUS),
    )

    if (uiState.incompatibleEmbeddingCount > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_ai_vector_reembed_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onReEmbed,
            enabled = !uiState.busy,
            modifier = Modifier.testTag(SettingsTestTags.AI_VECTOR_REEMBED),
        ) {
            Text(text = stringResource(R.string.settings_ai_vector_reembed))
        }
    }
}