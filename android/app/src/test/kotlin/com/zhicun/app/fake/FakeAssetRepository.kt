package com.zhicun.app.fake

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.repository.AssetRepository

/** 附件仓储假实现（详情页测试用）。 */
class FakeAssetRepository(
    assets: List<Asset> = emptyList(),
) : AssetRepository {

    val stored: MutableList<Asset> = assets.toMutableList()

    override suspend fun getByRecordId(recordId: String): List<Asset> =
        stored.filter { it.recordId == recordId }

    /**
     * 与 AssetDao.insert 的 REPLACE 语义一致：同 id 覆盖整行，不同 id 追加。
     * （网页快照用确定性 id 重跑覆盖，外部导入是 UUID 纯追加。）
     */
    override suspend fun save(asset: Asset) {
        val index = stored.indexOfFirst { it.id == asset.id }
        if (index >= 0) stored[index] = asset else stored += asset
    }

    override suspend fun updateSaveStatus(id: String, status: AssetSaveStatus) {
        val index = stored.indexOfFirst { it.id == id }
        if (index >= 0) stored[index] = stored[index].copy(saveStatus = status)
    }

    override suspend fun deleteById(id: String) {
        stored.removeAll { it.id == id }
    }

    /** 与真实 SQL 同一判据：每个种子记录里图片或音频达到 2 个才算。 */
    override suspend fun getRecordIdsWithMultipleMedia(): List<String> =
        stored.groupBy { it.recordId }
            .filterValues { assets ->
                assets.count { it.mimeType.startsWith("image/") } >= 2 ||
                    assets.count { it.mimeType.startsWith("audio/") } >= 2
            }
            .keys
            .toList()
}
