package com.zhicun.app.presentation.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.zhicun.app.domain.ai.ProviderProtocol
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「AI 服务配置」分组（开源改造 P0/P1）。
 *
 * 只测无状态的 `SettingsContent`，钉住四件事：
 * 1. 官方模式只显示官方卡片，自定义模式才展开三组；
 * 2. 每组有自己的保存按钮，**点击时把输入框里的值原样交出去**（不在组件里偷偷落盘）；
 * 3. 未配置状态必须显式可见 —— 用户填错一半时最需要看到的就是"还差什么"；
 * 4. 向量待重建数 > 0 时给出重建入口，为 0 时不出现（避免一个永远无意义的按钮）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class SettingsAiSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var officialToggles = mutableListOf<Boolean>()
    private var officialSaves = mutableListOf<Pair<String, String>>()
    private var chatSaves = mutableListOf<Triple<String, String, String>>()
    private var embedSaves = mutableListOf<Triple<String, String, String>>()
    private var asrSaves = mutableListOf<Triple<String, String, String>>()
    private var protocolSelections = mutableListOf<ProviderProtocol>()
    private var reEmbeds = 0

    private fun render(uiState: SettingsUiState) {
        composeRule.setContent {
            AppTheme {
                SettingsContent(
                    uiState = uiState,
                    onBack = {},
                    onFloatingToggled = {},
                    onGrantPermission = {},
                    onUseOfficialToggled = { officialToggles += it },
                    onSaveOfficial = { baseUrl, token -> officialSaves += baseUrl to token },
                    onSaveChat = { baseUrl, key, model -> chatSaves += Triple(baseUrl, key, model) },
                    onChatProtocolSelected = { protocolSelections += it },
                    onSaveEmbed = { baseUrl, key, model -> embedSaves += Triple(baseUrl, key, model) },
                    onSaveAsr = { baseUrl, key, model -> asrSaves += Triple(baseUrl, key, model) },
                    onReEmbed = { reEmbeds++ },
                )
            }
        }
    }

    private fun tag(prefix: String, suffix: String) = prefix + suffix

    @Test
    fun ChatProtocolSelector_reflectsStateAndEmitsSelection() {
        render(
            SettingsUiState(
                useOfficialService = false,
                chatProtocol = ProviderProtocol.ANTHROPIC,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.AI_CHAT_PROTOCOL_ANTHROPIC)
            .performScrollTo()
            .assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.AI_CHAT_PROTOCOL_OPENAI)
            .performScrollTo()
            .assertIsNotSelected()

        composeRule.onNodeWithTag(SettingsTestTags.AI_CHAT_PROTOCOL_OPENAI)
            .performScrollTo()
            .performClick()

        assertEquals(listOf(ProviderProtocol.OPENAI), protocolSelections)
    }

    @Test
    fun ChatProtocolSelector_hiddenInOfficialMode() {
        render(SettingsUiState(useOfficialService = true))

        composeRule.onNodeWithTag(SettingsTestTags.AI_CHAT_PROTOCOL_OPENAI).assertDoesNotExist()
    }

    @Test
    fun OfficialMode_showsOfficialCard_andHidesProviderCards() {
        render(SettingsUiState(useOfficialService = true))

        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_SWITCH)
            .performScrollTo()
            .assertIsOn()
        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_BASE_URL)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_BASE_URL_SUFFIX),
        ).assertDoesNotExist()
    }

    @Test
    fun CustomMode_showsThreeCards_allUnconfiguredByDefault() {
        render(SettingsUiState(useOfficialService = false))

        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_SWITCH)
            .performScrollTo()
            .assertIsOff()
        listOf(
            SettingsTestTags.AI_CHAT_PREFIX,
            SettingsTestTags.AI_EMBED_PREFIX,
            SettingsTestTags.AI_ASR_PREFIX,
        ).forEach { prefix ->
            composeRule.onNodeWithTag(tag(prefix, SettingsTestTags.AI_STATUS_SUFFIX))
                .performScrollTo()
                .assertTextEquals("未配置")
        }
    }

    @Test
    fun SavingChatCard_passesTypedValuesToCallback() {
        render(SettingsUiState(useOfficialService = false))

        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_BASE_URL_SUFFIX),
        ).performScrollTo().performTextInput("https://api.openai.com/v1")
        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_API_KEY_SUFFIX),
        ).performScrollTo().performTextInput("sk-test")
        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_MODEL_SUFFIX),
        ).performScrollTo().performTextInput("gpt-4o-mini")
        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_SAVE_SUFFIX),
        ).performScrollTo().performClick()

        assertEquals(
            listOf(Triple("https://api.openai.com/v1", "sk-test", "gpt-4o-mini")),
            chatSaves,
        )
        assertEquals("另外两张卡片不该被这次点击影响", emptyList<Any>(), embedSaves)
    }

    @Test
    fun ConfiguredProvider_showsConfiguredStatus() {
        render(
            SettingsUiState(
                useOfficialService = false,
                chatBaseUrl = "https://api.openai.com/v1",
                chatModel = "gpt-4o-mini",
                chatConfigured = true,
            ),
        )

        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_STATUS_SUFFIX),
        ).performScrollTo().assertTextEquals("已配置")
        composeRule.onNodeWithTag(
            tag(SettingsTestTags.AI_CHAT_PREFIX, SettingsTestTags.AI_BASE_URL_SUFFIX),
        ).performScrollTo().assertTextContains("https://api.openai.com/v1")
    }

    @Test
    fun VectorPending_showsCountAndRebuildButton() {
        render(
            SettingsUiState(
                useOfficialService = false,
                embedConfigured = true,
                vectorStatusLoaded = true,
                incompatibleEmbeddingCount = 3,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.AI_VECTOR_STATUS)
            .performScrollTo()
            .assertTextContains("3 条记录", substring = true)
        composeRule.onNodeWithTag(SettingsTestTags.AI_VECTOR_REEMBED)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, reEmbeds)
    }

    @Test
    fun VectorClean_hidesRebuildButton() {
        render(
            SettingsUiState(
                useOfficialService = false,
                embedConfigured = true,
                vectorStatusLoaded = true,
                incompatibleEmbeddingCount = 0,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.AI_VECTOR_STATUS)
            .performScrollTo()
            .assertTextEquals("全部可检索记录都使用当前模型")
        composeRule.onNodeWithTag(SettingsTestTags.AI_VECTOR_REEMBED).assertDoesNotExist()
    }

    @Test
    fun Rebuilding_disablesButtonWhileRunning() {
        render(
            SettingsUiState(
                useOfficialService = false,
                embedConfigured = true,
                vectorStatusLoaded = true,
                incompatibleEmbeddingCount = 2,
                reEmbedRunning = true,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.AI_VECTOR_REEMBED)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun OfficialConfig_savePassesBaseUrlAndToken() {
        render(
            SettingsUiState(
                useOfficialService = true,
                officialBaseUrl = "https://gw.example.com",
                officialConfigured = true,
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_BASE_URL)
            .performScrollTo()
            .assertTextContains("https://gw.example.com")
        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_TOKEN)
            .performScrollTo()
            .performTextInput("token-value")
        composeRule.onNodeWithTag(SettingsTestTags.AI_OFFICIAL_SAVE)
            .performScrollTo()
            .performClick()

        assertEquals(listOf("https://gw.example.com" to "token-value"), officialSaves)
        assertTrue("开关回调不该被保存按钮触发", officialToggles.isEmpty())
    }
}