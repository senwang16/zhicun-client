package com.zhicun.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zhicun.app.domain.model.Record

/**
 * 记录标题的**唯一**展示判据（2026-10-24 从 `RecordCard` 里抽出来）。
 *
 * ## 为什么不放在 `RecordFormatter.kt`
 * 那个文件刻意保持纯 Kotlin（可以直接单测、不依赖 Compose 运行时），而这里的兜底
 * 需要 `stringResource`。所以拆成两层：纯逻辑在 `RecordFormatter`，
 * 需要 Context 的这一层放在这里。
 *
 * ## 为什么必须有兜底
 * 录音的转写、图片的 OCR 都落在 `derived_contents`，**不写 `raw_text`**
 * （见 `TranscribeWorker` / `OcrWorker`），所以 [displayTitle] 对这两类恒为 null。
 * 没有兜底时它们只有一个空标题，多条录音彼此无法区分。
 *
 * 抽成一个函数而不是各处内联：收件箱卡片与详情页"相关记录"必须给出**同一个标题**，
 * 两处各写一份 `displayTitle() ?: ...` 迟早会飘。
 */
@Composable
fun Record.displayTitleOrFallback(): String =
    displayTitle() ?: stringResource(
        recordFallbackTitleRes(type),
        formatTimestamp(createdAt, FALLBACK_TITLE_TIME_PATTERN),
    )
