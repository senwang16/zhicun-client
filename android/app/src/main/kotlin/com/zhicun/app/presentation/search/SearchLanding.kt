package com.zhicun.app.presentation.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.model.TagUsage

/**
 * 搜索空闲态（M21）：最近搜索 + 标签两个区块。
 *
 * ## 为什么单独一个文件
 * SearchScreen.kt 已经接近 400 行的文件上限（AGENTS.md §2.1）；这里只放"没有输入时"
 * 的布局与 chip 组件，结果列表逻辑仍留在 SearchScreen。
 *
 * ## 两个区块都可能为空
 * 新用户（没搜过、也还没跑过 AI）会同时为空 —— 那种情况下上层直接回退到原来的
 * 居中引导文案，不渲染一个只有标题的空壳。
 *
 * @param history 最近搜索（最多 10 条，见 [com.zhicun.app.domain.usecase.ObserveSearchHistoryUseCase]）
 * @param tags 标签概览（最多 12 条，口径见 `TagDao.observeTagUsage`）
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SearchLanding(
    history: List<SearchHistoryEntry>,
    tags: List<TagUsage>,
    onHistorySelected: (SearchHistoryEntry) -> Unit,
    onDeleteHistory: (SearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag(SearchTestTags.LANDING),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (history.isNotEmpty()) {
            item(key = "history-header") {
                SearchSectionHeader(
                    title = stringResource(R.string.search_history_title),
                    actionLabel = stringResource(R.string.search_history_clear),
                    onAction = onClearHistory,
                    tag = SearchTestTags.HISTORY_SECTION,
                )
            }
            item(key = "history-chips") {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SearchTestTags.HISTORY_CHIPS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { entry ->
                        HistoryChip(
                            entry = entry,
                            onClick = { onHistorySelected(entry) },
                            onLongClick = { onDeleteHistory(entry) },
                        )
                    }
                }
            }
        }

        if (tags.isNotEmpty()) {
            item(key = "tags-header") {
                SearchSectionHeader(
                    title = stringResource(R.string.search_tags_title),
                    subtitle = stringResource(R.string.search_tags_subtitle),
                    tag = SearchTestTags.TAGS_SECTION,
                    modifier = Modifier.padding(top = if (history.isEmpty()) 0.dp else 10.dp),
                )
            }
            item(key = "tag-chips") {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SearchTestTags.TAG_CHIPS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { usage ->
                        TagChip(name = usage.name, onClick = { onTagSelected(usage.name) })
                    }
                }
            }
        }

        item(key = "landing-hint") {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.search_landing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 区块标题：主标题 +（可选的）说明 +（可选的）右侧动作。 */
@Composable
private fun SearchSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    tag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (tag == null) Modifier else Modifier.testTag(tag)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (subtitle != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 历史 chip：单击回填并检索，长按删除单条。
 *
 * 长按没有视觉提示，因此给 `combinedClickable` 传 `onLongClickLabel` ——
 * TalkBack 会把"删除这条搜索"读出来，避免只有摸过才知道这个手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryChip(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.search_history_delete),
        ),
    ) {
        Text(
            text = entry.query,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 标签 chip：点一下把标签名填进搜索框（检索链路已覆盖 `tags.name`）。 */
@Composable
private fun TagChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .widthIn(max = 200.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "#$name",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 结果页顶部（M21 决策 3 起）：语义/降级提示 + 被取消的收窄提示 + 结果标签行。
 *
 * 与结果列表分开是为了让 SearchScreen 保持在文件行数限制内，也让"结果页头部"
 * 的三种提示在同一处维护，避免语义提示与标签提示各自分支、口径不一。
 */
@Composable
fun SearchResultsHeader(
    semanticPending: Boolean,
    keywordOnly: Boolean,
    droppedTag: String?,
    tags: List<TagUsage>,
    selectedTag: String?,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 两段式（2026-10-24）：语义在路上 → "排序中"；终态降级 → "仅关键词"。
        // 两者互斥（pending 时 keywordOnly 恒为 false），用 when 而不是两个 if
        when {
            semanticPending -> Text(
                text = stringResource(R.string.search_semantic_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag(SearchTestTags.SEMANTIC_PENDING),
            )

            keywordOnly -> Text(
                text = stringResource(R.string.search_keyword_only_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag(SearchTestTags.NOTICE),
            )
        }

        droppedTag?.let { dropped ->
            Text(
                text = stringResource(R.string.search_result_tag_dropped, dropped),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(SearchTestTags.RESULT_TAGS_DROPPED),
            )
        }

        ResultTagRow(
            tags = tags,
            selectedTag = selectedTag,
            onTagSelected = onTagSelected,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * 结果页的"结果标签"行（M21 决策 3）。
 *
 * 与空闲页标签云的区别写在标题上：这些标签只属于**当前这批结果**，数字是
 * 结果内的命中记录数，不是全库使用量。点 chip 只在当前结果内收窄，不重查库。
 *
 * @param selectedTag 当前收窄的标签；点同一个 chip 会取消收窄（调用方负责切换）
 */
@Composable
fun ResultTagRow(
    tags: List<TagUsage>,
    selectedTag: String?,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SearchTestTags.RESULT_TAGS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.search_result_tags_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = tags, key = { it.name }) { usage ->
                ResultTagChip(
                    usage = usage,
                    selected = usage.name == selectedTag,
                    onClick = { onTagSelected(usage.name) },
                )
            }
        }
    }
}

@Composable
private fun ResultTagChip(
    usage: TagUsage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (selected) {
        stringResource(R.string.search_result_tag_clear_a11y, usage.name)
    } else {
        stringResource(R.string.search_result_tag_a11y, usage.name)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "#${usage.name}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = usage.recordCount.toString(),
                style = MaterialTheme.typography.labelSmall,
            )
            if (selected) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "×", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}