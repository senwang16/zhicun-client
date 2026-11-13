package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.AiProviderConfig
import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.ai.Enrichment
import com.zhicun.app.domain.ai.SpeechTranscriber
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.fake.FakeAiProviderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 官方 / 自定义的运行时路由（开源改造 P0）。
 *
 * 钉住的是**选路规则**，不是三种协议本身（那是 `DirectProvidersTest` 的事）：
 * - 勾了官方且官方已配置 → 官方；
 * - 没勾官方，或勾了但官方没配 → 自定义（只要填了）；
 * - 两边都没有 → 官方（让它抛"未配置"，文案才准确）。
 *
 * 为什么这条规则值得单独测：它是"用户的 Key 会不会被用在别的服务上"这条隐私边界的
 * 唯一执行点。写反了不会崩，只会静默把内容发到用户没选的那一家。
 */
class RoutingTest {

    private class CountingContentEnricher(private val tag: String) : ContentEnricher {
        var calls = 0
        override suspend fun enrich(text: String, language: AppLanguage): Enrichment {
            calls++
            return Enrichment(title = tag, summary = null, tags = emptyList())
        }
    }

    private class CountingEmbedder(private val key: String) : Embedder {
        var calls = 0
        var queryCalls = 0
        override val providerKey: String
            get() = key

        override suspend fun embed(text: String): Embedding {
            calls++
            return Embedding(model = "m", vector = floatArrayOf(1f))
        }

        override suspend fun embedQuery(text: String): Embedding {
            queryCalls++
            return Embedding(model = "m", vector = floatArrayOf(1f))
        }
    }

    private class CountingTranscriber(private val tag: String) : SpeechTranscriber {
        var calls = 0
        override suspend fun transcribe(audioPath: String, mimeType: String): String {
            calls++
            return tag
        }
    }

    private fun customConfig(model: String = "gpt-4o-mini") =
        AiProviderConfig("https://api.openai.com/v1", "sk-user", model)

    @Test
    fun OfficialChosen_whenSelectedAndConfigured_evenIfCustomExists() = runBlocking {
        val settings = FakeAiProviderSettings(useOfficial = true, chat = customConfig())
        val official = CountingContentEnricher("official")
        val custom = CountingContentEnricher("custom")
        val router = RoutingContentEnricher(settings, official, custom)

        val result = router.enrich("文本", AppLanguage.ZH)

        assertEquals("official", result.title)
        assertEquals(1, official.calls)
        assertEquals(0, custom.calls)
    }

    @Test
    fun CustomChosen_whenOfficialSwitchIsOff() = runBlocking {
        val settings = FakeAiProviderSettings(useOfficial = false, chat = customConfig())
        val official = CountingContentEnricher("official")
        val custom = CountingContentEnricher("custom")
        val router = RoutingContentEnricher(settings, official, custom)

        assertEquals("custom", router.enrich("文本", AppLanguage.ZH).title)
        assertEquals(0, official.calls)
    }

    @Test
    fun CustomChosen_whenOfficialSelectedButNotConfigured() = runBlocking {
        // 开源编译包的默认状态：勾着官方、但没有地址与令牌
        val settings = FakeAiProviderSettings(
            useOfficial = true,
            officialBase = null,
            officialSec = null,
            chat = customConfig(),
        )
        val official = CountingContentEnricher("official")
        val custom = CountingContentEnricher("custom")
        val router = RoutingContentEnricher(settings, official, custom)

        assertEquals("custom", router.enrich("文本", AppLanguage.ZH).title)
    }

    @Test
    fun OfficialChosen_whenNothingConfigured_soErrorIsAccurate() = runBlocking {
        val settings = FakeAiProviderSettings(useOfficial = false, officialBase = null, officialSec = null)
        val official = CountingContentEnricher("official")
        val custom = CountingContentEnricher("custom")
        val router = RoutingContentEnricher(settings, official, custom)

        router.enrich("文本", AppLanguage.ZH)

        assertEquals("两边都没配必须走官方，让「未配置」的报错语义准确", 1, official.calls)
    }

    @Test
    fun EmbedderRouting_usesSameRule_andExposesMatchingProviderKey() = runBlocking {
        val settings = FakeAiProviderSettings(useOfficial = false, embed = customConfig("BAAI/bge-m3"))
        val official = CountingEmbedder("https://gw.example.com")
        val custom = CountingEmbedder("https://api.openai.com/v1")
        val router = RoutingEmbedder(settings, official, custom)

        assertEquals("https://api.openai.com/v1", router.providerKey)
        router.embed("a")
        router.embedQuery("b")

        assertEquals(1, custom.calls)
        assertEquals(1, custom.queryCalls)
        assertEquals("搜索路也必须走同一条路由", 0, official.queryCalls)

        settings.setUseOfficialService(true)
        assertEquals("切回官方后 providerKey 必须跟着换", "https://gw.example.com", router.providerKey)
    }

    @Test
    fun TranscriberRouting_usesSameRule() = runBlocking {
        val settings = FakeAiProviderSettings(useOfficial = false, asr = customConfig("SenseVoiceSmall"))
        val official = CountingTranscriber("official-text")
        val custom = CountingTranscriber("custom-text")
        val router = RoutingSpeechTranscriber(settings, official, custom)

        assertEquals("custom-text", router.transcribe("/tmp/a.m4a", "audio/mp4"))
        assertEquals(0, official.calls)
    }
}