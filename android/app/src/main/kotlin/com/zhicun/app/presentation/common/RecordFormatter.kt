package com.zhicun.app.presentation.common

import androidx.annotation.StringRes
import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录 → 展示文本的纯函数集合。
 *
 * 单独抽出来的原因有两个：
 * 1. 纯 Kotlin，可直接单测，不需要 Compose 运行时；
 * 2. 列表卡片与详情页必须用**同一套**规则决定"标题是什么、摘要是哪段"，
 *    否则同一记录在两处显示的标题会不一致。
 *
 * 约定：返回 null 表示"没有可用的文本"，此时由 UI 侧用 stringResource 兜底，
 * 本文件不持有任何中文硬编码（AGENTS.md §2.2 禁止硬编码 UI 字符串）。
 */

/** 标题候选长度上限：卡片最多两行，超过后由 UI 层省略号截断，这里先按字符数夹一刀。 */
private const val TITLE_MAX_CHARS = 60

/** 摘要候选长度上限（避免把几万字笔记整个塞进 Text 布局）。 */
private const val SNIPPET_MAX_CHARS = 160

/**
 * 卡片标题：优先来源标题（网页标题等），其次正文首个非空行。
 *
 * 注意顺序不能反：`rawText` 对网页/文件往往是"抓到的正文"，标题信息量更低；
 * 而用户手写的笔记没有 sourceTitle，自然回落到正文首行。
 */
fun Record.displayTitle(): String? {
    val fromSource = sourceTitle?.trim()?.takeIf { it.isNotEmpty() }
    if (fromSource != null) return fromSource.take(TITLE_MAX_CHARS)

    val firstLine = rawText?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
    return firstLine?.take(TITLE_MAX_CHARS)
}

/**
 * 兜底标题里的时间格式（2026-10-24）。
 *
 * 用紧凑档而不是详情页的完整格式：卡片标题只有一行，带上年份会把唯一能区分的
 * "几点录的"挤出可视区；年份在详情页与排序里都能看到。具体 pattern 由
 * [timestampPattern] 按语言给出（中文 `MM-dd HH:mm`，英文 `MMM d, h:mm a`）。
 */
const val FALLBACK_TITLE_TIME_PATTERN: String = "MM-dd HH:mm"

/**
 * [displayTitle] 返回 null 时，按记录类型给出的标题模板资源（含一个 `%1$s` 占位给时间）。
 *
 * ## 为什么需要它
 * 录音的转写、图片的 OCR 都落在 `derived_contents`，**不写** `raw_text`
 * （见 `TranscribeWorker` / `OcrWorker`），所以这两类记录的 [displayTitle] 恒为 null。
 * 结果是收件箱里十几条录音全都显示"未命名记录"，彼此无法区分 —— 用户没法挑出
 * "早上那条"去点开（2026-10-24 反馈）。
 *
 * ## 为什么返回资源 id 而不是拼好的字符串
 * 本文件是纯 Kotlin，且不持有任何中文硬编码（AGENTS.md §2.2）。时间由调用方用
 * [formatTimestamp] 格式化后，交给 Compose 的 `stringResource` 填进模板。
 *
 * ## 为什么按类型分而不是统一写"记录"
 * 用户扫列表时先看字形轮廓再看文字：录音和图片的卡片外形完全一样（都没有正文），
 * 标题里的"录音/图片"是唯一能一眼分开的线索。
 */
@StringRes
fun recordFallbackTitleRes(type: RecordType): Int = when (type) {
    RecordType.VOICE_NOTE -> R.string.record_fallback_voice
    RecordType.IMAGE -> R.string.record_fallback_image
    RecordType.FILE -> R.string.record_fallback_file
    RecordType.COLLECTION -> R.string.record_fallback_collection
    // 这三类的正常路径都有正文 ⇒ displayTitle 非 null，走不到兜底；
    // 真走到说明记录既没正文也没标题，用中性文案而不是猜一个类型名
    RecordType.TEXT_NOTE,
    RecordType.SHARED_TEXT,
    RecordType.WEB_LINK,
    -> R.string.record_fallback_generic
}

/**
 * 卡片摘要：正文去掉与标题重复的首行后的内容；正文为空时退回 URL。
 *
 * 去首行是为了避免"标题 + 与标题一模一样的摘要"这种一眼假的卡片。
 */
fun Record.displaySnippet(): String? {
    val text = rawText?.trim()
    if (!text.isNullOrEmpty()) {
        val body = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .drop(1)
            .joinToString(" ")
        if (body.isNotEmpty()) return body.take(SNIPPET_MAX_CHARS)
        // 正文只有一行 ⇒ 标题已占满，摘要退回 URL（常见于纯链接分享）
    }
    return sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(SNIPPET_MAX_CHARS)
}

