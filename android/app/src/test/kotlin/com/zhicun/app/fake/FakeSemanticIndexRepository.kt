package com.zhicun.app.fake

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.repository.SemanticIndexRepository
import com.zhicun.app.domain.repository.SemanticResult
import com.zhicun.app.domain.search.ScoredRecordId

/**
 * 语义检索假实现（M12）。
 *
 * 不做任何余弦计算：向量相似度的正确性由 `VectorMathTest` 与 `SemanticIndexRepositoryImplTest`
 * 覆盖，这里只负责让 `SearchRecordsUseCase` 的"融合 + 降级"规则可以被穷举，
 * 不被"假向量算出的分数"干扰。
 */
class FakeSemanticIndexRepository : SemanticIndexRepository {

    /** 预置命中（按期望顺序）。 */
    var hits: List<ScoredRecordId> = emptyList()

    /** 预置"实际参与比较的向量条数"；0 表示索引为空。 */
    var comparedCount: Int = 0

    /** 置为非 null 时 [search] 抛该异常，用于测降级分支。 */
    var failure: Throwable? = null

    /** 记录每次调用的 (查询向量, limit, 来源)。 */
    val calls: MutableList<Triple<Embedding, Int, String>> = mutableListOf()

    override suspend fun search(query: Embedding, limit: Int, providerKey: String): SemanticResult {
        calls += Triple(query, limit, providerKey)
        failure?.let { throw it }
        return SemanticResult(hits = hits, comparedCount = comparedCount)
    }
}
