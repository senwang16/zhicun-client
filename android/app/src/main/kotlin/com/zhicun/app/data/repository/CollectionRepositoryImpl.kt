package com.zhicun.app.data.repository

import androidx.room.withTransaction
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.dao.CollectionDao
import com.zhicun.app.data.local.dao.DerivedContentDao
import com.zhicun.app.data.local.dao.RecordDao
import com.zhicun.app.data.local.entity.CollectionMemberEntity
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.data.mapper.toDomainOrNull
import com.zhicun.app.data.mapper.toEntity
import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.CollectionMemberFingerprint
import com.zhicun.app.domain.model.CollectionMemberWithRecord
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import javax.inject.Inject
import javax.inject.Singleton

/** [CollectionRepository] 的 Room 实现。 */
@Singleton
class CollectionRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val recordDao: RecordDao,
    private val collectionDao: CollectionDao,
    private val derivedContentDao: DerivedContentDao,
    private val sensitiveDetector: SensitiveContentDetector,
) : CollectionRepository {

    override suspend fun create(
        collection: Record,
        memberRecordIds: List<String>,
        now: Long,
    ) {
        require(memberRecordIds.isNotEmpty()) { "合集至少要有一个成员" }
        database.withTransaction {
            recordDao.insert(collection.toEntity())
            collectionDao.insertAll(
                memberRecordIds.mapIndexed { index, recordId ->
                    CollectionMemberEntity(
                        collectionId = collection.id,
                        recordId = recordId,
                        position = index,
                        addedAt = now,
                    )
                },
            )
        }
    }

    override suspend fun getMembers(collectionId: String): List<CollectionMember> =
        collectionDao.getMembers(collectionId).map { it.toDomain() }

    override suspend fun getMembersWithRecords(
        collectionId: String,
    ): List<CollectionMemberWithRecord> =
        collectionDao.getMembers(collectionId).mapNotNull { entity ->
            val record = recordDao.getById(entity.recordId) ?: return@mapNotNull null
            CollectionMemberWithRecord(
                member = entity.toDomain(),
                record = record.toDomain(),
            )
        }

    override suspend fun getMemberships(recordId: String): List<CollectionMember> =
        collectionDao.getMemberships(recordId).map { it.toDomain() }

    override suspend fun markGenerated(collectionId: String, now: Long) {
        database.withTransaction {
            collectionDao.getMembers(collectionId).forEach { member ->
                val record = recordDao.getById(member.recordId)?.toDomain() ?: return@forEach
                // 跳过无法识别的派生类型（2026-11-10 Bug 4）：指纹只由可识别的内容组成
                val derived = derivedContentDao.getByRecordId(member.recordId)
                    .mapNotNull { it.toDomainOrNull() }
                collectionDao.updateSnapshot(
                    collectionId = collectionId,
                    recordId = member.recordId,
                    fingerprint = CollectionMemberFingerprint.of(record, derived),
                )
            }
        }
    }

    override suspend fun refreshForMemberChange(memberRecordId: String, now: Long) {
        val collectionIds = collectionDao.getMemberships(memberRecordId)
            .map { it.collectionId }
            .distinct()
        if (collectionIds.isEmpty()) return

        database.withTransaction {
            collectionIds.forEach { collectionId ->
                // 逐条读而不是联表一次取出：成员内容在同一事务里刚被改过，
                // 这里要的是"提交后的最新值"，走同一条 recordDao 查询最直接。
                val statuses = collectionDao.getMembers(collectionId).mapNotNull { member ->
                    recordDao.getById(member.recordId)
                        ?.takeUnless { it.isDeleted }
                        ?.sensitivityStatus
                }
                // 合集自身的状态不能被成员并集覆盖：合集说明（raw_text）也会被摘要读到，
                // 说明里有敏感内容时必须继续拦住。这里重新扫描说明再取并集 ——
                // 直接沿用旧值会把已删除成员的历史状态固化下来，徽标再也降不回去。
                val collection = recordDao.getById(collectionId)?.toDomain()
                val descriptionScan = sensitiveDetector.scan(
                    listOfNotNull(collection?.rawText, collection?.sourceTitle).joinToString("\n"),
                )
                recordDao.updateSensitivityAndClearConfirmation(
                    id = collectionId,
                    status = CollectionSensitivity.union(
                        statuses + sensitiveDetector.toSensitivityStatus(descriptionScan),
                    ),
                    updatedAt = now,
                )
            }
        }
    }

    override suspend fun effectiveSensitivity(collectionId: String): SensitivityStatus {
        val statuses = collectionDao.getMembers(collectionId).mapNotNull { member ->
            recordDao.getById(member.recordId)
                ?.takeUnless { it.isDeleted }
                ?.sensitivityStatus
        }
        return CollectionSensitivity.union(statuses)
    }

    override suspend fun isStale(collectionId: String): Boolean =
        collectionDao.getMembers(collectionId).any { member ->
            val record = recordDao.getById(member.recordId)?.toDomain()
                ?: return@any false
            val derived = derivedContentDao.getByRecordId(member.recordId)
                .mapNotNull { it.toDomainOrNull() }
            member.snapshotFingerprint != CollectionMemberFingerprint.of(record, derived)
        }
}
