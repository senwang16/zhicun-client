package com.zhicun.app.presentation.archive

import androidx.annotation.StringRes
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag

/**
 * 归档箱页面的状态（M16）。
 *
 * 与收件箱同构（Loading/Empty/Content/Error），但**没有筛选**：
 * 归档箱里只有"已归档"一种记录，按类型或时间再切一遍属于搜索的职责。
 */
sealed interface ArchiveUiState {

    data object Loading : ArchiveUiState

    /** 一条归档记录都没有。 */
    data object Empty : ArchiveUiState

    /**
     * 有归档记录，按归档时间倒序（由 SQL 保证）；搜索中则按创建时间倒序。
     *
     * @param query 当前搜索词；空串 = 未在搜索（列表是全量归档记录）。
     *   搜索中且 [records] 为空时，UI 用它区分"没搜到"（显示换词提示）
     *   与"归档箱本来就是空的"（显示引导语）——两种空不能说同一句话。
     * @param tagsByRecord 每张卡片的标签，结构同收件箱（M11 的做法）。
     */
    data class Content(
        val records: List<Record>,
        val query: String = "",
        val tagsByRecord: Map<String, List<Tag>> = emptyMap(),
    ) : ArchiveUiState

    data class Error(@StringRes val messageRes: Int) : ArchiveUiState
}

/**
 * 归档箱的多选状态。
 *
 * 与收件箱的 `InboxSelection` 形状相同但不复用：两者将来会分化
 * （归档箱可能加"全选"，收件箱可能加"仅可选择未置顶的"），
 * 共用一个类会让一次改动同时影响两个页面。
 */
data class ArchiveSelection(
    val active: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {

    val count: Int get() = selectedIds.size

    /**
     * 恢复与删除按钮共用一条判据：启用条件本来就是同一件事（有没有选东西）。
     * 与收件箱 `InboxSelection.canAct` 对齐，避免将来两处启用条件分叉。
     */
    val canAct: Boolean get() = active && selectedIds.isNotEmpty()
}

/** 一次性操作反馈（恢复成功/失败）。 */
sealed interface ArchiveFeedback {

    data class Restored(val count: Int) : ArchiveFeedback

    data object RestoreFailed : ArchiveFeedback

    /**
     * 删除完成（2026-10-24）。归档箱里也能删 —— 归档是"收起来"，删除才是"不要了"。
     *
     * @param count 清干净的条数
     * @param incomplete 附件文件被占用、暂时没清掉的条数
     */
    data class Deleted(val count: Int, val incomplete: Int) : ArchiveFeedback

    data object DeleteFailed : ArchiveFeedback
}
