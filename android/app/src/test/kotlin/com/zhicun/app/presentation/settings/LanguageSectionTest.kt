package com.zhicun.app.presentation.settings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语言分组（P11-02）。
 *
 * 这里只钉两件事：点了就回调、当前项能被读出来。**不发请求**由 ViewModel 侧用例保证，
 * 界面层不该有自己的"顺手做点什么"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class LanguageSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun LanguageSection_showsStoredLanguageAsSelected() {
        composeRule.setContent {
            AppTheme {
                LanguageSection(current = AppLanguage.EN, onSelect = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.LANGUAGE_EN).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.LANGUAGE_ZH).assertIsNotSelected()
    }

    @Test
    fun LanguageSection_clickChinese_reportsChinese() {
        var selected: AppLanguage? = null
        composeRule.setContent {
            AppTheme {
                LanguageSection(current = AppLanguage.EN, onSelect = { selected = it })
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.LANGUAGE_ZH).performClick()

        assertEquals(AppLanguage.ZH, selected)
    }

    @Test
    fun LanguageSection_clickCurrentLanguage_stillReportsIt() {
        var selected: AppLanguage? = null
        composeRule.setContent {
            AppTheme {
                LanguageSection(current = AppLanguage.ZH, onSelect = { selected = it })
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.LANGUAGE_ZH).performClick()

        assertEquals(AppLanguage.ZH, selected)
    }

    /** 中文界面里也必须能把语言名认出来：语言名用各自的语言写，不做翻译。 */
    @Test
    fun LanguageSection_labelsAreSelfDescribing() {
        composeRule.setContent {
            AppTheme {
                LanguageSection(current = AppLanguage.ZH, onSelect = {})
            }
        }

        composeRule.onNodeWithText("English").assertExists()
        composeRule.onNodeWithText("中文").assertExists()
    }
}