package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.zhicun.app.BuildConfig
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.AiProviderConfig
import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.ProviderMode
import com.zhicun.app.domain.ai.ProviderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiProviderSettings] 的 SharedPreferences 实现（开源改造 P0）。
 *
 * ## 存储布局：沿用旧文件 + 新增一个文件
 * ```
 * "ai_gateway"（**沿用改造前的文件名，老用户配置无需搬迁**）
 *   base_url        = "https://..."     官方网关地址
 *   token           = "enc1:..."        官方访问令牌（KeyStore 加密）
 *   use_official    = true/false        用户是否选择官方服务
 *
 * "ai_provider"（新增，三组自定义配置）
 *   chat_base_url / chat_api_key / chat_model
 *   embed_base_url / embed_api_key / embed_model
 *   asr_base_url / asr_api_key / asr_model
 * ```
 *
 * 旧配置迁移因此**不需要写任何代码**：`ai_gateway` 里的地址与令牌原样就是官方模式配置，
 * `use_official` 默认 true，升级后行为与升级前完全一致。这是刻意选择的兼容形态 ——
 * 迁移代码只在"装过旧版本又升级"的设备上跑，能少一段就少一段真机风险。
 *
 * ## 密钥：一律 KeyStore 加密，且不提供读回明文的接口
 * 三组 `*_api_key` 与官方 `token` 走同一套 [TokenCipher]（AES/GCM + 前缀 `enc1:`）。
 * 读到历史明文时一次性加密重写；加密/解密失败一律按"未配置"降级，**绝不回退明文**
 * ——否则 Keystore 坏掉的设备上会出现"密钥悄悄以明文存活"。
 *
 * `AiProviderSettings` 只暴露"是否已配置"，没有 `chatApiKey` 这样的 getter：
 * 设置页保存后不回填，用户要改就重新输入。少一条"把明文密钥送到 UI 层"的路径。
 */
