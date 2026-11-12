package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.ai.Enrichment
import com.zhicun.app.domain.ai.SpeechTranscriber

/**
 * 官方 / 自定义的运行时路由（开源改造 P0）。
 *
 * ## 为什么用包装器而不是 Hilt 条件绑定
 * Hilt 的作用域是编译期固定的，而用户可以在设置页**运行时**在三组配置之间来回切。
 * 条件绑定做不到"这次请求走官方、下次走自定义"，包装器可以 —— 每次调用现读配置。
 *
 * ## 统一的选路规则（三种能力共用）
 * ```
 * 勾了官方 且 官方已配置        → 官方
 * 否则 该能力的自定义配置齐备    → 自定义
 * 否则                          → 官方（必然抛"未配置"，文案准确）
 * ```
 * 第三条是刻意的：**不做"悄悄换一家"**。用户勾了官方却看到自定义 Provider 的输出，
 * 意味着应用用另一个 Key 替他做了一次他没同意的外发 —— 这种事不能"为了不报错"而发生。
 *
 * ## 可用性与选路必须同源
 * `AiProviderSettings.chatAvailable` 等属性用的就是这里同一份规则；
 * 两处若各写一份，界面会显示"可用"而请求走不到（或反之），是这类改造最常见的坑。
 */
class RoutingContentEnricher(
    private val settings: AiProviderSettings,
    private val official: ContentEnricher,
    private val custom: ContentEnricher,
) : ContentEnricher {

    override suspend fun enrich(text: String): Enrichment =
        if (useOfficial()) official.enrich(text) else custom.enrich(text)

    private fun useOfficial(): Boolean =
        (settings.useOfficialService && settings.officialConfigured) ||
            settings.chatConfig == null
}

/**
 * 向量化的运行时路由。
 *
 * [providerKey] 必须跟着实际选中的实现走：它决定"库里哪些向量能和这次查询比较"，
 * 路由选官方却报自定义的供应商地址，会让语义检索把两批不可比的向量混在一起算余弦。
 */
class RoutingEmbedder(
    private val settings: AiProviderSettings,
    private val official: Embedder,
    private val custom: Embedder,
) : Embedder {

    override val providerKey: String
        get() = if (useOfficial()) official.providerKey else custom.providerKey

    override suspend fun embed(text: String): Embedding =
        if (useOfficial()) official.embed(text) else custom.embed(text)

    override suspend fun embedQuery(text: String): Embedding =
        if (useOfficial()) official.embedQuery(text) else custom.embedQuery(text)

    private fun useOfficial(): Boolean =
        (settings.useOfficialService && settings.officialConfigured) ||
            settings.embedConfig == null
}

/** 语音转写的运行时路由，规则与上面两个一致。 */
class RoutingSpeechTranscriber(
    private val settings: AiProviderSettings,
    private val official: SpeechTranscriber,
    private val custom: SpeechTranscriber,
) : SpeechTranscriber {

    override suspend fun transcribe(audioPath: String, mimeType: String): String =
        if (useOfficial()) {
            official.transcribe(audioPath, mimeType)
        } else {
            custom.transcribe(audioPath, mimeType)
        }

    private fun useOfficial(): Boolean =
        (settings.useOfficialService && settings.officialConfigured) ||
            settings.asrConfig == null
}