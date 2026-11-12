package com.zhicun.app.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R

/**
 * 长文本收起/展开（2026-10-24 用户反馈："原文过长时默认收起，点击之后加载"）。
 *
 * 成熟做法参照 Google Play 商店的 "Read more"：不猜测"多长算长"，而是**先按
 * 限行渲染、用布局结果说话** —— `onTextLayout` 的 `hasVisualOverflow` 为真才出现
 * "展开全文"。短文本永远不会多出一个没用的按钮；长文本展开/收起都在原地完成，
 * 不改变详情页的整体结构（不新增页面、不打断 AI 摘要 → 原文 → 附件的阅读顺序）。
 *
 * ## 为什么不用字符数阈值
 * "多少字算长"取决于字号、屏宽、语言，写死一个数字必然在某些设备上误判
 * （要么短文出现按钮、要么长文没被收起）。布局是否溢出是唯一可靠的判据。
 *
 * ## 为什么"展开"不做成分页/新页面
 * 原文是"取用"场景：用户展开后往往要选中复制一段。跳到新页面等于把复制目标
 * 挪了地方；在原地展开，选中手势和复制入口都不变。Compose 的 Text 渲染几万
 * 字符没有压力（TextView 时代同理），只有十万字符量级才值得考虑独立页面。
 *
 * ## 为什么 [SelectionContainer] 只包住文本
 * 选择手势和按钮点击是两套手势系统：按钮包进选择容器后，长按会先进入选择
 * 模式而不是触发点击，边缘行为难预测。按钮放外面，两者互不干扰。
 *
 * @param text 全文
 * @param stateKey 状态归属键（通常是 recordId 或分区 tag）：切换记录/分区时
 *   展开/收起状态自动复位，不会把上一条的展开状态带到这一条
 * @param collapsedLines 收起时的行数上限（约屏高的 1/5，保证"原文从哪开始"可见）
 * @param testTag 文本本身的 tag；切换按钮的 tag 是它加 `_toggle` 后缀
 */
@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    stateKey: Any? = null,
    collapsedLines: Int = DEFAULT_COLLAPSED_LINES,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    testTag: String? = null,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    // 溢出与否由首帧布局决定，不需要跨旋转保存：旋转后重新布局，重新算一遍
    var overflowed by remember(stateKey, text) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) overflowed = result.hasVisualOverflow
                },
                modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
        }
        if (overflowed || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag((testTag ?: "expandable_text") + "_toggle"),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.expandable_collapse else R.string.expandable_expand,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** 收起时的行数上限。 */
private const val DEFAULT_COLLAPSED_LINES = 12
