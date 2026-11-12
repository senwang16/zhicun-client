package com.zhicun.app.presentation.detail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zhicun.app.domain.usecase.GetRecordByIdUseCase
import com.zhicun.app.domain.usecase.GetRelatedRecordsUseCase
import com.zhicun.app.domain.usecase.RelatedRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 详情页"相关记录"（M18）的加载逻辑 —— 从 `RecordDetailViewModel` 原样抽出。
 *
 * 纯移动、零行为变更（含日志文案、`distinctUntilChanged` 的重算节流、取消放行）。
 * 抽出的目的是让 ViewModel 回到 400 行以内（AGENTS.md §2.1）。
 *
 * 抽取方式说明：这里不能用「扩展函数 + `viewModel::` 方法引用」的写法，
 * 但这两个方法都只由 `init` 在类内调用、界面层不引用它们，
 * 因此做成普通扩展函数由 ViewModel 的 `init` 调用是安全的。
 */

/**
 * 相关记录（M18）。与主数据流分开加载：推荐是**附加信息**，
 * 它慢一点、甚至失败，都不该拖住首屏的原文渲染 —— 慢网关算向量时
 * 用户应当已经在读正文了。
 *
 * ## 为什么要跟着 `processingStatus` 重算（2026-10-24）
 * 旧实现只在 `init` 里算一次。而向量是 `EMBED` 任务异步产出的：用户在处理
 * 还没跑完时点进详情页，那一刻查不到向量 → 分区整块不出现，且**再也不会出现**，
 * 必须退出再进来。现在状态一变就重算，处理完成时推荐会自己长出来。
 *
 * 用 `distinctUntilChanged` 卡住重算频率：编辑标签、收藏、置顶只改 `updated_at`
 * 不改 `processingStatus`，不该让它们每次都触发一次全量余弦扫描。
 */
internal fun RecordDetailViewModel.observeRelatedRecords(
    getRecordById: GetRecordByIdUseCase,
    getRelatedRecords: GetRelatedRecordsUseCase,
    relatedRecords: MutableStateFlow<RelatedRecords>,
) {
    viewModelScope.launch {
        getRecordById.observe(recordId)
            .map { it?.processingStatus }
            .distinctUntilChanged()
            .collectLatest {
                relatedRecords.value = try {
                    getRelatedRecords(recordId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 附加信息不配拥有 Error 页：留痕即可，界面按"没有相关记录"处理
                    Log.w(RecordDetailViewModel.TAG, "相关记录加载失败 recordId=$recordId", e)
                    RelatedRecords()
                }
            }
    }
}
