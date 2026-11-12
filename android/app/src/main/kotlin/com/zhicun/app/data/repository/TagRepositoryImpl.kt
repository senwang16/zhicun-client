package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.TagDao
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.data.mapper.toDomain
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.domain.repository.TagRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [TagRepository] 的 Room 实现（M11）。
 *
 * ## 标签行的复用规则
 * `tags` 表上有 `(name, source)` 唯一索引：同名同源只有一个标签行，多条记录共用。
 * 因此写入前先 `getByNameAndSource`，查到就复用 id，查不到才插 —— 直接 `INSERT OR REPLACE`
 * 会让"另一条记录也挂着这个标签"的关联被悄悄指向一行新的 tag。
 *
 * ## 替换 AI 标签的顺序
 * 先解除该记录的 **AI 关联**，再挂新的。中间没有事务包裹：
 * 最坏结果是"旧 AI 标签已摘、新标签没挂上"，用户看到的是"这条记录暂时没有 AI 标签"，
 * 下次重新处理就会恢复。反过来（先挂后摘）会出现新旧混在一起，更容易误导。
 */
@Singleton
class TagRepositoryImpl @Inject constructor(
    private val tagDao: TagDao,
) : TagRepository {

    override suspend fun getByRecordId(recordId: String): List<Tag> =
        tagDao.getByRecordId(recordId).map { it.toDomain() }

    override suspend fun replaceAiTags(recordId: String, names: List<String>, now: Long) {
        tagDao.detachByRecordIdAndSource(recordId, TagSource.AI)

        names.forEach { name ->
            val existing = tagDao.getByNameAndSource(name, TagSource.AI)
            val tagId = existing?.id ?: run {
                val entity = TagEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    source = TagSource.AI,
                    // confidence 留 null：网关目前不返回逐标签置信度，
                    // 编一个数字只会让界面显示一个没有来源的"可信度"
                    confidence = null,
                    createdAt = now,
                )
                tagDao.insert(entity)
                entity.id
            }
            tagDao.attachCrossRef(RecordTagCrossRef(recordId = recordId, tagId = tagId))
        }
    }

    override fun observeAllWithRecord(): Flow<List<RecordTag>> =
        tagDao.observeAllWithRecord().map { rows ->
            rows.map { row -> RecordTag(recordId = row.recordId, tag = row.tag.toDomain()) }
        }

    /**
     * 标签使用概览（M21）：口径完全由 SQL 决定（未删/未归档、同名合并），
     * 这里只做实体 → domain 的搬运，不再加第二套过滤。
     */
    override fun observeTagUsage(limit: Int): Flow<List<TagUsage>> =
        tagDao.observeTagUsage(limit).map { rows ->
            rows.map { row -> TagUsage(name = row.name, recordCount = row.recordCount) }
        }

    /** 结果页的"结果标签"（M21）：按 id 批量取，空列表不发查询。 */
    override suspend fun getForRecords(recordIds: List<String>): List<RecordTag> {
        if (recordIds.isEmpty()) return emptyList()
        return tagDao.getForRecords(recordIds).map { row ->
            RecordTag(recordId = row.recordId, tag = row.tag.toDomain())
        }
    }

    /**
     * 给记录挂一个用户标签（M16）。
     *
     * 复用规则同 AI 标签：`(name, source)` 唯一，同名 USER 标签只有一行、多条记录共用。
     * 因此必须"先查再插"，不能 `INSERT OR REPLACE` —— 后者会让别的记录对同一标签的关联
     * 指向一行新 id，界面上表现为"另一条记录的标签突然没了"。
     *
     * 已挂过同名标签时返回 false 而不是静默成功：界面要能说"这个标签已经有了"，
     * 而不是让用户点了没反应。
     */
    override suspend fun addUserTag(recordId: String, name: String, now: Long): Boolean {
        if (name.isBlank()) return false

        val existing = tagDao.getByNameAndSource(name, TagSource.USER)
        val tagId = existing?.id ?: run {
            val entity = TagEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                source = TagSource.USER,
                // 用户手打的标签没有"模型置信度"这回事
                confidence = null,
                createdAt = now,
            )
            tagDao.insert(entity)
            entity.id
        }

        // attachCrossRef 是 IGNORE 策略，重复关联不会报错也不会返回结果，
        // 所以"是否已经挂过"只能自己先查 —— 否则会向用户谎报"已添加"
        val alreadyAttached = tagDao.getByRecordId(recordId).any { it.id == tagId }
        if (alreadyAttached) return false

        tagDao.attachCrossRef(RecordTagCrossRef(recordId = recordId, tagId = tagId))
        return true
    }

    /**
     * 解除关联（M16）。**只删关联，不删 `tags` 表里的行** ——
     * 同一个标签可能还被别的记录挂着，删行会连带删掉它们的标签。
     *
     * 不限制 `source`：用户不该被"这个标签是 AI 加的，你不能删"挡住。
     * 代价是重新跑 AI 处理时它可能又回来（已在 DEVELOPMENT_PLAN M16 已知限制里写明）。
     */
    override suspend fun removeTag(recordId: String, tagId: String) {
        tagDao.detachCrossRef(recordId = recordId, tagId = tagId)
    }

    /** 只清 AI 关联：用户标签是用户自己打的，与内容重算无关。 */
    override suspend fun removeAiTags(recordId: String) {
        tagDao.detachByRecordIdAndSource(recordId, TagSource.AI)
    }
}
