package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * 订阅"已归档"列表（M16）。
 *
 * 与 [GetRecordsUseCase] 分开而不是给后者加一个布尔参数：收件箱与归档箱是两个页面，
 * 各自只关心自己那一半数据。加参数的写法迟早会在某处默认值传反，
 * 表现为"归档的记录出现在收件箱里"或"归档箱永远是空的"，而且很难在测试里发现。
 */
class GetArchivedRecordsUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
) {

    /** 按归档时间倒序（最近归档的在前），由 SQL 保证。 */
    operator fun invoke(): Flow<List<Record>> = recordRepository.observeArchived()
}
