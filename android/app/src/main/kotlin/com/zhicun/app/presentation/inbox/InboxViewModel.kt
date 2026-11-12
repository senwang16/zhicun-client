package com.zhicun.app.presentation.inbox

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.inbox.InboxListSettings
import com.zhicun.app.domain.model.DateGroups
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.CreateCollectionResult
import com.zhicun.app.domain.usecase.CreateCollectionUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.FavoriteRecordsUseCase
import com.zhicun.app.domain.usecase.GetRecordsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收件箱 ViewModel（AGENTS.md §2.3：只经 Repository/UseCase 取数，不碰 DAO）。
 *
 * 数据流是"被动"的：Room 的 Flow 一变，`observeAll` 就重新发射，UI 自动刷新 ——
 * M4 分享进来一条记录后，收件箱无需手动刷新即可看到。
 *
 * `WhileSubscribed(5s)`：屏幕旋转/短暂切后台时保留上游订阅 5 秒，避免重建时重新查库；
 * 超过 5 秒没有订阅者则停掉，符合"后台不耗电"的要求（AGENTS.md §2.2 的同一出发点）。
 *
 * ## M11：标签与记录合成一个状态
 * 两条 Flow 各自独立（记录一条、标签一条），在这里 `combine` 成 `recordId → tags`：
 * 后台刚写完 AI 标签时，卡片会自己更新，不需要进来一次就重查。
 * 标签流失败**不**影响列表展示（`catch` 里发空表），标签只是锦上添花。
 *
 * ## M16：筛选与选中
 * 筛选（[filter]）参与 `uiState` 的合成 —— 它决定列表显示哪些行，属于数据的一部分。
 * 选中（[selection]）与一次性提示（[feedback]）是独立 StateFlow —— 勾选一条记录
 * 不该让 `uiState` 重新发射（那会把整列卡片重建一遍，勾选动画会抖）。
 *
 * ## M19：折叠与日期筛选
 * 折叠（`_collapsedDays`）参与 `uiState` —— 它决定列表渲染哪些行，同属数据；
 * 持久化到 [InboxListSettings]。日期筛选（`_selectedDate`）也参与合成但**不持久化**：
 * 它是一次性意图，重启后回默认视图。
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    getRecords: GetRecordsUseCase,
    tagRepository: TagRepository,
    private val archiveRecords: ArchiveRecordsUseCase,
    private val favoriteRecords: FavoriteRecordsUseCase,
    private val deleteRecords: DeleteRecordsUseCase,
    private val createCollection: CreateCollectionUseCase,
    private val listSettings: InboxListSettings,
) : ViewModel() {

    private val _filter = MutableStateFlow(InboxFilter.ALL)

    /** 当前筛选条件（M16）。 */
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    private val _selection = MutableStateFlow(InboxSelection())

    /** 多选状态（M16）。 */
    val selection: StateFlow<InboxSelection> = _selection.asStateFlow()

    private val _feedback = MutableStateFlow<InboxFeedback?>(null)

    /** 一次性操作反馈。UI 消费完必须调 [onFeedbackShown]，否则旋转屏幕会重放。 */
    val feedback: StateFlow<InboxFeedback?> = _feedback.asStateFlow()

    /**
     * 被折叠的日期集合（M19）。初值来自持久化偏好；之后的每次变更同步写回
     * （见 [persistCollapsed]）。
     */
    private val _collapsedDays = MutableStateFlow(listSettings.collapsedDays)

    /** 日历点选的日期筛选（M19）；null = 未筛选。会话级，不持久化。 */
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)

    /**
     * 最近一次数据流里出现过的“天”（M19）。
     *
     * “全部折叠”要有东西可折、“保存时裁剪失效日期”要有依据 —— 两者都需要
     * 在 combine 的纯变换之外留一分当前全集。在 combine lambda 里赋值：
     * 它运行在 stateIn 的收集协程里，永远先于任何用户动作可观察。
     */
    private var knownDays: Set<LocalDate> = emptySet()

    val uiState: StateFlow<InboxUiState> = combine(
        getRecords(),
        tagRepository.observeAllWithRecord().catch { throwable ->
            Log.e(TAG, "标签流读取失败，卡片暂不显示标签", throwable)
            emit(emptyList())
        },
        _filter,
        _collapsedDays,
        _selectedDate,
    ) { records, recordTags, filter, collapsedDays, selectedDate ->
        knownDays = records.mapTo(LinkedHashSet()) { DateGroups.toLocalDate(it.createdAt) }
        if (records.isEmpty()) {
            // 库里真的一条都没有：此时筛选条件没有意义，仍按"还没存过东西"引导
            InboxUiState.Empty
        } else {
            // 收藏筛选取决于用户意图，参与列表与日历圆点；
            // 日期筛选是会话态视图切换，只裁列表、不裁圆点（圆点要能导航到"还没在看的那些天"）
            val favoriteVisible = when (filter) {
                InboxFilter.ALL -> records
                InboxFilter.FAVORITE -> records.filter { it.isFavorite }
            }
            val visible = selectedDate
                ?.let { date -> favoriteVisible.filter { DateGroups.toLocalDate(it.createdAt) == date } }
                ?: favoriteVisible
            InboxUiState.Content(
                records = visible,
                // 只保留可见记录的标签：筛选后另一半记录的标签没有展示机会，
                // 留在地图里只是白占内存（记录多起来时这张表不小）
                tagsByRecord = recordTags
                    .filter { it.recordId in visible.mapTo(HashSet()) { it.id } }
                    .groupBy({ it.recordId }, { it.tag })
                    .mapValues { (_, tags) -> tags.distinctBy { it.id } },
                filter = filter,
                inboxTotal = records.size,
                collapsedDays = collapsedDays,
                selectedDate = selectedDate,
                dayCounts = favoriteVisible.groupingBy { DateGroups.toLocalDate(it.createdAt) }.eachCount(),
            )
        }
    }
        .catch { throwable ->
            // 空 catch 是明令禁止的（AGENTS.md §8）：先落日志，再给用户一句人话。
            Log.e(TAG, "收件箱数据流读取失败", throwable)
            emit(InboxUiState.Error(R.string.inbox_error_load))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = InboxUiState.Loading,
        )

    /** 在"全部"与"只看收藏"之间切换（M16）。 */
    fun toggleFilter() {
        _filter.update { current ->
            when (current) {
                InboxFilter.ALL -> InboxFilter.FAVORITE
                InboxFilter.FAVORITE -> InboxFilter.ALL
            }
        }
        // 切筛选后旧的勾选可能已经不可见，留着会让人以为"明明选了 3 条却归档了 1 条"
        clearSelection()
    }

    // ------------------------------------------------------------ M19：折叠与日期筛选

    /**
     * 折叠/展开某一天（点日期组头触发）。
     *
     * 日期筛选期间忽略：此刻组头恒为展开（用户刚点名要看这天），点了折叠
     * 却没有任何可见变化，看起来像坏了；真要折，先清筛选再折。
     */
    fun toggleDayCollapsed(date: LocalDate) {
        if (_selectedDate.value != null) return
        if (date !in knownDays) return
        persistCollapsed(
            if (date in _collapsedDays.value) _collapsedDays.value - date else _collapsedDays.value + date,
        )
    }

    /** 折叠当前所有出现过的天（顶栏菜单"全部折叠"）。 */
    fun collapseAllDays() {
        if (_selectedDate.value != null) return
        persistCollapsed(knownDays)
    }

    /** 展开所有天（顶栏菜单"全部展开"）。 */
    fun expandAllDays() {
        if (_selectedDate.value != null) return
        persistCollapsed(emptySet())
    }

    /**
     * 日历点选某天 / 清除（再点同一天或点 chip 的 ×）。
     * 传 null 即清除筛选回到默认视图。
     */
    fun selectDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    /**
     * 更新折叠集合并落盘。保存前按现存记录的“天”裁剪：
     * 记录删掉之后，它对应的旧折叠日期会一直躺在偏好里，prefs 只增不减。
     */
    private fun persistCollapsed(days: Set<LocalDate>) {
        val effective = days.filter { it in knownDays }.toSet()
        _collapsedDays.value = effective
        listSettings.saveCollapsedDays(effective)
    }

    /**
     * 进入选择模式并勾选 [recordId]（长按卡片触发）。
     *
     * 已经在选择模式里时等同于 [toggleSelected]：长按一条已选中的卡片应该取消它，
     * 而不是"什么都没发生"。
     */
    fun startSelection(recordId: String) {
        if (_selection.value.active) {
            toggleSelected(recordId)
        } else {
            // 统一走 update，避免直接引用赋值在并发回调下丢中间态（AGENTS §2.2）
            _selection.update { InboxSelection(active = true, selectedIds = setOf(recordId)) }
        }
    }

    /** 进入选择模式但不预选任何记录（顶栏"整理"按钮触发）。 */
    fun enterSelectionMode() {
        _selection.update { InboxSelection(active = true) }
    }

    /** 勾选/取消勾选一条记录。不在选择模式时忽略（点击应由调用方导向详情页）。 */
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

    /**
     * 退出选择模式。
     *
     * 不保留 last-selected：再次进入选择模式时用户期待的是"从头开始选"，
     * 保留上一轮的勾选会让人在不知情的情况下多归档几条。
     */
    fun clearSelection() {
        _selection.update { InboxSelection() }
    }

    /**
     * 收藏当前勾选的记录（2026-10-24：整理模式支持批量收藏）。
     *
     * 与 [archiveSelected] 的关键差异：**收藏后不退出选择模式**。
     * 归档会把记录移出列表，留在选择模式里只剩一个空壳；收藏不改变列表内容，
     * 记录还在原位，用户往往接着要归档 —— 此时退出等于逼他重新勾一遍。
     *
     * 失败时也只提示，不清勾选：用户可以立刻再点一次。
     */
    fun favoriteSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val count = favoriteRecords(targets)
                _feedback.value = InboxFeedback.Favorited(count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量收藏失败 count=${targets.size}", e)
                _feedback.value = InboxFeedback.FavoriteFailed
            }
        }
    }

    /**
     * 取消收藏当前勾选的记录（2026-10-24）。
     *
     * 收藏筛选视图里"整理"的动作必须是**移出收藏**：用户正看着收藏列表，
     * 这时给他一个"收藏"按钮，点了没有任何可见变化（记录本来就都是收藏），
     * 看起来像按钮坏了。走 [FavoriteRecordsUseCase.unset] —— 取消收藏与收藏
     * 是同一份数据的两个方向，不另写一份 SQL。
     *
     * 与 [favoriteSelected] 同样**不退出选择模式**：取消收藏后记录还在列表里
     * （只是没了收藏标记），用户往往接着要归档或删除。
     */
    fun unfavoriteSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val count = favoriteRecords.unset(targets)
                _feedback.value = InboxFeedback.Unfavorited(count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量取消收藏失败 count=${targets.size}", e)
                _feedback.value = InboxFeedback.FavoriteFailed
            }
        }
    }

    /**
     * 归档当前勾选的记录（M16）。
     *
     * 成功后立刻退出选择模式：记录已经从列表里消失，让"已选 N 条"继续挂在顶栏
     * 会显得操作没生效。失败则保留勾选，用户可以直接再点一次。
     */
    fun archiveSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val archived = archiveRecords(targets)
                _feedback.value = InboxFeedback.Archived(archived)
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量归档失败 count=${targets.size}", e)
                _feedback.value = InboxFeedback.ArchiveFailed
            }
        }
    }

    /**
     * 删除当前勾选的记录（2026-10-24）。
     *
     * 确认框在 UI 层（[com.zhicun.app.presentation.inbox.InboxContent]）：
     * ViewModel 拿到的已经是"用户点过确认"的意图，这里不再问第二遍。
     *
     * 成功与"失败"都退出选择模式 —— 删除是终态动作，留在选择模式里只剩一个空壳
     * （记录已经不在列表里，勾选项也全失效）。"部分没清干净"也属于删掉了，
     * 只是磁盘晚点回收，所以同样退出。
     */
    fun deleteSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            try {
                val report = deleteRecords(targets)
                _feedback.value = InboxFeedback.Deleted(
                    count = report.deleted,
                    incomplete = report.incomplete,
                )
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量删除失败 count=${targets.size}", e)
                _feedback.value = InboxFeedback.DeleteFailed
            }
        }
    }

    /** 提示已经展示过，清掉，避免配置变更后重放。 */
    fun onFeedbackShown() {
        _feedback.value = null
    }

    /**
     * 把当前勾选的记录合并为一个合集（M20）。
     *
     * 成员顺序、类型校验与敏感并集都在 [CreateCollectionUseCase] 里：界面只负责
     * "把用户勾的这几条交上去"，不自己筛已删除/合集成员 —— 多选状态可能是几秒前的
     * 快照，落库前必须由用例再判一次。
     *
     * 成功后**退出选择模式并携带新合集 id**：紧接着的动作几乎必然是"看看合成了什么"，
     * 让用户自己去列表里找那条新记录没有必要。被拒绝时保留勾选，用户改选后可直接再点。
     */
    fun createCollectionSelected() {
        val targets = _selection.value.selectedIds.toList()
        if (targets.size < InboxSelection.MIN_MERGE_MEMBERS) return
        viewModelScope.launch {
            try {
                when (val result = createCollection(targets)) {
                    is CreateCollectionResult.Created -> {
                        clearSelection()
                        _feedback.value = InboxFeedback.CollectionCreated(
                            collectionId = result.collectionId,
                            memberCount = targets.size,
                        )
                    }

                    is CreateCollectionResult.Rejected ->
                        _feedback.value = InboxFeedback.CollectionRejected(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "创建合集失败 count=${targets.size}", e)
                _feedback.value = InboxFeedback.CollectionCreateFailed
            }
        }
    }

    private companion object {
        const val TAG = "InboxViewModel"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
