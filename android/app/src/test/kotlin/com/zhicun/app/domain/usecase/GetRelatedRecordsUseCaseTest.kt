package com.zhicun.app.domain.usecase

import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相关记录推荐（M18）。
 *
 * 钉住的是这些事：自己不出现、没向量不出、不相关的（余弦不过下限）不出、
 * **推荐阈值高于搜索阈值**、分数随结果返回、总数在截断后仍准确、
 * 分数降序且同分按 id、limit 生效、"向量在但记录没了"的窗口期不留空卡片。
 */
class GetRelatedRecordsUseCaseTest {

    private val embeddings = FakeEmbeddingRepository()
    private val records = FakeRecordRepository()
    private val useCase = GetRelatedRecordsUseCase(embeddings, records)

    /** 候选向量：与 self 的余弦分别为 1.0 / 0.894 / 0.40 / 0.0 / -1.0。 */
    private fun seedAll(self: FloatArray = floatArrayOf(1f, 0f)) {
        embeddings.seed("self", self)
        embeddings.seed("same", floatArrayOf(1f, 0f))
        embeddings.seed("near", floatArrayOf(0.8944f, 0.4472f))
        // 余弦 0.40：过得了搜索的 0.30，过不了推荐的 0.45
        embeddings.seed("weak", floatArrayOf(0.4f, 0.9165f))
        embeddings.seed("unrelated", floatArrayOf(0f, 1f))
        embeddings.seed("opposite", floatArrayOf(-1f, 0f))
        records.stored.value = listOf(
            testRecord(id = "self", rawText = "自己"),
            testRecord(id = "same", rawText = "完全一样"),
            testRecord(id = "near", rawText = "比较像"),
            testRecord(id = "weak", rawText = "弱相关"),
            testRecord(id = "unrelated", rawText = "毫不相关"),
            testRecord(id = "opposite", rawText = "完全相反"),
        )
    }

    @Test
    fun Invoke_selfWithoutVector_returnsEmpty() = runTest {
        records.stored.value = listOf(testRecord(id = "r1"))

        val result = useCase("r1")

        assertTrue("没有向量就谈不上'相似'，分区应整个不出现", result.isEmpty)
        assertEquals("空结果的 total 也必须是 0，否则界面会显示'共 0 条'", 0, result.total)
    }

    @Test
    fun Invoke_blankRecordId_returnsEmpty() = runTest {
        seedAll()

        assertTrue(useCase("").isEmpty)
        assertTrue(useCase("   ").isEmpty)
    }

    @Test
    fun Invoke_selfIsNeverItsOwnRecommendation() = runTest {
        seedAll()

        val ids = useCase("self").items.map { it.record.id }

        assertTrue("自己不能出现在自己的相关记录里", "self" !in ids)
    }

    @Test
    fun Invoke_belowThreshold_isExcluded() = runTest {
        seedAll()

        val ids = useCase("self").items.map { it.record.id }

        assertTrue(
            "余弦 0 与负数都不过 0.45 的下限",
            "unrelated" !in ids && "opposite" !in ids,
        )
        assertEquals(listOf("same", "near"), ids)
    }

    /**
     * 这条是本轮（2026-10-24）改动的核心：推荐与搜索的下限**不是同一个数**。
     *
     * 0.40 的候选在语义搜索里算命中（用户主动找，宁多勿漏），但作为"系统主动塞给
     * 用户的相关记录"就是噪声。两个常量必须能各自独立变化，别有人把它们合并回去。
     */
    @Test
    fun Invoke_scoreBetweenSearchAndRecommendThreshold_isExcluded() = runTest {
        seedAll()

        val ids = useCase("self").items.map { it.record.id }

        assertTrue("0.40 过得了搜索的 0.30，但过不了推荐的 0.45", "weak" !in ids)
    }

    @Test
    fun Invoke_returnsScoreAlongsideRecord() = runTest {
        seedAll()

        val same = useCase("self").items.first { it.record.id == "same" }

        assertEquals("卡片要显示相关度百分比，分数必须跟着结果一起回来", 1.0, same.score, 1e-6)
    }

    /**
     * 总数是"过线候选数"，不是"展示条数"。
     *
     * 界面上"一共就 5 条"与"有 6 条只显示了 5 条"必须能区分开 —— 旧实现只返回
     * top-N，用户永远不知道这个分区是不是被截断过。
     */
    @Test
    fun Invoke_totalCountsEveryCandidateOverThreshold() = runTest {
        embeddings.seed("self", floatArrayOf(1f, 0f))
        val others = (1..6).map { "c$it" }
        others.forEach { embeddings.seed(it, floatArrayOf(1f, 0f)) }
        records.stored.value = listOf(testRecord(id = "self")) +
            others.map { testRecord(id = it) }

        val result = useCase("self")

        assertEquals("默认只展示 5 条", 5, result.items.size)
        assertEquals("但总数要如实报 6 条", 6, result.total)
    }

    @Test
    fun Invoke_sameScore_ordersById() = runTest {
        embeddings.seed("self", floatArrayOf(1f, 0f))
        embeddings.seed("b", floatArrayOf(1f, 0f))
        embeddings.seed("a", floatArrayOf(1f, 0f))
        records.stored.value = listOf(
            testRecord(id = "self"),
            testRecord(id = "a"),
            testRecord(id = "b"),
        )

        assertEquals(
            "同分按 id 字典序，保证两次进入详情页顺序一致",
            listOf("a", "b"),
            useCase("self").items.map { it.record.id },
        )
    }

    @Test
    fun Invoke_limitTruncates() = runTest {
        seedAll()

        assertEquals(listOf("same"), useCase("self", limit = 1).items.map { it.record.id })
    }

    @Test
    fun Invoke_missingRecordRow_isSkipped() = runTest {
        // 向量还在（清理前的窗口期 / 换模型残留），记录行已经没了：不能返回空壳
        seedAll()
        records.stored.value = records.stored.value.filter { it.id != "near" }

        assertEquals(listOf("same"), useCase("self").items.map { it.record.id })
    }
}
