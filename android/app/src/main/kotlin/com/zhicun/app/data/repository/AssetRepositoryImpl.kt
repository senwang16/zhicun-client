package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.AssetDao
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.repository.AssetRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [AssetRepository] 的 Room 实现。 */
@Singleton
class AssetRepositoryImpl @Inject constructor(
    private val assetDao: AssetDao,
) : AssetRepository {

    override suspend fun getByRecordId(recordId: String): List<Asset> =
        assetDao.getByRecordId(recordId).map { it.toDomain() }

    override suspend fun save(asset: Asset) = assetDao.insert(asset.toEntity())

    override suspend fun updateSaveStatus(id: String, status: AssetSaveStatus) =
        assetDao.updateSaveStatus(id = id, status = status)

    override suspend fun deleteById(id: String) = assetDao.deleteById(id)

    override suspend fun getRecordIdsWithMultipleMedia(): List<String> =
        assetDao.getRecordIdsWithMultipleMedia()
}
