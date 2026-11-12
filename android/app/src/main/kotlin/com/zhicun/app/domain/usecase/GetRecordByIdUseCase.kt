package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.RecordRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 按 id 取单条记录（详情页用）。
 *
 * 两个入口对应两种使用姿势：
 * - [invoke]：一次性读，用于"读完就不再关心变化"的场景；
 * - [observe]：持续订阅，用于详情页 —— M7 起后台 OCR 会改写 `processingStatus`，
 *   页面必须跟着刷新，否则用户得退出再进来才看得到结果。
 *
 * 语义细节：**已软删除的记录也会返回**（[RecordRepository.getById] 不按 is_deleted 过滤），
 * 因为详情页需要如实展示"这条记录已被删除"的状态，而不是给出一个与事实不符的"不存在"。
 * 返回 null 只代表数据库里没有这条记录。
 */
class GetRecordByIdUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
) {

    suspend operator fun invoke(recordId: String): Record? {
        if (recordId.isBlank()) return null
        return recordRepository.getById(recordId)
    }

    /**
     * [invoke] 的"持续版"。
     *
     * id 为空（深链参数丢失）时直接发一个 null，不去查库 ——
     * 空串查询同样命中"没有这条记录"，但显式短路能让空参数的行为更早暴露。
     */
    fun observe(recordId: String): Flow<Record?> {
        if (recordId.isBlank()) return flowOf(null)
        return recordRepository.observeById(recordId)
    }
}
