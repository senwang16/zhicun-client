package com.zhicun.app.presentation.inbox

import androidx.annotation.StringRes
import com.zhicun.app.domain.model.DateGroups
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.usecase.CreateCollectionResult
import java.time.LocalDate

/**
 * 收件箱的筛选条件（M16）。
 *
 * 只有两档而不是"按类型/按时间/按标签"若干维度的组合：收件箱是"最近存了什么"的一览，
 * 真正的多维检索已经由 M10 全文搜索与 M12 语义检索承担。这里多一个维度就是多一组回归面。
 */
enum class InboxFilter {

    /** 全部未归档记录（默认）。 */
    ALL,

    /** 只看收藏过的。 */
    FAVORITE,
}

/**
 * 收件箱的"选中"状态（M16 批量归档用）。
 *
 * 独立于 [InboxUiState] 的两个理由：
 * 1. 勾选一条记录不该让 `uiState` 的 `combine` 重新发射 —— 那会让整个列表重建，
 *    而勾选只影响一张卡片的边框。
 * 2. 选中是纯界面态，屏幕旋转后重建丢失是可接受的（Navigation 会重建 ViewModel，
 *    但真实场景里旋转不重建 ViewModel，所以其实也丢不了）。
 *
 * @param active 是否处于选择模式。为 true 时点击卡片是"勾选"而不是"打开详情"。
 * @param selectedIds 已勾选的 recordId。
 */
