package com.zhicun.app.presentation.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.ResultTagIndex
import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.domain.search.SearchQuery
import com.zhicun.app.domain.usecase.BuildResultTagIndexUseCase
import com.zhicun.app.domain.usecase.ClearSearchHistoryUseCase
import com.zhicun.app.domain.usecase.DeleteSearchHistoryUseCase
import com.zhicun.app.domain.usecase.ObserveSearchHistoryUseCase
import com.zhicun.app.domain.usecase.ObserveTagUsageUseCase
import com.zhicun.app.domain.usecase.RecordSearchHistoryUseCase
import com.zhicun.app.domain.usecase.SearchOutcome
import com.zhicun.app.domain.usecase.SearchRecordsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索页 ViewModel（M10）。
 *
 * ## 为什么用"手写防抖"而不是 `debounce` 操作符
 * 输入 → 结果的链路只有一个入口（[onQueryChange]），手写"取消上一个 Job + `delay`"
 * 能把**取消语义写在眼前**：新输入立即取消上一次检索，不会出现"旧查询慢、新查询快，
 * 旧结果后到覆盖新结果"的乱序。同时它在单测里可以用虚拟时间精确推进
 * （`advanceTimeBy(DEBOUNCE_MS + 1)`），不需要为了测防抖去订阅 Flow。
 *
 * ## 为什么查询串与结果分成两个 StateFlow
 * 输入框的值由 [query] 单向驱动（受控组件），结果由 [uiState] 描述。
 * 合成一个状态的话，每次状态流转都要把输入框文本一起复制一遍，
 * 一旦某条分支忘了带，输入框就会被"回滚"成旧文本。
 *
 * ## 两段式结果（2026-10-24 搜索卡顿整改）
 * 语义路要等一次 2~3.5 秒的云端往返，此前界面跟着它一起等。现在防抖结束后
 * 先发布**关键词结果**（本地毫秒级，`semanticPending=true`），语义融合到达后
 * 再替换一次（[SearchRecordsUseCase.searchStaged] 两次发射）。两次发布在
 * 同一个 Job 里：输入变化即整体取消，旧查询的语义结果不可能覆盖新查询。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRecords: SearchRecordsUseCase,
    private val recordSearchHistory: RecordSearchHistoryUseCase,
    private val observeSearchHistory: ObserveSearchHistoryUseCase,
    private val observeTagUsage: ObserveTagUsageUseCase,
    private val deleteSearchHistory: DeleteSearchHistoryUseCase,
    private val clearSearchHistory: ClearSearchHistoryUseCase,
    private val buildResultTagIndex: BuildResultTagIndexUseCase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /**
     * 最近搜索（M21）：空闲态展示，按最近搜索时间倒序，默认 10 条。
     *
     * `WhileSubscribed(5s)`：页面离开 5 秒后停止收集、释放查询；旋转/短暂切后台不重查。
     */
    val history: StateFlow<List<SearchHistoryEntry>> = observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), emptyList())

    /** 标签概览（M21）：只统计未删/未归档记录，同名 AI/用户标签已在 SQL 侧合并。 */
    val tagUsage: StateFlow<List<TagUsage>> = observeTagUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), emptyList())

    private var searchJob: Job? = null

    // ==== 结果页"结果标签"（M21 决策 3）====
    // 完整结果集与标签索引必须留在内存里：收窄/恢复是纯 UI 过滤语义，不能为此重查库
    private var latestRecords: List<Record> = emptyList()
    private var latestTagIndex: ResultTagIndex = ResultTagIndex.EMPTY

    /** 当前收窄用的标签名；null = 展示完整结果。 */
    private var narrowedResultTag: String? = null

    /** 语义段把收窄标签挤出结果集时记录下来的标签名，用于界面提示一次。 */
    private var droppedResultTag: String? = null

    /** 收窄被取消提示的自动消失计时（审核 P3-4：不能一直挂在结果页顶部）。 */
    private var dropNoticeJob: Job? = null

    fun onQueryChange(raw: String) {
        // 超出上限的部分不进入输入框：否则"看到的词"与"实际搜的词"不一致，
        // 用户会以为后半段也被搜了（SearchQuery.normalize 也会截断，这里只是显式化）。
        val text = raw.take(SearchQuery.MAX_LENGTH)
        if (text == _query.value) return
        _query.value = text

        // 新词就是新结果集：收窄状态与标签索引必须一起丢掉，否则会出现
        // "用上一个查询的标签在过滤新结果"的错位
        resetResultTags()

        searchJob?.cancel()
        searchJob = null

        val keyword = SearchQuery.normalize(text)
        if (keyword == null) {
            _uiState.value = SearchUiState.Idle
            return
        }

        _uiState.value = SearchUiState.Searching(keyword)
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MILLIS)
            runStagedSearch(keyword)
        }
    }

    fun onClearQuery() {
        onQueryChange("")
    }

    // ==== 搜索历史（M21）====

    /**
     * 键盘「搜索」键：唯一一个"用户明确说就搜这个"的信号。
     *
     * 只记显式动作（回车 / 点开结果 / 点 chip）是刻意的：搜索是边打边搜，
     * 每次防抖都记会把 `com`/`comp`/`compo` 写满历史。
     */
    fun onSearchSubmitted() {
        record(_query.value)
    }

    /** 点开一条结果：最可靠的"这次搜索有用"信号。 */
    fun onResultOpened() {
        record(_query.value)
    }

    /** 点历史 chip：回填并立即检索一次，同时把这条顶到最前。 */
    fun onHistorySelected(entry: SearchHistoryEntry) {
        onQueryChange(entry.query)
        record(entry.query)
    }

    /** 点标签 chip：标签名就是一次关键词查询（检索链路已覆盖 tags.name）。 */
    fun onTagSelected(name: String) {
        onQueryChange(name)
        record(name)
    }

    // ==== 结果页"结果标签"（M21 决策 3）====

    /**
     * 点结果页的标签 chip：在当前结果内收窄；再点同一个 chip 恢复完整结果。
     *
     * 收窄是纯内存过滤，不重查库、不写搜索历史（它是筛选动作，不是一次新搜索）。
     */
    fun onResultTagSelected(tag: String) {
        if (tag.isBlank()) return
        val current = _uiState.value as? SearchUiState.Results ?: return
        applyResultTag(if (current.narrowedTag == tag) null else tag)
    }

    /** 切换/恢复收窄（点 chip 时由 [onResultTagSelected] 调用）。 */

    private fun applyResultTag(tag: String?) {
        if (_uiState.value !is SearchUiState.Results) return
        if (latestRecords.isEmpty()) return

        val display = if (tag == null) {
            latestRecords
        } else {
            val ids = latestTagIndex.recordIdsFor(tag)
            latestRecords.filter { it.id in ids }
        }

        narrowedResultTag = tag
        droppedResultTag = null
        dropNoticeJob?.cancel()
        _uiState.update { state ->
            if (state is SearchUiState.Results) {
                state.copy(
                    records = display,
                    resultTags = latestTagIndex.tags,
                    narrowedTag = tag,
                    narrowDroppedTag = null,
                )
            } else {
                state
            }
        }
    }

    /** 换词/清空/新结果集时的统一清理。 */
    private fun resetResultTags() {
        latestRecords = emptyList()
        latestTagIndex = ResultTagIndex.EMPTY
        narrowedResultTag = null
        droppedResultTag = null
        dropNoticeJob?.cancel()
        dropNoticeJob = null
    }

    /** 提示"已恢复完整结果"只挂 4 秒：它是状态变化的一次性说明，不是常驻文案。 */
    private fun scheduleDropNoticeClear() {
        dropNoticeJob?.cancel()
        dropNoticeJob = viewModelScope.launch {
            delay(DROP_NOTICE_MILLIS)
            droppedResultTag = null
            _uiState.update { state ->
                if (state is SearchUiState.Results && state.narrowDroppedTag != null) {
                    state.copy(narrowDroppedTag = null)
                } else {
                    state
                }
            }
        }
    }

    /** 长按删除单条：按去重键删除，不影响其它历史。 */
    fun onDeleteHistory(entry: SearchHistoryEntry) {
        viewModelScope.launch { deleteSearchHistory(entry.queryKey) }
    }

    /** 清空全部：物理删除，不弹确认（历史本身可重建，代价低）。 */
    fun onClearHistory() {
        viewModelScope.launch { clearSearchHistory() }
    }

    private fun record(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch { recordSearchHistory(raw) }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        searchJob = null
        dropNoticeJob?.cancel()
        dropNoticeJob = null
    }

    /**
     * 收两段发射并发布到界面。第一次算"语义还在路上"（[SearchUiState.Results.semanticPending]），
     * 第二次是终态。收集被取消（输入变了）时未完成的第二段自然作废。
     */
    private suspend fun runStagedSearch(keyword: String) {
        var semanticPending = true
        try {
            searchRecords.searchStaged(keyword).collect { outcome ->
                publish(outcome, keyword, semanticPending = semanticPending)
                semanticPending = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 搜索词属用户内容，日志只留长度（2026-11-05 移植海外轨隐私修复）
            val keywordLength = keyword.length
            Log.e(TAG, "检索失败 len=$keywordLength", e)
            _uiState.value = SearchUiState.Error(R.string.search_error)
        }
    }

    private suspend fun publish(outcome: SearchOutcome, keyword: String, semanticPending: Boolean) {
        // 语义检索没参与时这里留一条可查的痕迹：界面只有一句"关键词结果"，
        // 排障时需要知道到底是"没配网关"还是"库里还没有向量"。
        // 同样不记录搜索词本身，只留长度。
        val keywordLength = keyword.length
        outcome.degradeReason?.let { Log.i(TAG, "本次检索仅关键词 len=$keywordLength 原因=$it") }

        if (outcome.records.isEmpty()) {
            // 第一段就空也照发：宁可先说"没找到"（语义命中后立刻翻转为结果），
            // 也不要让无关键词命中的查询跟着网络一起转圈
            resetResultTags()
            _uiState.value = SearchUiState.Empty(keyword)
            return
        }

        // 结果标签：只在这批结果上聚合；条数/标签数不足时用例返回 EMPTY（不占空间）。
        // 两段式检索的第二段通常只是同一批记录重排（语义只改顺序/追加），集合未变时
        // 直接复用第一段的索引，避免再跑一次 `IN (≤50 ids)`（审核 P3-6）。
        val sameRecordSet = outcome.records.size == latestRecords.size &&
            outcome.records.map { it.id }.toSet() == latestRecords.map { it.id }.toSet()
        val index = if (sameRecordSet) latestTagIndex else buildResultTagIndex(outcome.records)
        latestRecords = outcome.records
        latestTagIndex = index

        // 收窄在语义段到达后必须重算：结果集可能变了，标签也可能被挤出 50 条上限
        var selected = narrowedResultTag
        var display = outcome.records
        if (selected != null) {
            val ids = index.recordIdsFor(selected)
            if (ids.isEmpty()) {
                droppedResultTag = selected
                selected = null
                narrowedResultTag = null
                // 提示会一直挂在结果页顶部，必须给它一个消失时机（审核 P3-4）
                scheduleDropNoticeClear()
            } else {
                display = outcome.records.filter { it.id in ids }
                droppedResultTag = null
            }
        }

        _uiState.value = SearchUiState.Results(
            keyword = keyword,
            records = display,
            // pending 期间语义还在路上、不算降级；终态才如实标注 keywordOnly
            keywordOnly = !semanticPending && outcome.keywordOnly,
            semanticPending = semanticPending,
            resultTags = index.tags,
            narrowedTag = selected,
            narrowDroppedTag = droppedResultTag,
        )
    }

    private companion object {
        const val TAG = "SearchViewModel"

        /** 输入停顿多久才真正查库：太短会每次按键都扫一遍 LIKE，太长手感发黏。 */
        const val DEBOUNCE_MILLIS = 250L

        /** history/tags 流在页面离开后保留订阅的时长：覆盖旋转与短暂切后台。 */
        const val FLOW_STOP_TIMEOUT_MS = 5_000L

        /** "已恢复完整结果"提示的停留时长（审核 P3-4）。 */
        const val DROP_NOTICE_MILLIS = 4_000L
    }
}
