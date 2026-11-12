package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * 收件箱数据流。
 *
 * 只做一件事：把仓储的 Flow 转交给上层。没有额外过滤/排序 —— 排序（置顶优先 + created_at 倒序）
 * 与"未删除且未归档"的过滤都是 SQL 的职责（[RecordRepository.observeInbox] 已保证），
 * 在内存里再排一次会让大列表的首帧变慢，且容易与 SQL 的排序不一致。
 *
 * M16 起改用 [RecordRepository.observeInbox]：归档的记录不再出现在收件箱，
 * 但仍会出现在导出与搜索里（那是 [RecordRepository.observeAll] 的职责）。
 */
class GetRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
) {
    operator fun invoke(): Flow<List<Record>> = recordRepository.observeInbox()
}