@Singleton
class PreferencesAiProviderSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenCipher: TokenCipher,
) : AiProviderSettings, AiGatewaySettings {

    private val officialPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_OFFICIAL, Context.MODE_PRIVATE)

    private val providerPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_PROVIDER, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 官方服务（模式 A）

    override val officialBaseUrl: String?
        get() = normalized(officialPrefs.getString(KEY_BASE_URL, null))
            ?: normalized(BuildConfig.AI_GATEWAY_BASE_URL)

    override val officialToken: String?
        get() = readSecret(officialPrefs, KEY_TOKEN)
            ?: normalized(BuildConfig.AI_GATEWAY_TOKEN)

    override val officialConfigured: Boolean
        get() = officialBaseUrl != null && officialToken != null

    override val useOfficialService: Boolean
        get() = officialPrefs.getBoolean(KEY_USE_OFFICIAL, DEFAULT_USE_OFFICIAL)

    override fun setUseOfficialService(value: Boolean) {
        officialPrefs.edit { putBoolean(KEY_USE_OFFICIAL, value) }
    }

    override fun saveOfficial(baseUrl: String?, token: String?) {
        officialPrefs.edit {
            putString(KEY_BASE_URL, normalized(baseUrl))
            // 这里 null 表示"清除"：官方令牌是必填项，留空无法工作。
            // 设置页在用户没重新输入时传的是**原值**而不是 null（见 SettingsViewModel）。
            putString(KEY_TOKEN, token?.let { encryptOrNull(it) })
        }
    }

    // ------------------------------------------------------------ 自定义 Provider（模式 B）

    override val chatConfig: AiProviderConfig?
        get() = readProviderConfig(PREFIX_CHAT)

    override val chatProtocol: ProviderProtocol
        get() = readChatProtocol()

    override fun saveChatProtocol(protocol: ProviderProtocol) {
        providerPrefs.edit { putString(KEY_CHAT_PROTOCOL, protocol.name) }
    }

    override fun saveChat(baseUrl: String?, apiKey: String?, model: String?) {
        saveProviderConfig(PREFIX_CHAT, baseUrl, apiKey, model)
    }

    override val chatAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || chatConfig != null

    override val embedConfig: AiProviderConfig?
        get() = readProviderConfig(PREFIX_EMBED)

    override fun saveEmbed(baseUrl: String?, apiKey: String?, model: String?) {
        saveProviderConfig(PREFIX_EMBED, baseUrl, apiKey, model)
    }

    override val embedAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || embedConfig != null

    override val asrConfig: AiProviderConfig?
        get() = readProviderConfig(PREFIX_ASR)

    override fun saveAsr(baseUrl: String?, apiKey: String?, model: String?) {
        saveProviderConfig(PREFIX_ASR, baseUrl, apiKey, model)
    }

    override val asrAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || asrConfig != null

    override val anyProviderAvailable: Boolean
        get() = chatAvailable || embedAvailable || asrAvailable

    // ------------------------------------------------------------ AiGatewaySettings（旧接口视图）

    /**
     * 旧接口的官方地址视图。
     *
     * 只该被官方协议实现（`AiGatewayHttp`、三个 `Remote*` 实现）读取；
     * 路由侧决定"走官方"之前已经确认过 [officialConfigured]。
     */
    override val baseUrl: String?
        get() = officialBaseUrl

    override val token: String?
        get() = officialToken

    /**
     * 旧接口的"云端通路可用"视图。
     *
     * **语义比改造前变宽了**：原来只回答"官方网关配了没有"，现在回答
     * "三种能力里有没有一种此刻能发出去"。这是必要的 —— 自定义模式没有官方网关，
     * 若仍按旧语义返回 false，`CloudUploadGate` 会把已配好的自定义能力一并挡掉。
     *
     * 代价是它不再能回答"这条任务具体能不能跑"：能力级判断由各 Provider 在真正
     * 发请求时用 [GatewayNotConfiguredException] 表达（终态、文案准确）。
     */
    override val isConfigured: Boolean
        get() = anyProviderAvailable

    override fun save(baseUrl: String?, token: String?) {
        saveOfficial(baseUrl, token)
    }

    // ------------------------------------------------------------ 内部实现

    private fun readProviderConfig(prefix: String): AiProviderConfig? {
        val baseUrl = normalized(providerPrefs.getString(prefix + KEY_SUFFIX_BASE_URL, null))
            ?: return null
        val model = providerPrefs.getString(prefix + KEY_SUFFIX_MODEL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        // 没有 Key 也允许：本地 Ollama / vLLM 通常不校验（见 AiProviderConfig 注释）
        val apiKey = readSecret(providerPrefs, prefix + KEY_SUFFIX_API_KEY).orEmpty()
        return AiProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            mode = ProviderMode.CUSTOM,
            // 协议只对 Chat 有意义：Embedding / ASR 没有 Anthropic 方言
            protocol = if (prefix == PREFIX_CHAT) readChatProtocol() else ProviderProtocol.OPENAI,
        )
    }

    /** 未写入过 / 写入值无法识别 → OpenAI 兼容（老配置与脏数据都不该炸，也不该猜成 Anthropic）。 */
    private fun readChatProtocol(): ProviderProtocol =
        when (providerPrefs.getString(KEY_CHAT_PROTOCOL, null)) {
            ProviderProtocol.ANTHROPIC.name -> ProviderProtocol.ANTHROPIC
            else -> ProviderProtocol.OPENAI
        }

    /**
     * 保存一组自定义配置。
     *
     * `apiKey == null` 表示**保留原密钥**：用户改模型名时不必重新粘贴 Key
     * （Key 从不回填到输入框，若这里把 null 当成"清除"，改个模型就把密钥弄丢了）。
     */
    private fun saveProviderConfig(prefix: String, baseUrl: String?, apiKey: String?, model: String?) {
        val storedKey = when {
            apiKey == null -> providerPrefs.getString(prefix + KEY_SUFFIX_API_KEY, null)
            apiKey.isBlank() -> null
            else -> encryptOrNull(apiKey)
        }
        providerPrefs.edit {
            putString(prefix + KEY_SUFFIX_BASE_URL, normalized(baseUrl))
            putString(
                prefix + KEY_SUFFIX_MODEL,
                model?.trim()?.takeIf { it.isNotEmpty() },
            )
            putString(prefix + KEY_SUFFIX_API_KEY, storedKey)
        }
    }

    /**
     * 读一个加密字段。
     *
     * - 带 `enc1:` 前缀：解密，失败按"未配置"降级（绝不回退明文）；
     * - 历史明文：一次性加密重写后再返回（M9–M17 期间的存量格式）。
     */
    private fun readSecret(prefs: SharedPreferences, key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (tokenCipher.isEncrypted(stored)) {
            return normalized(safeDecrypt(stored))
        }
        val migrated = try {
            tokenCipher.encrypt(stored)
        } catch (e: Exception) {
            Log.w(TAG, "密钥迁移加密失败，按未配置处理：${e.javaClass.simpleName}")
            return null
        }
        prefs.edit { putString(key, migrated) }
        return normalized(stored)
    }

    private fun safeDecrypt(value: String): String? = try {
        tokenCipher.decrypt(value)
    } catch (e: Exception) {
        Log.w(TAG, "密钥解密失败，按未配置处理：${e.javaClass.simpleName}")
        null
    }

    private fun encryptOrNull(value: String): String? = try {
        tokenCipher.encrypt(value.trim())
    } catch (e: Exception) {
        Log.w(TAG, "密钥加密失败，按未配置处理：${e.javaClass.simpleName}")
        null
    }

    /** 去首尾空白与尾部斜杠：`https://gw/` 与 `https://gw` 不该拼出 `//api/v1`。 */
    private fun normalized(value: String?): String? =
        value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "AiProviderSettings"
        const val PREFS_OFFICIAL = "ai_gateway"
        const val PREFS_PROVIDER = "ai_provider"

        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_USE_OFFICIAL = "use_official"

        /** 缺省勾选官方服务：升级用户与"只填了 BuildConfig 网关"的包行为不变。 */
        const val DEFAULT_USE_OFFICIAL = true

        const val PREFIX_CHAT = "chat_"
        const val PREFIX_EMBED = "embed_"
        const val PREFIX_ASR = "asr_"

        const val KEY_SUFFIX_BASE_URL = "base_url"
        const val KEY_SUFFIX_API_KEY = "api_key"
        const val KEY_SUFFIX_MODEL = "model"

        /** Chat 协议方言（`OPENAI` / `ANTHROPIC`）；缺失按 OpenAI 处理。 */
        const val KEY_CHAT_PROTOCOL = "chat_protocol"
    }
}