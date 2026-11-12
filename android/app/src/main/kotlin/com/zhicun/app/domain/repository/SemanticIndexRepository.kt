package com.zhicun.app.domain.repository

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.search.ScoredRecordId

/**
 * 语义检索结果。
 *
 * @param hits 命中的 recordId 与相似度，按相似度降序
 * @param comparedCount **实际参与比较**的向量条数。为 0 表示"库里还没有可比对的向量"
 *   （首次使用、或刚换了 embedding 模型）。这与"有索引但没有命中"是两回事，
 *   界面上"为什么只有关键词结果"的解释也不同，所以必须显式区分出来。
 */
data class SemanticResult(
    val hits: List<ScoredRecordId>,
    val comparedCount: Int,
)

/**
 * 向量相似度检索（M12）。
 *
 * 单独抽一层是为了 T-003 的可插拔：当前实现是"全量载入 + 暴力余弦"，
 * 将来换 sqlite-vec / HNSW 只换实现类，`SearchRecordsUseCase` 与界面都不用动。
 */
interface SemanticIndexRepository {

    /**
     * @param query 查询向量（来自 [com.zhicun.app.domain.ai.Embedder]）
     * @param limit 命中的上限
     * @param providerKey 查询向量的来源标识（开源改造 P1）。只与"同模型 + 同来源"的
     *   库内向量比较 —— 同名模型在不同供应商上是不同部署，混算余弦只会得到噪声排序。
     *   空串 = 不限制来源（兼容没有来源信息的老向量）。
     */
    suspend fun search(query: Embedding, limit: Int, providerKey: String = ""): SemanticResult
}
