package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.ResultTagIndex
import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.domain.repository.TagRepository
import javax.inject.Inject

/**
 * 从一批检索结果里算出"结果标签"（M21 决策 3）。
 *
 * 口径：
 * - 只统计**这批结果**上的标签，同名 AI/用户标签合并（与标签云一致）；
 * - 按命中的记录数降序，最多 6 个；
 * - 结果少于 2 条、或可用标签少于 2 个时返回 [ResultTagIndex.EMPTY]——为一张卡片配
 *   一个标签的"结果标签"没有信息量，只会占地方。
 */
class BuildResultTagIndexUseCase @Inject constructor(
    private val tagRepository: TagRepository,
) {

    suspend operator fun invoke(records: List<Record>): ResultTagIndex {
        if (records.size < MIN_RECORDS) return ResultTagIndex.EMPTY

        val rows = tagRepository.getForRecords(records.map { it.id })
        if (rows.isEmpty()) return ResultTagIndex.EMPTY

        // 同名标签合并：同一条记录同时挂 AI/用户同名标签时，靠 Set 去重只算一次
        val recordIdsByName = LinkedHashMap<String, MutableSet<String>>()
        rows.forEach { row ->
            recordIdsByName.getOrPut(row.tag.name) { linkedSetOf() }.add(row.recordId)
        }

        val usage = recordIdsByName
            .map { (name, recordIds) -> TagUsage(name = name, recordCount = recordIds.size) }
            .sortedWith(compareByDescending<TagUsage> { it.recordCount }.thenBy { it.name })
            .take(MAX_TAGS)
        if (usage.size < MIN_TAGS) return ResultTagIndex.EMPTY

        val kept = usage.map { it.name }.toSet()
        return ResultTagIndex(
            tags = usage,
            recordIdsByTag = recordIdsByName
                .filterKeys { it in kept }
                .mapValues { (_, ids) -> ids.toSet() },
        )
    }

    companion object {
        /** 结果少于两条时不显示标签行。 */
        const val MIN_RECORDS: Int = 2

        /** 可用标签少于两个时不显示标签行。 */
        const val MIN_TAGS: Int = 2

        /** 结果页最多展示的标签数；超出部分靠横向滚动也没有意义。 */
        const val MAX_TAGS: Int = 6
    }
}