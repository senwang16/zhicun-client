package com.zhicun.app.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.presentation.common.RecordCard
import com.zhicun.app.presentation.theme.AppTheme

/** 测试与无障碍定位用的 tag。 */
object SearchTestTags {
    const val FIELD = "search_field"
    const val LIST = "search_list"
    const val IDLE = "search_idle"
    const val EMPTY = "search_empty"
    const val SEARCHING = "search_searching"
    const val NOTICE = "search_keyword_notice"
    const val SEMANTIC_PENDING = "search_semantic_pending"
    const val ERROR = "search_error"
    const val CLEAR = "search_clear"

    // M21 空闲态：最近搜索 + 标签
    const val LANDING = "search_landing"
    const val HISTORY_SECTION = "search_history_section"
    const val HISTORY_CHIPS = "search_history_chips"
    const val TAGS_SECTION = "search_tags_section"
    const val TAG_CHIPS = "search_tag_chips"

    // M21 决策 3：结果页"结果标签"
    const val RESULT_TAGS = "search_result_tags"
    const val RESULT_TAGS_DROPPED = "search_result_tags_dropped"
}

/**
 * 搜索页（有状态壳）。
 *
 * 与收件箱同一套路：这里只管订阅，布局在无状态的 [SearchContent]，
 * UI 测试可以直接喂状态，不必为了测一条提示语去搭 Hilt。
 */
@Composable
fun SearchScreen(
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val tagUsage by viewModel.tagUsage.collectAsStateWithLifecycle()

    SearchContent(
        query = query,
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onRecordClick = { id ->
            // 点开结果 = 这次搜索有用：先记历史再跳转（跳转后本页可能被销毁）
            viewModel.onResultOpened()
            onRecordClick(id)
        },
        history = history,
        tagUsage = tagUsage,
        onSearchSubmitted = viewModel::onSearchSubmitted,
        onHistorySelected = viewModel::onHistorySelected,
        onTagSelected = viewModel::onTagSelected,
        onResultTagSelected = viewModel::onResultTagSelected,
        onDeleteHistory = viewModel::onDeleteHistory,
        onClearHistory = viewModel::onClearHistory,
        modifier = modifier,
    )
}

