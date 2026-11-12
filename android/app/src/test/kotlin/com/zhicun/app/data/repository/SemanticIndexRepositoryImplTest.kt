package com.zhicun.app.data.repository

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.fake.FakeEmbeddingRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 向量检索（M12）。
 *
 * 用假仓储提供"库里的向量"，真实计算全在 [SemanticIndexRepositoryImpl] 里 ——
 * 余弦本身由 `VectorMathTest` 覆盖，这里只关心**筛选与排序规则**：
 * 低分要截掉、维度不符要忽略、索引为空要如实报告 `comparedCount = 0`。
 */
class SemanticIndexRepositoryImplTest {

    private lateinit var embeddingRepository: FakeEmbeddingRepository
    private lateinit var index: SemanticIndexRepositoryImpl

    private val model = "test-model"

    @Before
    fun setUp() {
        embeddingRepository = FakeEmbeddingRepository()
        index = SemanticIndexRepositoryImpl(embeddingRepository)
    }

    private fun query(vararg values: Float) = Embedding(model, values)

    @Test
    fun Search_ordersByCosineDescending() = runTest {
        // 三个向量与查询的余弦分别是 1.0 / 0.894 / 0.447，都高于下限
        embeddingRepository.seed("same", floatArrayOf(1f, 0f))
        embeddingRepository.seed("close", floatArrayOf(1f, 0.5f))
        embeddingRepository.seed("loose", floatArrayOf(1f, 2f))

        val result = index.search(query(1f, 0f), limit = 10)

        assertEquals(listOf("same", "close", "loose"), result.hits.map { it.recordId })
        assertEquals(3, result.comparedCount)
        assertTrue("分数应降序", result.hits[0].score >= result.hits[1].score)
    }

    @Test
    fun Search_dropsHitsBelowThreshold() = runTest {
        // 与查询正交 → 余弦 0，必须被截掉，否则"搜什么都返回全部记录"
        embeddingRepository.seed("orthogonal", floatArrayOf(0f, 1f))
        embeddingRepository.seed("relevant", floatArrayOf(1f, 0.1f))

        val result = index.search(query(1f, 0f), limit = 10)

        assertEquals(listOf("relevant"), result.hits.map { it.recordId })
        assertEquals("被截掉不等于没比较过", 2, result.comparedCount)
    }

    @Test
    fun Search_noVectors_reportsZeroCompared() = runTest {
        val result = index.search(query(1f, 0f), limit = 10)

        assertTrue(result.hits.isEmpty())
        assertEquals(
            "索引为空要如实报告，界面据此说明为什么只有关键词结果",
            0,
            result.comparedCount,
        )
    }

    @Test
    fun Search_dimensionMismatch_isIgnored() = runTest {
        embeddingRepository.seed("three-dim", floatArrayOf(1f, 0f, 0f))
        embeddingRepository.seed("two-dim", floatArrayOf(1f, 0f))

        val result = index.search(query(1f, 0f), limit = 10)

        assertEquals(listOf("two-dim"), result.hits.map { it.recordId })
    }

    @Test
    fun Search_respectsLimit() = runTest {
        (1..10).forEach { embeddingRepository.seed("r$it", floatArrayOf(1f, 0f)) }

        val result = index.search(query(1f, 0f), limit = 3)

        assertEquals(3, result.hits.size)
        // 同分时按 id 兜底排序，保证两次结果一致
        assertEquals(listOf("r1", "r10", "r2"), result.hits.map { it.recordId })
    }

    @Test
    fun Search_emptyQueryVector_returnsEmptyWithoutTouchingStorage() = runTest {
        embeddingRepository.seed("r1", floatArrayOf(1f, 0f))

        val result = index.search(Embedding(model, FloatArray(0)), limit = 10)

        assertTrue(result.hits.isEmpty())
        assertEquals(0, result.comparedCount)
    }

    @Test
    fun Search_otherModelVectors_areNotCompared() = runTest {
        embeddingRepository.seed("same-model", floatArrayOf(1f, 0f), model = model)
        embeddingRepository.seed("other-model", floatArrayOf(1f, 0f), model = "另一个模型")

        val result = index.search(query(1f, 0f), limit = 10)

        assertEquals(listOf("same-model"), result.hits.map { it.recordId })
        assertEquals(1, result.comparedCount)
    }

    @Test
    fun Search_propagatesStorageFailure() = runTest {
        embeddingRepository.readFailure = IllegalStateException("库被锁")

        val error = runCatching { index.search(query(1f, 0f), limit = 10) }

        // 这里不做降级：降级是 SearchRecordsUseCase 的职责（它知道"还能只给关键词结果"）
        assertTrue(error.isFailure)
    }
}
