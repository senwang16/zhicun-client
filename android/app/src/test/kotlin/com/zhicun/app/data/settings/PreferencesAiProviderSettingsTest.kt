package com.zhicun.app.data.settings

import android.content.Context
import com.zhicun.app.BuildConfig
import com.zhicun.app.domain.ai.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 三组 Provider 配置与密钥存储（开源改造 P0）。
 *
 * 钉住五件事：
 * 1. 官方配置沿用 `ai_gateway` 文件（老用户升级后行为不变）；
 * 2. 官方 token 与三组 apiKey 都**加密落盘**，明文不出现；
 * 3. 历史明文一次性迁移；
 * 4. 解密失败按"未配置"降级，绝不回退明文；
 * 5. 三组配置互不干扰、可用性按"官方已配置 + 用户选择"正确推导。
 *
 * 用异或假 cipher 驱动：真 Keystore 在 JVM 上没有 Provider（见附录 C 真机验证项），
 * 但"加密落盘 / 解密失败降级"这些分支是纯逻辑，值得在单测里穷举。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesAiProviderSettingsTest {

    /** 异或假实现：前缀格式与真实现一致，可逆、确定性，KeyStore 不参与。 */
    private class XorTokenCipher : TokenCipher {
        override fun encrypt(plain: String): String =
            TokenCipher.PREFIX + plain.map { (it.code xor 0x5A).toChar() }.joinToString("")

        override fun decrypt(stored: String): String? {
            if (!isEncrypted(stored)) return null
            val body = stored.removePrefix(TokenCipher.PREFIX)
            if (body.isEmpty()) return null
            return body.map { (it.code xor 0x5A).toChar() }.joinToString("")
        }

        override fun isEncrypted(stored: String): Boolean = stored.startsWith(TokenCipher.PREFIX)
    }

    /** 模拟"密文损坏 / Keystore 被重置"：格式识别正常，解密恒失败。 */
    private class BrokenCipher : TokenCipher {
        override fun encrypt(plain: String) = TokenCipher.PREFIX + plain
        override fun decrypt(stored: String): String? = null
        override fun isEncrypted(stored: String) = stored.startsWith(TokenCipher.PREFIX)
    }

    private val app: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        listOf("ai_gateway", "ai_provider").forEach { name ->
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun newSettings() = PreferencesAiProviderSettings(app, XorTokenCipher())

    private fun prefsOf(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 官方服务（模式 A）

    @Test
    fun SaveOfficial_thenRead_roundTripsToken() {
        val settings = newSettings()
        settings.saveOfficial(baseUrl = "https://gw.example.com/", token = "secret-token")

        val next = newSettings()
        assertEquals("https://gw.example.com", next.officialBaseUrl)
        assertEquals("secret-token", next.officialToken)
        assertTrue(next.officialConfigured)
        assertTrue("官方已配置且默认勾选官方 → 三种能力都可用", next.chatAvailable)
        assertTrue(next.anyProviderAvailable)
    }

    @Test
    fun OfficialToken_storedEncryptedNotPlaintext() {
        newSettings().saveOfficial(baseUrl = "https://gw.example.com", token = "secret-token")

        val raw = prefsOf("ai_gateway").getString("token", null)
        assertTrue("落盘值应带加密前缀", raw != null && raw.startsWith(TokenCipher.PREFIX))
        assertFalse("明文不得出现在落盘值中", raw!!.contains("secret-token"))
    }

    @Test
    fun LegacyPlaintextToken_migratedOnRead_andStillReadable() {
        prefsOf("ai_gateway").edit()
            .putString("base_url", "https://gw.example.com")
            .putString("token", "legacy-plain-token")
            .apply()

        val settings = newSettings()
        assertEquals("迁移期间读到的仍是原值", "legacy-plain-token", settings.officialToken)

        val raw = prefsOf("ai_gateway").getString("token", null)
        assertTrue("迁移后落盘应为密文", raw != null && raw.startsWith(TokenCipher.PREFIX))
        assertEquals("迁移后读取值不变", "legacy-plain-token", newSettings().officialToken)
    }

    @Test
    fun CorruptedCipher_degradesToUnconfigured_notPlaintext() {
        val broken = PreferencesAiProviderSettings(app, BrokenCipher())
        broken.saveOfficial(baseUrl = "https://gw.example.com", token = "secret-token")

        val next = PreferencesAiProviderSettings(app, BrokenCipher())
        assertNull("解密失败按未配置处理，绝不回退明文", next.officialToken)
        assertFalse(next.officialConfigured)
        assertFalse("官方不可用且没有自定义配置 → 云端通路不可用", next.anyProviderAvailable)
        assertEquals("baseUrl 不是机密，不受影响", "https://gw.example.com", next.officialBaseUrl)
    }

    @Test
    fun UseOfficial_defaultsToTrue() {
        assertTrue("升级用户与只注入构建参数的包行为必须不变", newSettings().useOfficialService)
    }

    @Test
    fun BuildConfigFallback_usedWhenNothingStored() {
        val settings = newSettings()
        if (BuildConfig.AI_GATEWAY_TOKEN.isBlank()) {
            assertNull(settings.officialToken)
            assertFalse(settings.officialConfigured)
        } else {
            assertEquals(BuildConfig.AI_GATEWAY_TOKEN, settings.officialToken)
            assertTrue(settings.officialConfigured)
        }
    }

    // ------------------------------------------------------------ 自定义 Provider（模式 B）

    @Test
    fun SaveThreeProviders_independently() {
        val settings = newSettings()
        settings.setUseOfficialService(false)
        settings.saveChat("https://api.openai.com/v1", "sk-chat", "gpt-4o-mini")
        settings.saveEmbed("https://api.siliconflow.cn/v1", "sk-embed", "BAAI/bge-m3")
        settings.saveAsr("https://api.siliconflow.cn/v1", "sk-asr", "FunAudioLLM/SenseVoiceSmall")

        val next = newSettings()
        assertEquals("gpt-4o-mini", next.chatConfig?.model)
        assertEquals("BAAI/bge-m3", next.embedConfig?.model)
        assertEquals("FunAudioLLM/SenseVoiceSmall", next.asrConfig?.model)
        assertEquals("sk-chat", next.chatConfig?.apiKey)
        assertTrue(next.chatAvailable)
        assertTrue(next.embedAvailable)
        assertTrue(next.asrAvailable)
        assertTrue(next.anyProviderAvailable)
    }

    @Test
    fun ApiKeys_storedEncryptedNotPlaintext() {
        newSettings().saveChat("https://api.openai.com/v1", "sk-chat-secret", "gpt-4o-mini")

        val raw = prefsOf("ai_provider").getString("chat_api_key", null)
        assertTrue("apiKey 同样必须加密落盘", raw != null && raw.startsWith(TokenCipher.PREFIX))
        assertFalse("明文 Key 不得落盘", raw!!.contains("sk-chat-secret"))
    }

    @Test
    fun SaveWithNullApiKey_keepsExistingKey() {
        val settings = newSettings()
        settings.saveChat("https://api.openai.com/v1", "sk-chat", "gpt-4o-mini")

        // 用户只改模型名，输入框里的 Key 是空的 → 传 null，必须保留原 Key
        settings.saveChat("https://api.openai.com/v1", null, "gpt-4o")

        assertEquals("gpt-4o", settings.chatConfig?.model)
        assertEquals("改模型不该把密钥弄丢", "sk-chat", settings.chatConfig?.apiKey)
    }

    @Test
    fun SaveWithBlankApiKey_clearsKey() {
        val settings = newSettings()
        settings.saveChat("http://127.0.0.1:11434/v1", "sk-chat", "qwen2.5")
        settings.saveChat("http://127.0.0.1:11434/v1", "   ", "qwen2.5")

        assertEquals("显式清空（本地无鉴权服务）", "", settings.chatConfig?.apiKey)
        assertTrue("只有地址与模型也算可用（自托管场景）", settings.chatAvailable)
    }

    @Test
    fun MissingModel_makesProviderUnconfigured() {
        val settings = newSettings()
        settings.setUseOfficialService(false)
        settings.saveEmbed("https://api.siliconflow.cn/v1", "sk", "")

        assertNull(settings.embedConfig)
        assertFalse("只有地址没有模型，谈不上可用", settings.embedAvailable)
    }

    @Test
    fun CustomProviderWithUseOfficialTrue_isNotUsedUnlessOfficialMissing() {
        val settings = newSettings()
        settings.saveChat("https://api.openai.com/v1", "sk", "gpt-4o-mini")
        settings.setUseOfficialService(false)

        assertTrue(settings.chatAvailable)
        assertFalse("官方没配置时切到自定义", settings.officialConfigured)
    }

    @Test
    fun OfficialMissingButCustomPresent_anyProviderAvailable() {
        val settings = newSettings()
        // 默认勾选官方，但官方没有地址/令牌（开源编译包就是这种状态）
        settings.saveEmbed("https://api.siliconflow.cn/v1", "sk", "BAAI/bge-m3")

        assertFalse(settings.officialConfigured)
        assertTrue("自定义向量可用 → 云端通路可用", settings.embedAvailable)
        assertTrue(settings.anyProviderAvailable)
    }

    @Test
    fun SaveOfficial_trailingSlashNormalized() {
        val settings = newSettings()
        settings.saveOfficial("https://gw.example.com//", "t")

        assertEquals("https://gw.example.com", settings.officialBaseUrl)
    }

    @Test
    fun OldGatewayConfig_withoutUseOfficialFlag_treatedAsOfficialMode() {
        // 改造前的存量：只有 base_url + token，没有 use_official 键
        prefsOf("ai_gateway").edit()
            .putString("base_url", "https://gw.example.com")
            .putString("token", XorTokenCipher().encrypt("legacy-token"))
            .apply()

        val settings = newSettings()
        assertTrue(settings.useOfficialService)
        assertTrue(settings.officialConfigured)
        assertTrue(settings.asrAvailable)
    }

    // ------------------------------------------------------------ Chat 协议方言（P2）

    @Test
    fun ChatProtocol_defaultsToOpenAi_forLegacyConfigs() {
        newSettings().saveChat("https://api.openai.com/v1", "sk", "gpt-4o-mini")

        assertEquals(
            "没有写入过协议的存量配置必须按 OpenAI 兼容处理",
            ProviderProtocol.OPENAI,
            newSettings().chatProtocol,
        )
        assertEquals(ProviderProtocol.OPENAI, newSettings().chatConfig?.protocol)
    }

    @Test
    fun SaveChatProtocol_persists_andSurvivesSavingFields() {
        val settings = newSettings()
        settings.saveChatProtocol(ProviderProtocol.ANTHROPIC)

        assertEquals(ProviderProtocol.ANTHROPIC, newSettings().chatProtocol)

        // 用户随后才填地址/模型：保存这些字段不能把协议冲回默认值
        settings.saveChat("https://api.anthropic.com/v1", "sk-ant", "claude-3-5-sonnet-latest")

        val config = newSettings().chatConfig
        assertEquals(ProviderProtocol.ANTHROPIC, config?.protocol)
        assertEquals("https://api.anthropic.com/v1", config?.baseUrl)
        assertEquals(ProviderProtocol.ANTHROPIC, newSettings().chatProtocol)
    }

    @Test
    fun UnknownChatProtocol_fallsBackToOpenAi() {
        // 脏数据 / 版本回退：不能猜成 Anthropic（那会把 OpenAI 的请求发到 /messages）
        prefsOf("ai_provider").edit().putString("chat_protocol", "SOMETHING-ELSE").apply()

        assertEquals(ProviderProtocol.OPENAI, newSettings().chatProtocol)
    }

    @Test
    fun EmbedAndAsr_areAlwaysOpenAiCompatible() {
        val settings = newSettings()
        settings.saveEmbed("https://api.siliconflow.cn/v1", "sk", "BAAI/bge-m3")
        settings.saveAsr("https://api.siliconflow.cn/v1", "sk", "SenseVoiceSmall")

        assertEquals(ProviderProtocol.OPENAI, settings.embedConfig?.protocol)
        assertEquals(ProviderProtocol.OPENAI, settings.asrConfig?.protocol)
    }
}