/** 类型徽标文案。 */
@StringRes
fun recordTypeLabelRes(type: RecordType): Int = when (type) {
    RecordType.TEXT_NOTE -> R.string.record_type_text_note
    RecordType.SHARED_TEXT -> R.string.record_type_shared_text
    RecordType.WEB_LINK -> R.string.record_type_web_link
    RecordType.IMAGE -> R.string.record_type_image
    RecordType.VOICE_NOTE -> R.string.record_type_voice_note
    RecordType.FILE -> R.string.record_type_file
    RecordType.COLLECTION -> R.string.record_type_collection
}

/** 来源入口文案（卡片上的"从哪来"）。 */
@StringRes
fun captureSourceLabelRes(source: CaptureSource): Int = when (source) {
    CaptureSource.MANUAL -> R.string.capture_source_manual
    CaptureSource.SYSTEM_SHARE -> R.string.capture_source_system_share
    CaptureSource.CLIPBOARD -> R.string.capture_source_clipboard
    CaptureSource.FLOATING -> R.string.capture_source_floating
    CaptureSource.GALLERY -> R.string.capture_source_gallery
    CaptureSource.SCREENSHOT -> R.string.capture_source_screenshot
}

/** 类型徽标上的单字缩写（不引图标库，正文方块字在窄列里识别度更高）。 */
@StringRes
fun recordTypeShortRes(type: RecordType): Int = when (type) {
    RecordType.TEXT_NOTE -> R.string.record_type_short_text_note
    RecordType.SHARED_TEXT -> R.string.record_type_short_shared_text
    RecordType.WEB_LINK -> R.string.record_type_short_web_link
    RecordType.IMAGE -> R.string.record_type_short_image
    RecordType.VOICE_NOTE -> R.string.record_type_short_voice_note
    RecordType.FILE -> R.string.record_type_short_file
    RecordType.COLLECTION -> R.string.record_type_short_collection
}

/** 处理状态文案。 */
@StringRes
fun processingStatusLabelRes(status: ProcessingStatus): Int = when (status) {
    ProcessingStatus.PENDING -> R.string.processing_status_pending
    ProcessingStatus.PROCESSING -> R.string.processing_status_processing
    ProcessingStatus.DONE -> R.string.processing_status_done
    ProcessingStatus.PARTIAL -> R.string.processing_status_partial
    ProcessingStatus.FAILED -> R.string.processing_status_failed
    ProcessingStatus.LOCAL_ONLY -> R.string.processing_status_local_only
}

/**
 * 时间两档粒度（P11-04）。
 *
 * 分档而不是让调用方传 pattern：pattern 必须随语言变（英文 `Oct 29, 2026`，
 * 中文 `2026-10-29`，R-I18N-12），散在调用点上迟早会漏一处、出现中英混排。
 */
enum class TimestampStyle {
    /** 详情页等有整行宽度的地方：带年份。 */
    FULL,

    /** 卡片标题/副行：只有一行，带年份会把"几点录的"挤出可视区。 */
    COMPACT,
}

/**
 * 时间格式按语言取值（P11-04 / R-I18N-12）。
 *
 * - 中文沿用 `yyyy-MM-dd`：这是国内用户最熟悉、也最省宽度的写法；
 * - 英文用 `Oct 29, 2026` 这类月名在前、逗号分隔的写法，时分用 12 小时制加 AM/PM；
 * - 时长、体积等数字格式不随语言变（见 [formatDuration] / [formatFileSize]）。
 */
fun timestampPattern(style: TimestampStyle, locale: Locale): String {
    val chinese = locale.language == "zh"
    return when {
        chinese && style == TimestampStyle.COMPACT -> FALLBACK_TITLE_TIME_PATTERN
        chinese -> "yyyy-MM-dd HH:mm"
        style == TimestampStyle.COMPACT -> "MMM d, h:mm a"
        else -> "MMM d, yyyy · h:mm a"
    }
}

/**
 * 时间显示。
 *
 * 用固定 pattern 而非 `DateUtils.getRelativeTimeSpanString`：相对时间（"3 分钟前"）
 * 会让列表在滚动时的心智负担变高（每次都要换算），且难以在测试里断言。
 *
 * [locale] 必须由调用方显式给出（Compose 里用 `uiLocale()`）：本文件是纯 Kotlin，
 * 读 `Locale.getDefault()` 会绕过用户的语言偏好，也让单测结果随机器而变。
 * 时区仍用设备时区，不做 UTC 硬编码。
 */
fun formatTimestamp(
    millis: Long,
    locale: Locale,
    style: TimestampStyle = TimestampStyle.FULL,
): String = SimpleDateFormat(timestampPattern(style, locale), locale).format(Date(millis))

/**
 * 时长显示（`mm:ss`，超过 1 小时自然是 `60:00` 这种分钟数）。
 *
 * 不用 `mm:ss` 截断到小时：语音记录极少超过一小时，一旦超过还要再写一套
 * `h:mm:ss` 分支，反而让界面出现两种格式。负数（播放器偶发）按 0 处理。
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * 文件体积的人类可读形式（附件信息用）。
 *
 * 用 1024 进制而非 1000：Android 的文件管理器和 `du` 都按 1024 显示，
 * 混用会让用户觉得"App 显示的和系统对不上"。
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
