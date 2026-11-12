package com.zhicun.app.data.repository

import android.util.Log
import com.zhicun.app.data.local.dao.DerivedContentDao
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.data.mapper.toDomainOrNull
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.repository.DerivedContentRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [DerivedContentRepository] 的 Room 实现。 */
@Singleton
class DerivedContentRepositoryImpl @Inject constructor(
    private val derivedContentDao: DerivedContentDao,
) : DerivedContentRepository {

    /**
     * 一条记录的全部派生内容。
     *
     * 用 `toDomainOrNull` 跳过无法识别的 `content_type`（2026-11-10 Bug 4）：
     * 一条脏行只该少一块派生内容，不该让整页详情打不开。跳过时留一条日志，
     * 便于定位"是谁往库里写了不认识的值"。
     */
    override suspend fun getByRecordId(recordId: String): List<DerivedContent> {
        val rows = derivedContentDao.getByRecordId(recordId)
        val contents = rows.mapNotNull { it.toDomainOrNull() }
        if (contents.size != rows.size) {
            Log.w(TAG, "记录 $recordId 有 ${rows.size - contents.size} 条派生内容类型无法识别，已跳过")
        }
        return contents
    }

    override suspend fun getByRecordAndType(
        recordId: String,
        type: DerivedType,
    ): DerivedContent? = derivedContentDao.getByRecordIdAndType(recordId, type)?.toDomain()

    override suspend fun getByType(type: DerivedType): List<DerivedContent> =
        derivedContentDao.getAllByTypeSearchable(type).map { it.toDomain() }

    override suspend fun upsert(content: DerivedContent) =
        derivedContentDao.upsert(content.toEntity())

    /** 空列表短路：Room 的 `IN ()` 不报错，但白跑一次 DELETE 没有意义。 */
    override suspend fun deleteByRecordAndTypes(recordId: String, types: Collection<DerivedType>) {
        if (types.isEmpty()) return
        derivedContentDao.deleteByRecordAndTypes(recordId, types.toList())
    }

    override suspend fun deleteByIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        derivedContentDao.deleteByIds(ids.toList())
    }

    override suspend fun deleteByAssetId(assetId: String) {
        if (assetId.isEmpty()) return
        derivedContentDao.deleteByAssetId(assetId)
    }

    /**
     * `NOT IN ()` 在 SQLite 里是语法错误，空保留列表只能改走"删该类型全部行"：
     * 语义正好一致（没有任何附件值得保留）。
     */
    override suspend fun deleteAssetRowsNotIn(
        recordId: String,
        type: DerivedType,
        keepAssetIds: Collection<String>,
    ) {
        if (keepAssetIds.isEmpty()) {
            derivedContentDao.deleteByRecordAndTypes(recordId, listOf(type))
        } else {
            derivedContentDao.deleteAssetRowsNotIn(recordId, type, keepAssetIds.toList())
        }
    }

    private companion object {
        const val TAG = "DerivedContentRepo"
    }
}
