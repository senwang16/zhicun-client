package com.zhicun.app.presentation.archive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.search.SearchQuery
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.GetArchivedRecordsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 归档箱 ViewModel（M16）。
 *
 * 把 [GetArchivedRecordsUseCase] 的 Flow 摊平成状态，外加"恢复"与"删除"动作。
 * 恢复走的就是收件箱归档的逆操作（同一份 `ArchiveRecordsUseCase.restore`），
 * 所以两个页面不会出现"归档了却恢复不了"的不对称。
 *
 * ## 归档箱内搜索（2026-10-24）
 * 归档记录退出了主搜索与相关推荐（用户裁决），但归档箱必须有**自己的**搜索：
 * 存了几百条之后，没有搜索就只能一屏屏翻。搜索词走 [RecordRepository.searchArchivedBySubstring]，
 * `flatMapLatest` 切换"全量流"与"搜索流"；两条都是 Room Flow，恢复/删除后结果自动刷新。
 *
 * 选中与一次性提示是独立 StateFlow —— 勾选一条记录不该让 `uiState` 重新发射，
 * 理由同 `InboxViewModel`。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    getArchived: GetArchivedRecordsUseCase,
    private val recordRepository: RecordRepository,
    tagRepository: TagRepository,
    private val archiveRecords: ArchiveRecordsUseCase,
    private val deleteRecords: DeleteRecordsUseCase,
) : ViewModel() {

    private val _selection = MutableStateFlow(ArchiveSelection())

    /** 多选状态（恢复用）。 */
    val selection: StateFlow<ArchiveSelection> = _selection.asStateFlow()

    private val _feedback = MutableStateFlow<ArchiveFeedback?>(null)

    /** 一次性操作反馈。UI 消费完必须调 [onFeedbackShown]。 */
    val feedback: StateFlow<ArchiveFeedback?> = _feedback.asStateFlow()

    private val _query = MutableStateFlow("")

    /** 归档箱内搜索词（2026-10-24）。空串 = 未在搜索。 */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * 记录流：搜索词为空时是全量归档流，非空时是搜索结果流。
     *
     * 用 `flatMapLatest` 而不是把 query 传进查询再在内存过滤：归档记录的正文
     * （转写/OCR）在派生表里，内存里没有 —— 内存过滤会让"录音搜转写词"永远搜不到。
     */
    private val recordsFlow: Flow<List<Record>> = _query
        .flatMapLatest { raw ->
            val normalized = SearchQuery.normalize(raw)
            if (normalized == null) {
                getArchived()
            } else {
                recordRepository.searchArchivedBySubstring(normalized, SEARCH_LIMIT)
            }
        }

    /**
     * 标签与记录一起取（归档记录同样可能带标签，恢复前用户要能认出它是什么）。
     *
     * 标签流失败不影响列表（`catch` 里发空表）—— 与收件箱同一处理：
     * 标签只是锦上添花，不该让整个归档箱变成错误页。
     */
    val uiState: StateFlow<ArchiveUiState> = combine(
        recordsFlow,
        tagRepository.observeAllWithRecord().catch { throwable ->
            Log.e(TAG, "归档箱标签流读取失败，卡片暂不显示标签", throwable)
            emit(emptyList())
        },
        _query,
    ) { records, recordTags, query ->
        val normalized = SearchQuery.normalize(query)
        if (records.isEmpty() && normalized == null) {
            ArchiveUiState.Empty
        } else {
            val visibleIds = records.mapTo(HashSet()) { it.id }
            ArchiveUiState.Content(
                records = records,
                query = if (normalized == null) "" else query.trim(),
                tagsByRecord = recordTags
                    .filter { it.recordId in visibleIds }
                    .groupBy({ it.recordId }, { it.tag })
                    .mapValues { (_, tags) -> tags.distinctBy { it.id } },
            )
        }
    }
        .catch { throwable ->
            Log.e(TAG, "归档箱数据流读取失败", throwable)
            emit(ArchiveUiState.Error(R.string.archive_error_load))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ArchiveUiState.Loading,
        )

    /** 进入选择模式并勾选一条（长按卡片）。已在选择模式时等同于 [toggleSelected]。 */
    fun startSelection(recordId: String) {
        if (_selection.value.active) {
            toggleSelected(recordId)
        } else {
            _selection.value = ArchiveSelection(active = true, selectedIds = setOf(recordId))
        }
    }

    /** 进入选择模式但不预选（顶栏"整理"）。 */
    fun enterSelectionMode() {
        _selection.value = ArchiveSelection(active = true)
    }

    fun toggleSelected(recordId: String) {
        _selection.update { current ->
            if (!current.active) return@update current
            val next = if (recordId in current.selectedIds) {
                current.selectedIds - recordId
            } else {
                current.selectedIds + recordId
            }
            current.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _selection.value = ArchiveSelection()
    }

    /** 更新归档箱搜索词。与主搜索同一归一化，空词回到全量列表。 */
    fun onQueryChange(raw: String) {
        _query.value = raw
    }

    /** 清空搜索词并回到全量列表（搜索框的"清除"）。 */
    fun clearQuery() {
        _query.value = ""
    }

    /**
     * 把勾选的记录恢复回收件箱。
     *
     * 成功后退出选择模式：这些记录已经从归档箱消失，继续挂着"已选 N 条"会让人以为没生效。
     */
    fun restoreSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val restored = archiveRecords.restore(targets)
                _feedback.value = ArchiveFeedback.Restored(restored)
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量恢复失败 count=${targets.size}", e)
                _feedback.value = ArchiveFeedback.RestoreFailed
            }
        }
    }

    /**
     * 删除勾选的归档记录（2026-10-24）。
     *
     * 与收件箱共用 [DeleteRecordsUseCase]：归档记录同样有附件和派生内容，
     * 删除规则必须一模一样，不能因为从哪个页面进来而不同。
     */
    fun deleteSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val report = deleteRecords(targets)
                _feedback.value = ArchiveFeedback.Deleted(
                    count = report.deleted,
                    incomplete = report.incomplete,
                )
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量删除失败 count=${targets.size}", e)
                _feedback.value = ArchiveFeedback.DeleteFailed
            }
        }
    }

    fun onFeedbackShown() {
        _feedback.value = null
    }

    private companion object {
        const val TAG = "ArchiveViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * 归档箱内搜索的结果上限。与主搜索一个量级：这是"找回一条"的场景，
         * 不是"浏览全部"——想看全部就清空搜索词。
         */
        const val SEARCH_LIMIT = 50
    }
}
