package com.zhicun.app.fake

import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.CollectionMemberWithRecord
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.CollectionRepository

/**
 * 合集成员关系的假实现（M20）。
 *
 * 只覆盖用例真正依赖的语义：创建时按入参顺序落关系、查询按 `position` 升序、
 * 已删除成员**照常返回**（是否参与生成由调用方判断）。指纹与过期判断保持可注入，
 * 让测试能直接表达"生成后是否还有新变化"。
 */
class FakeCollectionRepository : CollectionRepository {

    /** 每次 [create] 的入参，便于断言字段（成员顺序、敏感并集等）。 */
    data class Creation(
        val collection: Record,
        val memberRecordIds: List<String>,
        val now: Long,
    )

    val creations: MutableList<Creation> = mutableListOf()

    /** collectionId → 成员（未排序，查询时再排）。 */
    val members: MutableMap<String, MutableList<CollectionMember>> = mutableMapOf()

    /** 读取原记录；未接入时 [getMembersWithRecords] 返回空。 */
    var recordLookup: ((String) -> Record?)? = null

    /** 生成成功后写入的指纹；未注入时用固定值。 */
    var fingerprintAfterGenerate: String? = null

    var createFailure: Throwable? = null

    /** 成员变化刷新过的记录 id，按调用顺序。 */
    val refreshed: MutableList<String> = mutableListOf()

    /** 生成成功标记过的合集 id。 */
    val generated: MutableList<String> = mutableListOf()

    /** [isStale] 的固定返回值。 */
    var stale: Boolean = false

    override suspend fun create(collection: Record, memberRecordIds: List<String>, now: Long) {
        createFailure?.let { throw it }
        creations += Creation(collection, memberRecordIds, now)
        members[collection.id] = memberRecordIds.mapIndexed { index, recordId ->
            CollectionMember(
                collectionId = collection.id,
                recordId = recordId,
                position = index,
                addedAt = now,
                snapshotFingerprint = null,
            )
        }.toMutableList()
    }

    override suspend fun getMembers(collectionId: String): List<CollectionMember> =
        members[collectionId].orEmpty().sortedBy { it.position }

    override suspend fun getMembersWithRecords(collectionId: String): List<CollectionMemberWithRecord> {
        val lookup = recordLookup ?: return emptyList()
        return getMembers(collectionId).mapNotNull { member ->
            lookup(member.recordId)?.let { CollectionMemberWithRecord(member = member, record = it) }
        }
    }

    override suspend fun getMemberships(recordId: String): List<CollectionMember> =
        members.values.flatten().filter { it.recordId == recordId }

    override suspend fun markGenerated(collectionId: String, now: Long) {
        generated += collectionId
        val fingerprint = fingerprintAfterGenerate ?: "generated"
        members[collectionId]?.replaceAll { it.copy(snapshotFingerprint = fingerprint) }
    }

    override suspend fun refreshForMemberChange(memberRecordId: String, now: Long) {
        refreshed += memberRecordId
    }

    /**
     * 与真实实现同口径：只看**未删除**成员的并集；空合集返回 `UNKNOWN`。
     *
     * 未接入 [recordLookup] 时无法判断成员，直接返回 `UNKNOWN` ——
     * 不假装"都是 CLEAR"，否则"敏感成员被并进合集"这类问题会被假实现洗掉。
     */
    override suspend fun effectiveSensitivity(collectionId: String): SensitivityStatus {
        val lookup = recordLookup ?: return SensitivityStatus.UNKNOWN
        val statuses = getMembers(collectionId).mapNotNull { member ->
            lookup(member.recordId)
                ?.takeUnless { it.isDeleted }
                ?.sensitivityStatus
        }
        return CollectionSensitivity.union(statuses)
    }

    override suspend fun isStale(collectionId: String): Boolean = stale
}
