package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.CollectionDao
import com.zhicun.app.data.local.dao.RecordDao
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.clipboard.ClipboardText
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.search.SearchQuery
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [RecordRepository] 的 Room 实现。
 *
 * 仓储不持有 UI 状态，只把 DAO 的 Flow / 挂起函数翻译成 domain 模型（AGENTS.md §3）。
 */
@Singleton
class RecordRepositoryImpl @Inject constructor(
    private val recordDao: RecordDao,
    private val collectionDao: CollectionDao,
    private val sensitiveDetector: SensitiveContentDetector,
) : RecordRepository {

    override fun observeAll(): Flow<List<Record>> =
        recordDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeInbox(): Flow<List<Record>> =
        recordDao.observeInbox().map { entities -> entities.map { it.toDomain() } }

    override fun observeArchived(): Flow<List<Record>> =
        recordDao.observeArchived().map { entities -> entities.map { it.toDomain() } }

    /** 空查询直接短路（同主搜索的纪律），绝不退化成"归档箱全量返回"。 */
    override fun searchArchivedBySubstring(normalized: String, limit: Int): Flow<List<Record>> {
        if (normalized.isBlank() || limit <= 0) return flowOf(emptyList())
        return recordDao
            .searchArchivedBySubstring(SearchQuery.likePattern(normalized), limit)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getById(id: String): Record? = recordDao.getById(id)?.toDomain()

    /**
     * 合集读成员表、其它类型直接返回。
     *
     * 成员行按 `position` 查出来即可 —— 这里不需要顺序，但复用同一个查询比再写一条
     * "只取 id" 的 SQL 更不容易在两处产生分歧。
     *
     * 已删除成员不参与：它们的内容不会进入合集摘要，算进并集只会让合集一直被拦。
     * 归档成员**参与**（内容没变，仍会被摘要读到）。
     *
     * 合集自己的 `sensitivity_status` 不能直接沿用：成员变化后它可能已不是最新。
     * 这里重新扫描**合集说明**（`raw_text`）再与成员并集合并，而不是把整列状态并进来 ——
     * 后者会把已删除成员留下的旧状态永久保留。
     */
    override suspend fun gateRecord(record: Record): Record {
        if (record.type != RecordType.COLLECTION) return record
        val statuses = collectionDao.getMembers(record.id).mapNotNull { member ->
            recordDao.getById(member.recordId)
                ?.takeUnless { it.isDeleted }
                ?.sensitivityStatus
        }
        val scan = sensitiveDetector.scan(
            listOfNotNull(record.rawText, record.sourceTitle).joinToString("\n"),
        )
        return record.copy(
            sensitivityStatus = CollectionSensitivity.union(
                statuses + sensitiveDetector.toSensitivityStatus(scan),
            ),
        )
    }

    /** 空 id 列表直接短路：Room 的 `IN ()` 虽然不报错，但白跑一次查询没意义。 */
    override suspend fun getByIds(ids: List<String>): List<Record> {
        if (ids.isEmpty()) return emptyList()
        return recordDao.getByIds(ids).map { it.toDomain() }
    }

    override fun observeById(id: String): Flow<Record?> =
        recordDao.observeById(id).map { it?.toDomain() }

    override suspend fun save(record: Record) = recordDao.insert(record.toEntity())

    override suspend fun update(record: Record) = recordDao.update(record.toEntity())

    override suspend fun softDelete(id: String, updatedAt: Long) =
        recordDao.softDelete(id = id, updatedAt = updatedAt)

    override suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int =
        recordDao.softDeleteMany(ids = ids, updatedAt = updatedAt)

    override suspend fun updateProcessingStatus(
        id: String,
        status: ProcessingStatus,
        updatedAt: Long,
    ) = recordDao.updateProcessingStatus(id = id, status = status, updatedAt = updatedAt)

    override suspend fun updateType(id: String, type: RecordType, updatedAt: Long): Boolean =
        recordDao.updateType(id = id, type = type, updatedAt = updatedAt) > 0

    override suspend fun updateRawText(
        id: String,
        rawText: String?,
        sensitivityStatus: SensitivityStatus,
        expectedRevision: Int,
        updatedAt: Long,
    ): Boolean = recordDao.updateRawText(
        id = id,
        rawText = rawText,
        sensitivityStatus = sensitivityStatus,
        expectedRevision = expectedRevision,
        updatedAt = updatedAt,
    ) > 0

    override suspend fun confirmCloudProcessing(
        id: String,
        revision: Int,
        scope: Set<ContentPart>,
        status: ProcessingStatus,
        updatedAt: Long,
    ): Boolean = recordDao.confirmCloudProcessing(
        id = id,
        revision = revision,
        scope = ContentPart.encode(scope).takeIf { it.isNotEmpty() },
        status = status,
        updatedAt = updatedAt,
    ) > 0

    override suspend fun confirmWebFetch(id: String, revision: Int, updatedAt: Long): Boolean =
        recordDao.confirmWebFetch(id = id, revision = revision, updatedAt = updatedAt) > 0

    override suspend fun idsByProcessingStatus(status: ProcessingStatus): List<String> =
        recordDao.idsByProcessingStatus(status)

    override suspend fun findDuplicate(text: String): String? =
        recordDao.findIdByNormalizedText(text = ClipboardText.dedupKey(text))

    override suspend fun recomputeProcessingStatus(
        id: String,
        cloudActive: Boolean,
        updatedAt: Long,
    ) = recordDao.recomputeProcessingStatus(id = id, cloudActive = cloudActive, updatedAt = updatedAt)

    override suspend fun elevateSensitivity(id: String, updatedAt: Long) =
        recordDao.elevateSensitivity(id = id, updatedAt = updatedAt)

    override suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long): Boolean =
        recordDao.setFavorite(id = id, favorite = favorite, updatedAt = updatedAt) > 0

    /** 空列表短路，理由同 [setArchived]。 */
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean, updatedAt: Long): Int {
        if (ids.isEmpty()) return 0
        return recordDao.setFavoriteBatch(ids = ids, favorite = favorite, updatedAt = updatedAt)
    }

    override suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Boolean =
        recordDao.setPinned(id = id, pinned = pinned, updatedAt = updatedAt) > 0

    /** 空列表短路：Room 的 `IN ()` 不报错但会白跑一次 UPDATE，还会多发射一次列表。 */
    override suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long): Int {
        if (ids.isEmpty()) return 0
        return recordDao.setArchived(ids = ids, archivedAt = archivedAt, updatedAt = updatedAt)
    }
}
