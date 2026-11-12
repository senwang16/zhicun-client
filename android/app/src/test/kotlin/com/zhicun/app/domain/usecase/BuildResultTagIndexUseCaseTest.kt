package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.fake.testRecord
import com.zhicun.app.fake.FakeTagRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果标签聚合（M21 决策 3）。
 *
 * 这里钉的是"什么情况下不显示"与"口径"：
 * - 结果 < 2 条、标签 < 2 个 → 不显示（避免为一张卡片配一个标签）；
 * - 同名 AI/用户标签合并，同一记录只算一次；
 * - 最多 6 个，按命中记录数降序。
 */
class BuildResultTagIndexUseCaseTest {

    private fun tagged(recordId: String, name: String, source: TagSource = TagSource.AI) = RecordTag(
        recordId = recordId,
        tag = Tag(
            id = "${source.name.lowercase()}-$name",
            name = name,
            source = source,
            confidence = null,
            createdAt = 1L,
        ),
    )

    private fun useCaseWith(vararg rows: RecordTag): BuildResultTagIndexUseCase =
        BuildResultTagIndexUseCase(FakeTagRepository(rows.toList()))

    @Test
    fun SingleRecord_returnsEmpty() = runTest {
        val index = useCaseWith(tagged("r1", "工作"), tagged("r1", "会议"))
            .invoke(listOf(testRecord(id = "r1")))

        assertTrue(index.tags.isEmpty())
        assertTrue(index.recordIdsByTag.isEmpty())
    }

    @Test
    fun SingleTag_returnsEmpty() = runTest {
        val index = useCaseWith(tagged("r1", "工作"), tagged("r2", "工作"))
            .invoke(listOf(testRecord(id = "r1"), testRecord(id = "r2")))

        assertTrue("只有一个标签时不显示结果标签", index.tags.isEmpty())
    }

    @Test
    fun SameNameAcrossSources_mergesAndCountsOncePerRecord() = runTest {
        val index = useCaseWith(
            tagged("r1", "工作", TagSource.AI),
            tagged("r1", "工作", TagSource.USER),
            tagged("r2", "工作", TagSource.AI),
            tagged("r1", "会议"),
        ).invoke(listOf(testRecord(id = "r1"), testRecord(id = "r2")))

        assertEquals(listOf("工作" to 2, "会议" to 1), index.tags.map { it.name to it.recordCount })
        assertEquals(setOf("r1", "r2"), index.recordIdsFor("工作"))
        assertEquals(setOf("r1"), index.recordIdsFor("会议"))
    }

    @Test
    fun KeepsAtMostSixTagsOrderedByCount() = runTest {
        val rows = mutableListOf<RecordTag>()
        repeat(8) { tagIndex ->
            // 第 0 个标签挂 8 条记录，第 7 个标签挂 1 条
            repeat(8 - tagIndex) { recordIndex ->
                rows += tagged("r$recordIndex", "标签$tagIndex")
            }
        }
        val records = (0 until 8).map { testRecord(id = "r$it") }

        val index = BuildResultTagIndexUseCase(FakeTagRepository(rows))
            .invoke(records)

        assertEquals(BuildResultTagIndexUseCase.MAX_TAGS, index.tags.size)
        assertEquals("标签0", index.tags.first().name)
        assertEquals(8, index.tags.first().recordCount)
        // 被截掉的标签不应留在索引里（否则界面没有 chip 却能被收窄）
        assertEquals(
            BuildResultTagIndexUseCase.MAX_TAGS,
            index.recordIdsByTag.size,
        )
    }
}