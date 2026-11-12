package com.zhicun.app.presentation.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.zhicun.app.R
import kotlinx.coroutines.delay

/** 复制成功反馈的停留时长：够看清，又不至于一直亮着让人以为还处于选中态。 */
private const val COPIED_FEEDBACK_MILLIS = 1_600L

/**
 * 内容模块的"复制"按钮（2026-10-26 用户裁决）。
 *
 * 每个模块（原文 / AI 摘要 / OCR / 网页正文 / 转写）各自一个按钮，不做"复制全部"：
 * 用户要的往往是其中一段，一次复制整页反而还要自己去删。
 *
 * 点完立即变成实心的"已复制 ✓"约 1.6 秒再退回 —— 这是市面上复制按钮的常见反馈：
 * Android 13+ 系统会自己弹"已复制"，但低版本没有，靠按钮自身给确认最稳。
 *
 * [testTag] 由调用方给，测试才能区分"复制的是哪一块"。
 * 空白文字不画按钮 —— 没有内容可复制时按钮只会让人以为坏了。
 */
@Composable
internal fun CopyTextButton(
    text: String,
    onCopy: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MILLIS)
            copied = false
        }
    }
    DetailPillButton(
        label = stringResource(if (copied) R.string.detail_copied else R.string.detail_copy),
        iconRes = if (copied) R.drawable.ic_action_check else R.drawable.ic_action_copy,
        emphasized = copied,
        onClick = {
            onCopy(text)
            copied = true
        },
        modifier = modifier.testTag(testTag),
    )
}