data class InboxSelection(
    val active: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {

    val count: Int get() = selectedIds.size

    /**
     * 有选中项才能点顶栏的「收藏」「归档」。
     *
     * 两个按钮共用一条判据：启用条件本来就是同一件事（有没有选东西）。
     * 此前只有 `canArchive`，加收藏时若再写一个 `canFavorite`，两处迟早有一处忘了跟着改，
     * 结果是"按钮亮着但点了什么都不发生"。
     */
    val canAct: Boolean get() = active && selectedIds.isNotEmpty()

    /**
     * 是否够格合并为合集（M20）。
     *
     * 少于两条时合并没有意义（合集就是"把几条放在一起"）。类型是否允许不在这里判：
     * 选中项里可能含合集本身，判据在 `CreateCollectionUseCase` 里（嵌套拒绝），
     * 这里只回答"数量够不够"——两处各写一份迟早走样。
     */
    val canMerge: Boolean get() = active && selectedIds.size >= MIN_MERGE_MEMBERS

    companion object {
        /** 合集至少两条成员。与 `CreateCollectionUseCase.MIN_MEMBERS` 同值。 */
        const val MIN_MERGE_MEMBERS = 2
    }
}

/**
 * 一次性的操作反馈（归档 / 收藏的成功与失败）。
 *
 * 用"一次性事件"而不是把消息塞进 [InboxUiState]：后者会在配置变更后重新显示一遍，
 * 用户会看到一条已经处理过的提示。UI 消费后必须调 `onFeedbackShown()` 清掉。
 */
sealed interface InboxFeedback {

    /** 成功归档了 [count] 条。 */
    data class Archived(val count: Int) : InboxFeedback

    /** 归档失败。不区分原因：对用户来说"没成功、再试一次"就够。 */
    data object ArchiveFailed : InboxFeedback

    /** 成功收藏了 [count] 条（2026-10-24）。 */
    data class Favorited(val count: Int) : InboxFeedback

    /** 收藏失败。与 [ArchiveFailed] 分开，用户才知道是哪个动作没成。 */
    data object FavoriteFailed : InboxFeedback

    /**
     * 成功取消收藏了 [count] 条（2026-10-24）。
     *
     * 只在收藏筛选视图的整理模式出现：用户正看着收藏列表，
     * 此时动作是"移出收藏"而不是"加进收藏"——反馈必须跟动作一致。
     */
    data class Unfavorited(val count: Int) : InboxFeedback

    /**
     * 删除完成（2026-10-24）。
     *
     * @param count 文件与数据库行都清干净的条数
     * @param incomplete 记录已不可见、但附件文件正被占用没删掉的条数
     *   （磁盘暂时没释放，启动或设置页清理会再试一次）
     */
    data class Deleted(val count: Int, val incomplete: Int) : InboxFeedback

    /**
     * 删除整体失败（数据库写入/清理抛异常）。与"部分没清干净"分开：
     * 前者是"什么都没删成"，后者是"删了但空间没释放"，用户的下一步动作完全不同。
     */
    data object DeleteFailed : InboxFeedback

    /**
     * 合集创建成功（M20）。
     *
     * 带 `collectionId` 是为了让界面直接跳进新合集：创建动作的下一步几乎必然是
     * "看看合成了什么"。不带的话用户要自己在列表里找到那条新记录。
     */
    data class CollectionCreated(
        val collectionId: String,
        val memberCount: Int,
    ) : InboxFeedback

    /**
     * 合集创建被拒绝（M20）。
     *
     * 拒绝原因来自 [CreateCollectionResult.Reason]，文案由表现层翻译 ——
     * domain 层不引 Android 资源。
     */
    data class CollectionRejected(
        val reason: CreateCollectionResult.Reason,
    ) : InboxFeedback

    /** 合集创建失败（写库/调度抛异常）。与"被规则拒绝"分开，下一步动作不同。 */
    data object CollectionCreateFailed : InboxFeedback
}

/**
 * 收件箱列表的一行（M19：按天分组后，LazyColumn 里不再只有记录卡）。
 *
 * key 必须全列表唯一：组头用 `"header:"` 前缀 + 日期，与 recordId 天然不冲突
 * （recordId 是 `rec_` 前缀的 UUID）。
 */
sealed interface InboxListItem {

    val key: String

    /** 「置顶」组头：置顶记录单独成组固定最前，不打断下面的日期连续性。不可折叠。 */
    data class PinnedHeader(val count: Int) : InboxListItem {
        override val key: String get() = PINNED_KEY

        companion object {
            const val PINNED_KEY = "header:pinned"
        }
    }

    /**
     * 日期组头。折叠时该天只留这一行；**日期筛选期间恒为展开**
     * （用户刚点名要看这天，再给他一个折叠头是自我否定）。
     */
    data class DayHeader(
        val date: LocalDate,
        val count: Int,
        val collapsed: Boolean,
    ) : InboxListItem {
        override val key: String get() = "header:$date"
    }

    /** 一张记录卡。包一层而不是直接用 [Record]：key 的出处要收敛在一个地方。 */
    data class RecordEntry(val record: Record) : InboxListItem {
        override val key: String get() = record.id
    }
}

/**
 * 收件箱页面的完整状态。
 *
 * 用 sealed interface 而不是 `data class(records, isLoading, error)` 三件套：
 * "加载中"和"空列表"在 UI 上完全不同（前者骨架屏/进度，后者引导文案），
 * 布尔组合很容易出现 `isLoading=false && records=empty && error!=null` 这种自相矛盾的状态。
 */
sealed interface InboxUiState {

    /** 首帧：数据尚未到达（Flow 首次发射之前）。 */
    data object Loading : InboxUiState

    /** 库里确实一条记录都没有 —— 需要引导用户"怎么存第一条"。 */
    data object Empty : InboxUiState

    /**
     * 有数据。列表按"置顶优先、再按 created_at 倒序"（由 SQL 保证）。
     *
     * **筛选之后为空是 Content，不是 Empty**：`records` 为空而 [inboxTotal] 不为空时，
     * 界面要说的是"没有收藏的记录"或"这天没有记录"，而不是"你还没存过东西"。
     * 两者的引导动作完全不同。
     *
     * @param records 当前筛选（收藏 + 日期）下**实际展示**的记录，SQL 顺序。
     * @param tagsByRecord 每张卡片要显示的标签（M11）。单独一张表查回来再按 recordId 归并，
     *   而不是给每条记录塞一个 `tags` 字段 —— 后者会让每张卡片各自查库（N+1）。
     * @param filter 当前筛选条件（M16）。
     * @param inboxTotal 收件箱里共有多少条（不受任何筛选影响），用于区分"真的没有"与"筛掉了"。
     * @param collapsedDays 被折叠的日期（M19）；日期筛选期间对新列表无效（见 [InboxListItem.DayHeader]）。
     * @param selectedDate 日历点选的日期筛选（M19）；null = 未筛选。会话级，不持久化。
     * @param dayCounts 每天有多少条记录（M19，日历圆点的数据源）。口径与列表一致：
     *   **收藏筛选参与、日期筛选不参与**（圆点要能导航到筛选前就存在的天）、含置顶记录
     *   （置顶记录也是"那天存的"，点过去时它落回那天的组里）。
     */
    data class Content(
        val records: List<Record>,
        val tagsByRecord: Map<String, List<Tag>> = emptyMap(),
        val filter: InboxFilter = InboxFilter.ALL,
        val inboxTotal: Int = records.size,
        val collapsedDays: Set<LocalDate> = emptySet(),
        val selectedDate: LocalDate? = null,
        val dayCounts: Map<LocalDate, Int> = emptyMap(),
    ) : InboxUiState {

        /** 筛选把记录全筛掉了（库里有数据，但当前条件下没有）。 */
        val filteredToEmpty: Boolean get() = records.isEmpty() && inboxTotal > 0

        /**
         * LazyColumn 的真实行序列（M19）。纯函数派生而不是 ViewModel 预拼好：
         * 预览与 UI 测试直接构造 [Content] 就能拿到完整列表，不必复刻一份分组逻辑。
         */
        val listItems: List<InboxListItem>
            get() {
                if (selectedDate != null) {
                    // 时间视图：只看这一天，置顶组不出现（点某天要看的是"那天的全貌"，
                    // 别的天置顶的记录混进来就成了噪音）。恒展开。
                    val dayRecords = records.filter {
                        DateGroups.toLocalDate(it.createdAt) == selectedDate
                    }
                    if (dayRecords.isEmpty()) return emptyList()
                    return listOf(InboxListItem.DayHeader(selectedDate, dayRecords.size, collapsed = false)) +
                        dayRecords.map { InboxListItem.RecordEntry(it) }
                }
                return buildList {
                    val pinned = records.filter { it.isPinned }
                    if (pinned.isNotEmpty()) {
                        add(InboxListItem.PinnedHeader(pinned.size))
                        pinned.forEach { add(InboxListItem.RecordEntry(it)) }
                    }
                    // records 已按 created_at 倒序（SQL），LinkedHashMap 保序 ⇒ 日期组天然倒序
                    val byDay = LinkedHashMap<LocalDate, MutableList<Record>>()
                    records.filter { !it.isPinned }.forEach { record ->
                        byDay.getOrPut(DateGroups.toLocalDate(record.createdAt)) { mutableListOf() }
                            .add(record)
                    }
                    byDay.forEach { (day, dayRecords) ->
                        add(InboxListItem.DayHeader(day, dayRecords.size, collapsed = day in collapsedDays))
                        if (day !in collapsedDays) {
                            dayRecords.forEach { add(InboxListItem.RecordEntry(it)) }
                        }
                    }
                }
            }
    }

    /**
     * 读取失败（数据库损坏、磁盘 IO 错误等）。
     *
     * 只带一个字符串资源 id，不带异常文本：面向用户的文案不该出现
     * `SQLiteDatabaseCorruptException` 这类内容；原始异常走 Log（见 ViewModel）。
     */
    data class Error(@StringRes val messageRes: Int) : InboxUiState
}
