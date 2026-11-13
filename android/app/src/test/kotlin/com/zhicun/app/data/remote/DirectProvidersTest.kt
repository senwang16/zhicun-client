package com.zhicun.app.data.remote

import com.zhicun.app.data.settings.ClientInstanceId
import com.zhicun.app.domain.ai.AiProviderConfig
import com.zhicun.app.domain.ai.GatewayRejectedException
import com.zhicun.app.domain.ai.GatewayTransientException
import com.zhicun.app.domain.ai.ProviderProtocol
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiProviderSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 三路直连 Provider 的协议层测试（开源改造 P0）。
 *
 * 用 [MockWebServer] 断言**真实发出的请求**，而不是只测解析函数：
 * - 端点路径与鉴权头（直连模式最容易错的两处，错了表现为 404 / 401）；
 * - 请求体形状（Chat 的 `response_format`、ASR 的 multipart 字段名）；
 * - 响应容错（裸 JSON / 围栏 / 前后多余文字 / 空字段）；
 * - 错误映射（配额耗尽不能当普通限流重试）。
 *
 * 不连真实供应商：协议差异是客户端的事，真实连通性属于真机验收项。
 */
@RunWith(RobolectricTestRunner::class)
class DirectProvidersTest {

    private lateinit var server: MockWebServer
    private lateinit var http: AiGatewayHttp

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = AiGatewayHttp(
            client = OkHttpClient(),
            settings = FakeAiGatewaySettings(),
            clientInstanceId = ClientInstanceId(RuntimeEnvironment.getApplication()),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 用户填的 API 地址（带版本号），与设置页的占位文案一致。 */
    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    /** 既有用例都验证中文输出语言；语言相关用例显式传 [AppLanguage.EN] 覆盖。 */
    private suspend fun DirectChatProvider.enrich(text: String): com.zhicun.app.domain.ai.Enrichment =
        enrich(text, AppLanguage.ZH)

    private fun providerSettings(
        chat: Boolean = false,
        embed: Boolean = false,
        asr: Boolean = false,
        chatProtocol: ProviderProtocol = ProviderProtocol.OPENAI,
    ) = FakeAiProviderSettings(
        useOfficial = false,
        chat = if (chat) {
            AiProviderConfig(
                baseUrl = baseUrl(),
                apiKey = "sk-chat",
                model = if (chatProtocol == ProviderProtocol.ANTHROPIC) {
                    "claude-3-5-sonnet-latest"
                } else {
                    "gpt-4o-mini"
                },
                protocol = chatProtocol,
            )
        } else {
            null
        },
        embed = if (embed) AiProviderConfig(baseUrl(), "sk-embed", "BAAI/bge-m3") else null,
        asr = if (asr) AiProviderConfig(baseUrl(), "sk-asr", "SenseVoiceSmall") else null,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    // ------------------------------------------------------------ Chat

    @Test
    fun DirectChat_sendsOpenAiCompatibleRequestAndParsesJson() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"choices":[{"message":{"role":"assistant","content":"{\"title\":\"标题\",\"summary\":\"摘要\",\"tags\":[\"标签A\",\"标签B\"]}"}}]}""",
            ),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        val enrichment = provider.enrich("一段待整理的文字")

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-chat", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue("必须带模型名", body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue("直连模式由客户端要求 JSON mode", body.contains("\"response_format\""))
        assertTrue("max_tokens 不能被当作默认值省略", body.contains("\"max_tokens\":"))
        assertTrue("用户文本必须进 messages", body.contains("一段待整理的文字"))
        assertFalse("直连模式不该发官方网关的实例头", request.headers.values("X-Client-Id").isNotEmpty())

        assertEquals("标题", enrichment.title)
        assertEquals("摘要", enrichment.summary)
        assertEquals(listOf("标签A", "标签B"), enrichment.tags)
    }

    @Test
    fun DirectChat_englishLanguage_appendsEnglishInstructionAndKeepsTextUntouched() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{\"title\":\"T\"}"}}]}"""))
        val provider = DirectChatProvider(http, providerSettings(chat = true))
        val rawText = "一段中文原文，不能被翻译。"

        provider.enrich(rawText, AppLanguage.EN)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("英文模式必须追加英文语言指令", body.contains("Output language: English"))
        assertTrue("中文语言指令不能同时出现（会自相矛盾）", !body.contains("简体中文"))
        assertTrue("原文必须逐字进入 user 消息", body.contains(rawText))
    }

    @Test
    fun DirectChat_chineseLanguage_appendsChineseInstruction() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{\"title\":\"T\"}"}}]}"""))
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        provider.enrich("English text stays as-is.", AppLanguage.ZH)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("中文模式必须要求中文输出", body.contains("输出语言：简体中文"))
        assertTrue(body.contains("English text stays as-is."))
    }

    @Test
    fun DirectChat_toleratesMarkdownFenceAndExtraProse() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"choices":[{"message":{"content":"好的，以下是整理结果：\n```json\n{\"title\":\"T\",\"summary\":\"S\",\"tags\":\"a, b\"}\n```\n希望有用！"}}]}""",
            ),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        val enrichment = provider.enrich("文本")

        assertEquals("T", enrichment.title)
        assertEquals("S", enrichment.summary)
        assertEquals("逗号分隔的标签也要能拆开", listOf("a", "b"), enrichment.tags)
    }

    @Test
    fun DirectChat_missingFields_areNullNotError() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{\"tags\":[]}"}}]}"""))
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        val enrichment = provider.enrich("文本")

        assertNull(enrichment.title)
        assertNull(enrichment.summary)
        assertTrue(enrichment.tags.isEmpty())
    }

    @Test
    fun DirectChat_jsonModeRejected_retriesWithoutResponseFormat() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"invalid_request_error","message":"response_format unsupported"}}"""),
        )
        server.enqueue(
            jsonResponse(
                """{"choices":[{"message":{"content":"{\"title\":\"T\",\"summary\":\"S\",\"tags\":[\"a\"]}"}}]}""",
            ),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        val enrichment = provider.enrich("文本")

        assertEquals("T", enrichment.title)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("第一次应尝试 JSON mode", first.contains("response_format"))
        assertFalse("被拒后应去掉该字段重发", second.contains("response_format"))
    }

    @Test
    fun DirectChat_unparsableResponse_isTransient() = runBlocking {
        repeat(2) {
            server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"我想了很久，但没有给出 JSON。"}}]}"""))
        }
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        try {
            provider.enrich("文本")
            fail("解析不出来应按可重试故障上报")
        } catch (e: GatewayTransientException) {
            assertEquals("失败重发一次后仍不可解析", 2, server.requestCount)
        }
    }

    @Test
    fun DirectChat_quotaExhausted_isRejectedNotRateLimited() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"insufficient_quota"}}"""),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        try {
            provider.enrich("文本")
            fail("配额耗尽必须按终态失败处理")
        } catch (e: GatewayRejectedException) {
            assertEquals("insufficient_quota", e.code)
        }
    }

    // ------------------------------------------------ 官方免费试用额度（2026-11-13）

    @Test
    fun OfficialTrialExhausted402_isRejectedWithOwnKeyHint() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(402)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":"TRIAL_EXHAUSTED","message":"免费试用次数已用完"}}""",
                ),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        try {
            provider.enrich("文本")
            fail("试用额度用完是终态，不该当普通失败")
        } catch (e: GatewayRejectedException) {
            assertEquals("TRIAL_EXHAUSTED", e.code)
            assertTrue(
                "文案必须给出「填自己的 Key」这条出路，实际：${e.message}",
                e.message.orEmpty().contains("自己的模型 Key"),
            )
        }
    }

    @Test
    fun OfficialDailyQuota429_isRejectedNotRetriedAsRateLimit() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":"DAILY_QUOTA_EXCEEDED","message":"今日免费额度已用完"}}""",
                ),
        )
        val provider = DirectChatProvider(http, providerSettings(chat = true))

        try {
            provider.enrich("文本")
            fail("每日额度用完当天重试没有意义，必须按终态处理")
        } catch (e: GatewayRejectedException) {
            assertEquals("DAILY_QUOTA_EXCEEDED", e.code)
            assertTrue(e.message.orEmpty().contains("自己的模型 Key"))
        }
        assertEquals("不该触发一次重发", 1, server.requestCount)
    }

    // ------------------------------------------------------------ Anthropic Messages

    @Test
    fun AnthropicChat_sendsMessagesProtocolWithOwnHeaders() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"content":[{"type":"text","text":"{\"title\":\"标题\",\"summary\":\"摘要\",\"tags\":[\"a\"]}"}]}""",
            ),
        )
        val provider = DirectChatProvider(
            http,
            providerSettings(chat = true, chatProtocol = ProviderProtocol.ANTHROPIC),
        )

        val enrichment = provider.enrich("一段文字")

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-chat", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertNull("Anthropic 不用 Bearer", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue("system 必须是顶层字段", body.contains("\"system\""))
        assertTrue("max_tokens 必填", body.contains("\"max_tokens\""))
        assertTrue("用户文本进 messages", body.contains("一段文字"))
        assertFalse("Anthropic 没有 response_format", body.contains("response_format"))

        assertEquals("标题", enrichment.title)
        assertEquals(listOf("a"), enrichment.tags)
    }

    @Test
    fun AnthropicChat_concatenatesTextBlocks_andIgnoresThinking() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"content":[{"type":"thinking","thinking":"先想一想"},{"type":"text","text":"{\"title\":\"T\""},{"type":"text","text":",\"summary\":\"S\"}"}]}""",
            ),
        )
        val provider = DirectChatProvider(
            http,
            providerSettings(chat = true, chatProtocol = ProviderProtocol.ANTHROPIC),
        )

        val enrichment = provider.enrich("文本")

        assertEquals("T", enrichment.title)
        assertEquals("S", enrichment.summary)
    }

    @Test
    fun AnthropicChat_errorEnvelope_isMappedToRejected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                ),
        )
        val provider = DirectChatProvider(
            http,
            providerSettings(chat = true, chatProtocol = ProviderProtocol.ANTHROPIC),
        )

        try {
            provider.enrich("文本")
            fail("401 必须按终态失败处理")
        } catch (e: GatewayRejectedException) {
            assertEquals("authentication_error", e.code)
        }
        assertEquals("鉴权失败不该重发，避免白计费", 1, server.requestCount)
    }

    @Test
    fun AnthropicChat_doesNotFallBackToJsonModeOnRejection() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""),
        )
        val provider = DirectChatProvider(
            http,
            providerSettings(chat = true, chatProtocol = ProviderProtocol.ANTHROPIC),
        )

        try {
            provider.enrich("文本")
            fail("Anthropic 没有 JSON mode 回退，应直接失败")
        } catch (e: GatewayRejectedException) {
            assertEquals("invalid_request_error", e.code)
        }
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------ Embedding

    @Test
    fun DirectEmbed_sendsStandardRequestAndParsesVector() = runBlocking {
        server.enqueue(
            jsonResponse("""{"data":[{"embedding":[0.5,-0.25],"index":0}],"model":"BAAI/bge-m3"}"""),
        )
        val provider = DirectEmbedProvider(http, providerSettings(embed = true))

        val embedding = provider.embed("一段文字")

        val request = server.takeRequest()
        assertEquals("/v1/embeddings", request.path)
        assertEquals("Bearer sk-embed", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"model\":\"BAAI/bge-m3\""))
        assertEquals("BAAI/bge-m3", embedding.model)
        assertEquals(2, embedding.dimensions)
    }

    @Test
    fun DirectEmbed_emptyData_isEmptyVectorNotError() = runBlocking {
        // 不回显 model：走"用配置里的模型名兜底"这条分支
        server.enqueue(jsonResponse("""{"data":[]}"""))
        val provider = DirectEmbedProvider(http, providerSettings(embed = true))

        val embedding = provider.embed("纯符号文本！！！")

        assertTrue("空向量是正常业务结果，不该抛异常", embedding.isEmpty)
        assertEquals("未回显时用配置里的模型名兜底", "BAAI/bge-m3", embedding.model)
    }

    @Test
    fun DirectEmbed_echoedModel_wins() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"embedding":[0.5]}],"model":"vendor-actual-model"}"""))
        val provider = DirectEmbedProvider(http, providerSettings(embed = true))

        val embedding = provider.embed("文字")

        assertEquals(
            "供应商回显的模型名优先：它决定这批向量归哪一组",
            "vendor-actual-model",
            embedding.model,
        )
    }

    @Test
    fun DirectEmbed_providerKey_isProviderBaseUrl() {
        val settings = providerSettings(embed = true)
        assertEquals(baseUrl(), DirectEmbedProvider(http, settings).providerKey)
    }

    // ------------------------------------------------------------ ASR

    @Test
    fun DirectAsr_sendsMultipartWithFileAndModel() = runBlocking {
        val audio = File.createTempFile("zhicun-asr", ".m4a").apply {
            writeBytes(ByteArray(64) { 1 })
            deleteOnExit()
        }
        server.enqueue(jsonResponse("""{"text":"转写结果"}"""))
        val provider = DirectAsrProvider(http, providerSettings(asr = true))

        val text = provider.transcribe(audio.absolutePath, "audio/mp4")

        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer sk-asr", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue("必须是 multipart（不能沿用官方网关的裸字节流）", contentType.startsWith("multipart/form-data"))
        assertTrue("multipart 必须带 boundary", contentType.contains("boundary="))

        val body = request.body.readUtf8()
        assertTrue("文件字段名必须是 file", body.contains("name=\"file\""))
        assertTrue("必须带原始文件名", body.contains(audio.name))
        assertTrue("模型字段必须是 model", body.contains("name=\"model\""))
        assertTrue(body.contains("SenseVoiceSmall"))
        assertEquals("转写结果", text)
    }

    @Test
    fun DirectAsr_missingAudio_isRejectedWithoutNetwork() = runBlocking {
        val provider = DirectAsrProvider(http, providerSettings(asr = true))

        try {
            provider.transcribe("/不存在/audio.m4a", "audio/mp4")
            fail("文件不存在是确定性失败")
        } catch (e: GatewayRejectedException) {
            assertEquals("AUDIO_MISSING", e.code)
        }
        assertEquals("本地能判断的失败不要发请求", 0, server.requestCount)
    }
}