package com.zhicun.app.presentation.inbox

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.presentation.common.RecordCard
import com.zhicun.app.presentation.common.uiLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/* 收件箱的筛选条、分组列表与空态（M16 从 InboxScreen.kt 拆出；M19 增加按天分组头与日期筛选 chip）。 */

/**
 * 组头标题：今天 / 昨天 / M月d日（M19）。
 *
 * “今天”在组合期判定而不是写进状态：跨越午夜后停留在页面里，
 * 标题应该跟着翻页（状态里只存 LocalDate 这个客观事实）。
 */
@Composable
internal fun dayGroupTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.inbox_group_today)
        today.minusDays(1) -> stringResource(R.string.inbox_group_yesterday)
        else -> date.format(datePattern())
    }
}

/** 日期显示格式收进资源，并按界面语言取 Locale：英文要 `Oct 29`，中文要 `10月29日`。 */
@Composable
private fun datePattern(): DateTimeFormatter =
    DateTimeFormatter.ofPattern(stringResource(R.string.inbox_group_date_pattern), uiLocale())

/**
 * 筛选行（M16）：左边"全部/收藏"（+ M19 的日期筛选 chip），右边"整理"入口。
 *
 * 不把筛选做成顶栏按钮：顶栏已经有日历与更多菜单，再加筛选就会挤掉标题。
 * 筛选条紧贴列表，用户改条件后立刻看到结果，比顶栏更符合"就地生效"的心智。
 */
@Composable
internal fun InboxFilterRow(
    filter: InboxFilter,
    showSelectEntry: Boolean,
    onToggleFilter: () -> Unit,
    onEnterSelection: () -> Unit,
    selectedDateLabel: String? = null,
    onClearDateFilter: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter == InboxFilter.ALL,
            onClick = { if (filter != InboxFilter.ALL) onToggleFilter() },
            label = { Text(text = stringResource(R.string.inbox_filter_all)) },
            modifier = Modifier.testTag(InboxTestTags.FILTER_ALL),
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = filter == InboxFilter.FAVORITE,
            onClick = { if (filter != InboxFilter.FAVORITE) onToggleFilter() },
            label = { Text(text = stringResource(R.string.inbox_filter_favorite)) },
            modifier = Modifier.testTag(InboxTestTags.FILTER_FAVORITE),
        )
        if (selectedDateLabel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            // 日期筛选 chip：点 ×（或整个 chip）即清除。chip 本身不可"取消选中"——
            // 它的选中态就是"日期筛选生效中"，再提供第二种解除方式只会让人犹豫点哪个。
            InputChip(
                selected = true,
                onClick = onClearDateFilter,
                label = { Text(text = selectedDateLabel) },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.inbox_calendar_clear),
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.testTag(InboxTestTags.FILTER_DATE),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showSelectEntry) {
            // 整理入口做成胶囊（2026-10-24 用户反馈"整理像没设计"）：
            // 它是一个**模式切换**动作，不是普通链接 —— 用 secondaryContainer 软底 +
            // 圆角，与详情页收藏/置顶/归档的 ToggleAction 同一设计语言；
            // 纯文字按钮淹没在筛选片旁边，看起来像临时加的。
            Surface(
                onClick = onEnterSelection,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag(InboxTestTags.SELECT_ENTRY),
            ) {
                Text(
                    text = stringResource(R.string.inbox_select_entry),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun InboxList(
    uiState: InboxUiState.Content,
    selection: InboxSelection,
    onRecordClick: (String) -> Unit,
    onLongPressRecord: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onToggleDayCollapsed: (LocalDate) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(InboxTestTags.LIST),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // key 必填（AGENTS.md §8）：组头 key 带 "header:" 前缀与 recordId 互不冲突，
        // 分享进来新记录时列表前插，没有 key 会整列重组。contentType 区分组头与卡片，
        // 折叠/展开时 LazyColumn 不至于把两种行互相误复用。
        items(
            items = uiState.listItems,
            key = { it.key },
            contentType = { it::class.simpleName },
        ) { item ->
            when (item) {
                is InboxListItem.PinnedHeader -> InboxGroupHeader(
                    title = stringResource(R.string.inbox_group_pinned),
                    count = item.count,
                    collapsible = false,
                    collapsed = false,
                    onToggle = {},
                    modifier = Modifier.testTag(InboxTestTags.GROUP_HEADER_PINNED),
                )

                is InboxListItem.DayHeader -> InboxGroupHeader(
                    title = dayGroupTitle(item.date),
                    count = item.count,
                    // 日期筛选期间组头恒展开（见 InboxViewModel.toggleDayCollapsed）：
                    // 此时连折叠箭头都不显示，明确传达"此刻没有可折叠的东西"
                    collapsible = uiState.selectedDate == null,
                    collapsed = item.collapsed,
                    onToggle = { onToggleDayCollapsed(item.date) },
                    modifier = Modifier.testTag(InboxTestTags.dayHeaderTag(item.date)),
                )

                is InboxListItem.RecordEntry -> RecordCard(
                    record = item.record,
                    tags = uiState.tagsByRecord[item.record.id].orEmpty(),
                    selected = item.record.id in selection.selectedIds,
                    onClick = {
                        if (selection.active) {
                            onToggleSelected(item.record.id)
                        } else {
                            onRecordClick(item.record.id)
                        }
                    },
                    onLongClick = { onLongPressRecord(item.record.id) },
                )
            }
        }
    }
}

/**
 * 分组头（M19）：标题 + 条数 + 折叠箭头。
 *
 * 点整行都可折叠（不只箭头）—— 日期头的命中区域横贯全宽，单手够得着；
 * 「置顶」组不可折叠（collapsible=false）：置顶的意义就是"一直看得见"。
 */
@Composable
internal fun InboxGroupHeader(
    title: String,
    count: Int,
    collapsible: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "chevronRotation",
    )
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.inbox_group_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (collapsible) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(
                        if (collapsed) {
                            R.string.inbox_group_expand
                        } else {
                            R.string.inbox_group_collapse
                        },
                    ),
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }
    }
}

/** 筛选把记录全筛掉了：库里有数据，只是这个条件下没有。 */
@Composable
internal fun FilteredEmptyState(
    filter: InboxFilter,
    modifier: Modifier = Modifier,
    dateFiltered: Boolean = false,
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when {
                    dateFiltered -> R.string.inbox_date_filtered_empty_title
                    filter == InboxFilter.ALL -> R.string.inbox_empty_title
                    else -> R.string.inbox_filtered_empty_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(
                when {
                    dateFiltered -> R.string.inbox_date_filtered_empty_hint
                    filter == InboxFilter.ALL -> R.string.inbox_empty_hint
                    else -> R.string.inbox_filtered_empty_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 空状态：不是一句"暂无数据"就完事 —— 新用户此刻最需要知道的是"入口在哪"。
 */
@Composable
internal fun InboxEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .testTag(InboxTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.inbox_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.inbox_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.inbox_empty_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
