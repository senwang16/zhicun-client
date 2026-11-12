package com.zhicun.app.fake

import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 标签仓储假实现（M11）。
 *
 * 语义对齐真实实现的两条硬约束：
 * 1. `replaceAiTags` 只清 AI 标签，用户标签必须原样保留；
 * 2. 同名同源标签复用同一个 id（真实表上有 `(name, source)` 唯一索引）。
 */
class FakeTagRepository(tags: List<RecordTag> = emptyList()) : TagRepository {

    val associations: MutableStateFlow<List<RecordTag>> = MutableStateFlow(tags)

    override suspend fun getByRecordId(recordId: String): List<Tag> =
        associations.value.filter { it.recordId == recordId }.map { it.tag }

    override suspend fun replaceAiTags(recordId: String, names: List<String>, now: Long) {
        val kept = associations.value.filterNot { it.recordId == recordId && it.tag.source == TagSource.AI }
        val added = names.map { name ->
            RecordTag(
                recordId = recordId,
                tag = Tag(
                    // 同名同源复用 id：与真实表的唯一索引行为一致
                    id = "ai-$name",
                    name = name,
                    source = TagSource.AI,
                    confidence = null,
                    createdAt = now,
                ),
            )
        }
        associations.value = kept + added
    }

    override fun observeAllWithRecord(): Flow<List<RecordTag>> = associations

    /**
     * 添加用户标签（M16）：与真实实现同一套语义 ——
     * 同名同源复用同一个 id、已挂过则返回 false、空白名直接拒绝。
     */
    override suspend fun addUserTag(recordId: String, name: String, now: Long): Boolean {
        if (name.isBlank()) return false
        val tagId = "user-$name"
        if (associations.value.any { it.recordId == recordId && it.tag.id == tagId }) return false
        associations.value = associations.value + RecordTag(
            recordId = recordId,
            tag = Tag(
                id = tagId,
                name = name,
                source = TagSource.USER,
                confidence = null,
                createdAt = now,
            ),
        )
        return true
    }

    /** 只解除关联，不删标签行（别的记录可能还挂着）。 */
    override suspend fun removeTag(recordId: String, tagId: String) {
        associations.value = associations.value.filterNot {
            it.recordId == recordId && it.tag.id == tagId
        }
    }

    /** 与 `TagRepositoryImpl.removeAiTags` 同语义：只摘 AI 标签，用户标签必须保留。 */
    override suspend fun removeAiTags(recordId: String) {
        associations.value = associations.value.filterNot {
            it.recordId == recordId && it.tag.source == TagSource.AI
        }
    }

    /**
     * 标签概览（M21）：fake 只做同名合并 + 按记录数降序。
     *
     * "排除已删除/已归档记录"这一步真实实现由 SQL JOIN records 完成，
     * 假实现拿不到记录可见性，因此不做；对应口径由 TagUsageQueryTest 覆盖。
     */
    override fun observeTagUsage(limit: Int): Flow<List<TagUsage>> =
        associations.map { rows ->
            rows.groupBy { it.tag.name }
                .map { (name, list) ->
                    TagUsage(name = name, recordCount = list.map { it.recordId }.distinct().size)
                }
                .sortedWith(compareByDescending<TagUsage> { it.recordCount }.thenBy { it.name })
                .take(limit)
        }

    /** 按记录 id 批量取标签（M21 决策 3）。 */
    override suspend fun getForRecords(recordIds: List<String>): List<RecordTag> {
        val wanted = recordIds.toSet()
        return associations.value.filter { it.recordId in wanted }
    }
}
