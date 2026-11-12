package com.zhicun.app.fake

import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.repository.DerivedContentRepository

/**
 * 派生内容仓储假实现（M7）。
 *
 * 按 `recordId + contentType + assetId` 唯一存一条，与真实表上的唯一索引一致 ——
 * 这样"重复处理覆盖旧值"的行为在测试里也能被验证到。
 */
class FakeDerivedContentRepository : DerivedContentRepository {

    val stored: MutableList<DerivedContent> = mutableListOf()

    /** 置为非 null 时读操作抛该异常，用于测详情页的 Error 分支。 */
    var readFailure: Throwable? = null

    override suspend fun getByRecordId(recordId: String): List<DerivedContent> {
        readFailure?.let { throw it }
        return stored.filter { it.recordId == recordId }
    }

    /** 与真实 DAO 一致：只取记录级（assetId = null）的行。 */
    override suspend fun getByRecordAndType(
        recordId: String,
        type: DerivedType,
    ): DerivedContent? {
        readFailure?.let { throw it }
        return stored.firstOrNull {
            it.recordId == recordId && it.contentType == type && it.assetId == null
        }
    }

    override suspend fun getByType(type: DerivedType): List<DerivedContent> {
        readFailure?.let { throw it }
        return stored.filter { it.contentType == type }
    }

    override suspend fun upsert(content: DerivedContent) {
        stored.removeAll {
            it.recordId == content.recordId &&
                it.contentType == content.contentType &&
                it.assetId == content.assetId
        }
        stored += content
    }

    /** 与 `DerivedContentDao.deleteByRecordAndTypes` 同语义：只删指定记录的指定类型。 */
    override suspend fun deleteByRecordAndTypes(recordId: String, types: Collection<DerivedType>) {
        if (types.isEmpty()) return
        stored.removeAll { it.recordId == recordId && it.contentType in types }
    }

    override suspend fun deleteByIds(ids: Collection<String>) {
        stored.removeAll { it.id in ids }
    }

    override suspend fun deleteByAssetId(assetId: String) {
        stored.removeAll { it.assetId == assetId }
    }

    override suspend fun deleteAssetRowsNotIn(
        recordId: String,
        type: DerivedType,
        keepAssetIds: Collection<String>,
    ) {
        stored.removeAll {
            it.recordId == recordId && it.contentType == type &&
                (it.assetId == null || it.assetId !in keepAssetIds)
        }
    }
}
