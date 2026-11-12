package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.MaintenanceDao
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.MaintenanceRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [MaintenanceRepository] 的 Room 实现（M15）。逻辑全在 SQL 与 [MaintenanceDao.purgeRecord] 里。 */
@Singleton
class MaintenanceRepositoryImpl @Inject constructor(
    private val dao: MaintenanceDao,
) : MaintenanceRepository {

    override suspend fun getSoftDeletedRecords(): List<Record> =
        dao.getSoftDeletedRecords().map { it.toDomain() }

    override suspend fun purgeRecord(recordId: String): Boolean = dao.purgeRecord(recordId)
}