/**
 * 搜索页（无状态）。
 *
 * 没有返回按钮（2026-10-24 用户反馈）：搜索是底导 tab 之一，左右都是平级页面，
 * "返回"没有语义，返回键由底导切换语义接管。
 *
 * @param query 输入框当前值（受控）
 * @param history 最近搜索（M21）；只有空闲态展示
 * @param tagUsage 标签概览（M21）；只有空闲态展示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    query: String,
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    history: List<SearchHistoryEntry> = emptyList(),
    tagUsage: List<TagUsage> = emptyList(),
    onSearchSubmitted: () -> Unit = {},
    onHistorySelected: (SearchHistoryEntry) -> Unit = {},
    onTagSelected: (String) -> Unit = {},
    onResultTagSelected: (String) -> Unit = {},
    onDeleteHistory: (SearchHistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // 胶囊搜索框（2026-10 改版）：搜索框即顶栏，底色用 surfaceVariant 软底
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SearchTestTags.FIELD),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_placeholder),
                                // 单行 + 省略：占位语比窄屏/大字号的输入框宽，
                                // 不拦住会折成两行，把整个顶栏撑成两层（2026-10-24 用户反馈）
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // 搜索是"边输边搜"，回车只用于收起键盘：交给系统处理即可
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        // 回车仍交给系统收键盘；同时把它当作一次"显式搜索"记入历史（M21）
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                TextButton(
                                    onClick = onClearQuery,
                                    modifier = Modifier.testTag(SearchTestTags.CLEAR),
                                ) {
                                    Text(text = stringResource(R.string.search_clear))
                                }
                            }
                        },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                SearchUiState.Idle -> if (history.isEmpty() && tagUsage.isEmpty()) {
                    SearchHint(
                        title = stringResource(R.string.search_idle_title),
                        hint = stringResource(R.string.search_idle_hint),
                        tag = SearchTestTags.IDLE,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // M21：有历史或标签时，空闲态变成可点的入口
                    SearchLanding(
                        history = history,
                        tags = tagUsage,
                        onHistorySelected = onHistorySelected,
                        onDeleteHistory = onDeleteHistory,
                        onClearHistory = onClearHistory,
                        onTagSelected = onTagSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is SearchUiState.Searching -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(SearchTestTags.SEARCHING),
                )

                is SearchUiState.Empty -> SearchHint(
                    title = stringResource(R.string.search_empty_title),
                    hint = stringResource(R.string.search_empty_hint, uiState.keyword),
                    tag = SearchTestTags.EMPTY,
                    modifier = Modifier.align(Alignment.Center),
                )

                is SearchUiState.Error -> Text(
                    text = stringResource(uiState.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
                        .testTag(SearchTestTags.ERROR),
                )

                is SearchUiState.Results -> Column(modifier = Modifier.fillMaxSize()) {
                    // 提示行 + 结果标签行（M21 决策 3）统一在 SearchLanding.kt 的头部组件里维护
                    SearchResultsHeader(
                        semanticPending = uiState.semanticPending,
                        keywordOnly = uiState.keywordOnly,
                        droppedTag = uiState.narrowDroppedTag,
                        tags = uiState.resultTags,
                        selectedTag = uiState.narrowedTag,
                        onTagSelected = onResultTagSelected,
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SearchTestTags.LIST),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = uiState.records, key = { it.id }) { record ->
                            RecordCard(
                                record = record,
                                onClick = { onRecordClick(record.id) },
                                // 命中词高亮（改版）：仅渲染层行为，不改变数据与排序
                                highlight = uiState.keyword,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(
    title: String,
    hint: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, name = "搜索 · 未输入")
@Composable
private fun SearchIdlePreview() {
    AppTheme {
        SearchContent(
            query = "",
            uiState = SearchUiState.Idle,
            onQueryChange = {},
            onClearQuery = {},
            onRecordClick = {},
        )
    }
}

@Preview(showBackground = true, name = "搜索 · 空闲（最近搜索 + 标签）")
@Composable
private fun SearchLandingPreview() {
    AppTheme {
        SearchContent(
            query = "",
            uiState = SearchUiState.Idle,
            onQueryChange = {},
            onClearQuery = {},
            onRecordClick = {},
            history = listOf(
                SearchHistoryEntry(queryKey = "陪伴", query = "陪伴", searchedAt = 1_700_000_000_000L),
                SearchHistoryEntry(queryKey = "compose", query = "compose", searchedAt = 1_700_000_100_000L),
            ),
            tagUsage = listOf(
                TagUsage(name = "工作", recordCount = 12),
                TagUsage(name = "学习", recordCount = 8),
                TagUsage(name = "旅行", recordCount = 3),
            ),
        )
    }
}

@Preview(showBackground = true, name = "搜索 · 有结果")
@Composable
private fun SearchResultsPreview() {
    AppTheme {
        SearchContent(
            query = "compose",
            uiState = SearchUiState.Results(
                keyword = "compose",
                records = listOf(
                    Record(
                        id = "1",
                        type = RecordType.WEB_LINK,
                        captureSource = CaptureSource.SYSTEM_SHARE,
                        rawText = "Jetpack Compose 是 Android 的现代声明式 UI 工具包。",
                        sourceUrl = "https://developer.android.com/jetpack/compose",
                        sourceTitle = "Compose 官方文档",
                        sourceApp = "Chrome",
                        createdAt = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L,
                        localStatus = LocalStatus.SAVED,
                        processingStatus = ProcessingStatus.DONE,
                        sensitivityStatus = SensitivityStatus.CLEAR,
                        recordRevision = 1,
                        isDeleted = false,
                    ),
                ),
            ),
            onQueryChange = {},
            onClearQuery = {},
            onRecordClick = {},
        )
    }
}
