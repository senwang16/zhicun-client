package com.zhicun.app.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.presentation.theme.AppTheme
import com.zhicun.app.presentation.theme.StatusAmber
import com.zhicun.app.presentation.theme.StatusAmberDark
import com.zhicun.app.presentation.theme.TypeFile
import com.zhicun.app.presentation.theme.TypeFileContainer
import com.zhicun.app.presentation.theme.TypeImage
import com.zhicun.app.presentation.theme.TypeImageContainer
import com.zhicun.app.presentation.theme.TypeVoiceContainer
import com.zhicun.app.presentation.theme.TypeWeb
import com.zhicun.app.presentation.theme.TypeWebContainer

/**
 * 收件箱卡片（2026-10 UI 改版后的唯一列表项形态）。
 *
 * 信息层级（自上而下）：彩色类型图标（左）→ 加粗标题 + 标记 → 两行摘要 →
 * 标签（M11）→ 状态徽标 + 来源·时间。白底卡片浮在页面浅灰底上，
 * 类型辨识交给低饱和"软底 + 深前景"图标，品牌青绿只留给选中态。
 *
 * @param selected 是否处于勾选态（M16 批量整理）。只影响描边与底色，
 *   不改内容排版 —— 勾选时卡片高度一变，整列会跟着跳。
 * @param onLongClick 长按回调（M16 进入选择模式）。为 null 时退回单纯的点击语义，
 *   这样不需要长按的调用方（搜索结果、归档列表）不必传一个空 lambda。
 * @param highlight 搜索命中词（M10）。非空时标题与摘要里的命中片段加高亮底，
 *   仅影响渲染，不改变任何数据。
 * @param dimmed 归档箱弱化态（M16，2026-10 改版）：卡片底色退到页面底色，
 *   提示"这条不常用"，交互（点击/长按）不变。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordCard(
    record: Record,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tags: List<Tag> = emptyList(),
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    highlight: String? = null,
    dimmed: Boolean = false,
) {
    // 不用 `Card(onClick = ...)` 那个重载：它不支持长按。
    // 关掉 Card 自带的点击语义、改用 combinedClickable，两种入口才能并存。
    val clickModifier = if (onLongClick == null) {
        Modifier.combinedClickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                dimmed -> MaterialTheme.colorScheme.background
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                RecordTypeBadge(type = record.type)

                Spacer(modifier = Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 标题兜底：录音/图片的正文不在 raw_text 里
                    // （转写、OCR 落在 derived_contents），displayTitle() 对它们恒为 null。
                    // 判据在 RecordTitles.kt：详情页"相关记录"用的是同一个函数。
                    val title = record.displayTitleOrFallback()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = highlightText(
                                raw = title,
                                keyword = highlight,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (record.isPinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MarkerChip(text = stringResource(R.string.record_marker_pinned))
                        }
                        if (record.isFavorite) {
                            Spacer(modifier = Modifier.width(5.dp))
                            // 用字符而不是图标字体：本项目不引 material-icons（见 RecordTypeBadge 注释）
                            Text(
                                text = "★",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSystemInDarkTheme()) StatusAmberDark else StatusAmber,
                            )
                        }
                    }

                    record.displaySnippet()?.let { snippet ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = highlightText(raw = snippet, keyword = highlight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (tags.isNotEmpty()) {
                TagChips(tags = tags.take(MAX_CARD_TAGS))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProcessingStatusBadge(status = record.processingStatus)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    // 来源入口（分享/剪贴板/悬浮球…）比 App 名更稳定：App 名可能为空
                    text = stringResource(captureSourceLabelRes(record.captureSource)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTimestamp(record.createdAt, pattern = "MM-dd HH:mm"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 标题右侧的小标记（"置顶"）。与标签同形但用主色描边区分"这是状态不是分类"。 */
@Composable
private fun MarkerChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 卡片上的标签行（M11）。
 *
 * 最多 5 个（PRD §6.3）、单行横向排列，超出部分用 `…` 收尾而不是换行：
 * 卡片是"扫一眼"的密度，多一行标签会把时间和状态挤出视野。
 * 用户标签排在 AI 标签前面 —— 用户自己打的标签更能代表他想要的分类。
 *
 * 没有引入 `FlowRow`（实验 API）：单行 + 省略号已经够用，也避免布局在长标签下抖动。
 */
