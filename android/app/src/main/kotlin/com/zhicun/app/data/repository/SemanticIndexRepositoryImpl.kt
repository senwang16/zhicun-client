package com.zhicun.app.data.repository

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.SemanticIndexRepository
import com.zhicun.app.domain.repository.SemanticResult
import com.zhicun.app.domain.search.ScoredRecordId
import com.zhicun.app.domain.search.VectorMath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语义检索：全量载入同模型向量，逐个算余弦（M12）。
 *
 * ## 为什么是暴力扫描而不是 ANN 索引
 * 个人知识库里"有向量的记录"是千级：扫 1000 条 × 1024 维 = 约 100 万次乘加，
 * 在手机上是个位到十几毫秒，比一次网络往返便宜得多。HNSW / IVF 这类索引
 * 要到十万级才划算，而十万级记录已经不是"个人知识库"了。因此 T-003 选的
 * 是"最笨但正确"的实现，接口留着（[SemanticIndexRepository]），
 * 量级真的上来时换实现即可，上层不用改。
 *
 * ## 相似度下限为什么必须有
 * 余弦对任意两段文本都给得出一个数，哪怕是毫不相关的两条也能有 0.2~0.35。
 * 不做截断的话，搜任何词都会返回"全部记录"，召回率看起来 100%，
 * 实际上把真正相关的那几条稀释掉了。
 *
 * 0.30 是**保守取值**（宁可少召回，也不要凑数）：它对 OpenAI 系与多数开源
 * 中文 embedding 模型都落在"弱相关以上"。换模型后应当重新标定，
 * 这也是它被写成 `internal const` 而不是散落魔数的原因。
 */
@Singleton
class SemanticIndexRepositoryImpl @Inject constructor(
    private val embeddingRepository: EmbeddingRepository,
) : SemanticIndexRepository {

    override suspend fun search(query: Embedding, limit: Int, providerKey: String): SemanticResult {
        if (query.isEmpty || limit <= 0) return SemanticResult(emptyList(), comparedCount = 0)

        // 只在同一向量空间里比较：模型与来源都要一致（v10 起）
        val stored = embeddingRepository.getAll(query.model, providerKey)
        if (stored.isEmpty()) return SemanticResult(emptyList(), comparedCount = 0)

        val hits = stored
            .mapNotNull { candidate ->
                val score = VectorMath.cosine(query.vector, candidate.vector)
                // 维度不符 / 全零 / NaN 都会被 cosine 归零，这里一并按"不参与排序"处理
                if (score >= MIN_SIMILARITY) ScoredRecordId(candidate.recordId, score) else null
            }
            // 分数降序；同分按 id 兜底，保证同一查询两次结果顺序一致
            .sortedWith(compareByDescending<ScoredRecordId> { it.score }.thenBy { it.recordId })
            .take(limit)

        return SemanticResult(hits = hits, comparedCount = stored.size)
    }

    companion object {
        /**
         * 低于该余弦值视为"不相关"，不进入融合排序。
         * 常量本体在 `VectorMath`（domain），M18 的相关推荐与这里共用同一个下限 ——
         * 两个入口给用户的相关性标准必须是同一个，否则"搜索里算相关的，推荐里又不算"。
         */
        const val MIN_SIMILARITY: Double = VectorMath.MIN_SIMILARITY
    }
}
