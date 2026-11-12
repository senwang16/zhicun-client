package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject

/**
 * 批量收藏 / 取消收藏（2026-10-24：整理模式支持批量收藏）。
 *
 * ## 为什么和归档分成两个用例
 * 两者的**副作用模型**完全不同：归档会把记录移出收件箱（列表少一行），收藏只改一个标记
 * （列表不变）。界面对这两件事的反应必须不一样 —— 归档后要退出选择模式，收藏后不能退
 * （记录还在原地，用户往往接着要归档）。合成一个"整理"用例会让调用方又要传标记又要
 * 判断该不该退出，等于把两份规则塞进一个 isArchived 的布尔值里。
 *
 * ## 为什么调用方传 bool 而不是"切换"
 * 切换语义要求先读每条的当前状态再逐条取反 —— 十条记录里五条已收藏时，"切换"会让
 * 五条变未收藏、五条变已收藏，用户看到的是"点了收藏却有一半掉了星"。
 * 这里一律"设成目标值"，幂等，点两次结果相同。
 *
 * ## 与单条收藏的关系
 * 详情页的单条收藏走 `RecordRepository.setFavorite(id, ...)`，本用例走批量重载。
 * 两条路径都只动标记与 `updatedAt`，不碰内容字段（`record_revision` 不变），
 * 因此不会让已经生成的 AI 摘要/向量变成过期状态。
 */
class FavoriteRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
) {

    /**
     * 标为收藏。
     *
     * @return 实际改动的条数（`0` = 记录都已删除或不存在）
     */
    suspend operator fun invoke(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Int = apply(ids, favorite = true, now = now)

    /**
     * 取消收藏。
     *
     * 与 [invoke] 分开成两个入口而不是"传 false 就是取消"：调用方读代码时
     * `favorite.unset(ids)` 比 `favorite(ids, false)` 少一层猜测（与
     * `ArchiveRecordsUseCase.restore` 同一约定）。
     */
    suspend fun unset(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Int = apply(ids, favorite = false, now = now)

    private suspend fun apply(ids: List<String>, favorite: Boolean, now: Long): Int {
        val targets = ids.distinct().filter { it.isNotBlank() }
        // 空列表不打数据库：`IN ()` 不报错，但会白跑一次 UPDATE 并多发射一次列表 Flow，
        // 界面上表现为"什么都没做，列表闪了一下"（同 ArchiveRecordsUseCase）
        if (targets.isEmpty()) return 0
        return recordRepository.setFavorite(targets, favorite = favorite, updatedAt = now)
    }
}
