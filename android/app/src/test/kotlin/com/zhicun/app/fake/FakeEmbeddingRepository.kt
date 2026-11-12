package com.zhicun.app.fake

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.EmbeddingSpace
import com.zhicun.app.domain.repository.StoredEmbedding
import com.zhicun.app.domain.search.VectorMath

/**
 * 向量仓储假实现（M12）。
 *
 * 按 `recordId` 唯一存一条（与真实的 `recordId:EMBEDDING` 主键一致），
 * 这样"重复处理覆盖而不是新增一行"的幂等语义在假实现上也成立 ——
 * 否则测试会为一个只存在于假实现里的行为背书。
 *
 * v10（开源改造 P1）：每条向量带来源标识；[getAll] 按模型 + 来源过滤，
 * 与真实实现的兼容性判据保持一致（空来源 = 通配）。
 */
class FakeEmbeddingRepository : EmbeddingRepository {

    val stored: MutableMap<String, StoredEmbedding> = mutableMapOf()

    /** 记录 [upsert] 调用次数：用于断言"幂等跳过时根本没有落库"。 */
    var upsertCount: Int = 0
        private set

    /** 置为非 null 时 [getAll] 抛该异常，用于测检索层对存储故障的处理。 */
    var readFailure: Throwable? = null

    override suspend fun upsert(
        recordId: String,
        embedding: Embedding,
        now: Long,
        providerKey: String,
    ) {
        upsertCount++
        stored[recordId] = StoredEmbedding(
            recordId = recordId,
            model = embedding.model,
            vector = embedding.vector,
            providerKey = providerKey,
        )
    }

    override suspend fun getAll(model: String, providerKey: String): List<StoredEmbedding> {
        readFailure?.let { throw it }
        return stored.values.filter { candidate ->
            candidate.model == model &&
                (providerKey.isEmpty() ||
                    candidate.providerKey.isEmpty() ||
                    candidate.providerKey == providerKey)
        }
    }

    override suspend fun getByRecordId(recordId: String): StoredEmbedding? {
        readFailure?.let { throw it }
        return stored[recordId]
    }

    override suspend fun countIncompatible(model: String, providerKey: String): Int =
        stored.values.count { candidate ->
            candidate.model != model ||
                (providerKey.isNotEmpty() &&
                    candidate.providerKey.isNotEmpty() &&
                    candidate.providerKey != providerKey)
        }

    override suspend fun findIncompatibleRecordIds(
        model: String,
        providerKey: String,
        limit: Int,
    ): List<String> = stored.values
        .filter { candidate ->
            candidate.model != model ||
                (providerKey.isNotEmpty() &&
                    candidate.providerKey.isNotEmpty() &&
                    candidate.providerKey != providerKey)
        }
        .map { it.recordId }
        .take(limit)

    override suspend fun latestSpace(): EmbeddingSpace? =
        stored.values.maxByOrNull { it.recordId }?.let {
            EmbeddingSpace(model = it.model, providerKey = it.providerKey)
        }

    /** 直接塞一条向量（等价于"这条记录之前已经向量化过"）。 */
    fun seed(
        recordId: String,
        vector: FloatArray,
        model: String = "test-model",
        providerKey: String = "",
    ) {
        stored[recordId] = StoredEmbedding(
            recordId = recordId,
            model = model,
            vector = vector,
            providerKey = providerKey,
        )
    }

    /** 便于断言"存进去的向量确实等于 xx"：Base64 往返一次，绕开 FloatArray 的比较陷阱。 */
    fun encodedOf(recordId: String): String? =
        stored[recordId]?.let { VectorMath.encode(it.vector) }
}