@Composable
private fun TagChips(tags: List<Tag>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEachIndexed { index, tag ->
            if (index > 0) Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "#${tag.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 类型徽标：32dp 圆角方块 + 线性图标（2026-10 改版）。
 *
 * 不引图标库（material-icons 已被 Compose BOM 摘出）：类型图标是自绘 vector drawable，
 * 配色用"软底 + 深前景"的类型色（亮色模式）；暗色模式回落到中性面，避免
 * 浅色软底在深色卡片上刺眼。
 */
@Composable
private fun RecordTypeBadge(
    type: RecordType,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val (container, content) = when (type) {
        RecordType.WEB_LINK ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else TypeWebContainer to TypeWeb

        RecordType.TEXT_NOTE, RecordType.SHARED_TEXT ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary

        RecordType.VOICE_NOTE ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else TypeVoiceContainer to StatusAmber

        RecordType.IMAGE ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else TypeImageContainer to TypeImage

        RecordType.FILE ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else TypeFileContainer to TypeFile

        RecordType.COLLECTION ->
            if (dark) MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .background(color = container, shape = RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(recordTypeIconRes(type)),
            contentDescription = stringResource(recordTypeShortRes(type)),
            tint = content,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 类型 → 线稿图标。图标本身不区分主题色，由 [RecordTypeBadge] 统一上色。 */
internal fun recordTypeIconRes(type: RecordType): Int = when (type) {
    RecordType.WEB_LINK -> R.drawable.ic_type_web
    RecordType.TEXT_NOTE, RecordType.SHARED_TEXT -> R.drawable.ic_type_text
    RecordType.VOICE_NOTE -> R.drawable.ic_type_voice
    RecordType.IMAGE -> R.drawable.ic_type_image
    RecordType.FILE -> R.drawable.ic_type_file
    RecordType.COLLECTION -> R.drawable.ic_type_collection
}

/**
 * 搜索命中词高亮（M10，纯渲染）：命中片段加青绿软底，不改变文本内容。
 * 大小写不敏感；keyword 为空或未命中时原样返回。
 */
private fun highlightText(raw: String, keyword: String?): androidx.compose.ui.text.AnnotatedString {
    val query = keyword?.trim().orEmpty()
    if (query.isEmpty()) return buildAnnotatedString { append(raw) }

    val lowerRaw = raw.lowercase()
    val lowerQuery = query.lowercase()
    return buildAnnotatedString {
        append(raw)
        var index = lowerRaw.indexOf(lowerQuery)
        while (index >= 0) {
            addStyle(
                style = SpanStyle(
                    color = Color(0xFF0B4F4A),
                    background = Color(0xFFCFF2EC),
                    fontWeight = FontWeight.Medium,
                ),
                start = index,
                end = index + query.length,
            )
            index = lowerRaw.indexOf(lowerQuery, index + query.length)
        }
    }
}

/** 卡片上最多显示的标签数（PRD §6.3：每条记录最多 5 个 AI 标签）。 */
private const val MAX_CARD_TAGS = 5

@Preview(showBackground = true)
@Composable
private fun RecordCardPreview() {
    AppTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecordCard(
                record = Record(
                    id = "preview-1",
                    type = RecordType.WEB_LINK,
                    captureSource = CaptureSource.SYSTEM_SHARE,
                    rawText = "Compose 的 recomposition 只发生在读取了 State 的作用域内。\n这也是为什么把状态读取下沉到子组件能减少重组范围。",
                    sourceUrl = "https://developer.android.com/jetpack/compose",
                    sourceTitle = "Compose 心智模型",
                    sourceApp = "Chrome",
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                    localStatus = LocalStatus.SAVED,
                    processingStatus = ProcessingStatus.DONE,
                    sensitivityStatus = SensitivityStatus.CLEAR,
                    recordRevision = 1,
                    isDeleted = false,
                    isPinned = true,
                    isFavorite = true,
                ),
                onClick = {},
                selected = true,
                onLongClick = {},
            )
            RecordCard(
                record = Record(
                    id = "preview-2",
                    type = RecordType.TEXT_NOTE,
                    captureSource = CaptureSource.MANUAL,
                    rawText = "记得问一下物业停车位的事",
                    sourceUrl = null,
                    sourceTitle = null,
                    sourceApp = null,
                    createdAt = 1_700_000_100_000L,
                    updatedAt = 1_700_000_100_000L,
                    localStatus = LocalStatus.SAVED,
                    processingStatus = ProcessingStatus.PENDING,
                    sensitivityStatus = SensitivityStatus.UNKNOWN,
                    recordRevision = 1,
                    isDeleted = false,
                ),
                onClick = {},
                highlight = "物业",
            )
        }
    }
}
