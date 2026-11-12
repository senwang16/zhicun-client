package com.zhicun.app.fake

import com.zhicun.app.domain.ai.AiProviderConfig
import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.ProviderProtocol

/**
 * 内存版 Provider 配置（开源改造 P0）。
 *
 * 默认"官方已配置且勾选官方"，与改造前的 `FakeAiGatewaySettings` 行为一致：
 * 绝大多数用例只关心业务行为，不想为每条都填三组地址与密钥。
 *
 * 保存方法同时记录调用参数，便于断言"界面保存的值真的落到了配置层"。
 */
class FakeAiProviderSettings(
    var officialBase: String? = "https://gateway.test",
    var officialSec: String? = "test-token",
    var useOfficial: Boolean = true,
    var chat: AiProviderConfig? = null,
    var embed: AiProviderConfig? = null,
    var asr: AiProviderConfig? = null,
) : AiProviderSettings {

    /** Chat 协议方言；与 `chat` 分开存，模拟"先选协议、后填地址"的顺序。 */
    var chatProtocolValue: ProviderProtocol = ProviderProtocol.OPENAI

    /** 保存调用记录：(baseUrl, apiKey, model)；apiKey 为 null 表示"保留原值"。 */
    val chatWrites: MutableList<Triple<String?, String?, String?>> = mutableListOf()
    val embedWrites: MutableList<Triple<String?, String?, String?>> = mutableListOf()
    val asrWrites: MutableList<Triple<String?, String?, String?>> = mutableListOf()
    val officialWrites: MutableList<Pair<String?, String?>> = mutableListOf()

    override val officialBaseUrl: String?
        get() = officialBase

    override val officialToken: String?
        get() = officialSec

    override val officialConfigured: Boolean
        get() = officialBase != null && officialSec != null

    override val useOfficialService: Boolean
        get() = useOfficial

    override fun setUseOfficialService(value: Boolean) {
        useOfficial = value
    }

    override fun saveOfficial(baseUrl: String?, token: String?) {
        officialWrites += baseUrl to token
        officialBase = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        officialSec = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    override val chatConfig: AiProviderConfig?
        get() = chat

    override val chatProtocol: ProviderProtocol
        get() = chatProtocolValue

    override fun saveChatProtocol(protocol: ProviderProtocol) {
        chatProtocolValue = protocol
    }

    override fun saveChat(baseUrl: String?, apiKey: String?, model: String?) {
        chatWrites += Triple(baseUrl, apiKey, model)
        chat = configOf(baseUrl, apiKey, model, previous = chat, protocol = chatProtocolValue)
    }

    override val chatAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || chat != null

    override val embedConfig: AiProviderConfig?
        get() = embed

    override fun saveEmbed(baseUrl: String?, apiKey: String?, model: String?) {
        embedWrites += Triple(baseUrl, apiKey, model)
        embed = configOf(baseUrl, apiKey, model, previous = embed)
    }

    override val embedAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || embed != null

    override val asrConfig: AiProviderConfig?
        get() = asr

    override fun saveAsr(baseUrl: String?, apiKey: String?, model: String?) {
        asrWrites += Triple(baseUrl, apiKey, model)
        asr = configOf(baseUrl, apiKey, model, previous = asr)
    }

    override val asrAvailable: Boolean
        get() = (useOfficialService && officialConfigured) || asr != null

    override val anyProviderAvailable: Boolean
        get() = chatAvailable || embedAvailable || asrAvailable

    private fun configOf(
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        previous: AiProviderConfig?,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI,
    ): AiProviderConfig? {
        val url = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val name = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = when {
            apiKey == null -> previous?.apiKey.orEmpty()
            apiKey.isBlank() -> ""
            else -> apiKey.trim()
        }
        return AiProviderConfig(baseUrl = url, apiKey = key, model = name, protocol = protocol)
    }
}