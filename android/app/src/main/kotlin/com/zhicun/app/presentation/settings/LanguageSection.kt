package com.zhicun.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.settings.AppLanguage

/**
 * 语言分组（P11-02 / R-I18N-01）。
 *
 * 只有两项，没有"跟随系统"：首启已经按设备语言落过一次地，之后再跟随系统会让
 * "我明明选了 English，重启又变回中文"——那正是用户最讨厌的不可控。
 *
 * 语言名用各自的语言写（中文 / English），不做翻译：一个看不懂当前界面语言的人，
 * 需要能认出自己的母语那一行。
 */
@Composable
internal fun LanguageSection(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.selectableGroup()) {
            LanguageOption(
                language = AppLanguage.ZH,
                label = stringResource(R.string.settings_language_zh),
                tag = SettingsTestTags.LANGUAGE_ZH,
                selected = current == AppLanguage.ZH,
                onSelect = onSelect,
            )
            LanguageOption(
                language = AppLanguage.EN,
                label = stringResource(R.string.settings_language_en),
                tag = SettingsTestTags.LANGUAGE_EN,
                selected = current == AppLanguage.EN,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    language: AppLanguage,
    label: String,
    tag: String,
    selected: Boolean,
    onSelect: (AppLanguage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(language) },
            )
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}