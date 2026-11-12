package com.zhicun.app.domain.search

/**
 * 语义检索命中：[recordId] 与查询向量的余弦相似度。
 */
data class ScoredRecordId(val recordId: String, val score: Double)

/**
 * 二级重排信号（M17，PRD §7.1"标题匹配、时间及标签"）。
 *
 * 全部字段可选：缺省构造时排序行为与 M12 完全一致，旧调用方零改动。
 * 信号由 `SearchRecordsUseCase` 从候选记录与其派生内容里收集，排序器保持纯函数。
 */
data class RankBoost(
    /** 标题（DerivedType.TITLE）含查询词的记录。 */
    val titleMatchIds: Set<String> = emptySet(),
    /** 任一标签名含查询词的记录。 */
    val tagMatchIds: Set<String> = emptySet(),
    /** 置顶的记录（M16）。 */
    val pinnedIds: Set<String> = emptySet(),
    /** 收藏的记录（M16）。 */
    val favoriteIds: Set<String> = emptySet(),
    /** 记录 id → 创建时间；配合 [nowMillis] 计算时间衰减。 */
    val createdAtById: Map<String, Long> = emptyMap(),

    /**
     * "现在"的时间戳（毫秒）。**0 表示禁用时间衰减**：调用方不传时排序完全确定，
     * 测试不会因为真实时钟漂移而翻结果。
     */
    val nowMillis: Long = 0L,
)

/**
 * 关键词结果与语义结果的融合排序（M12；M17 加入二级重排信号，纯函数）。
 *
 * ## 为什么用 RRF（Reciprocal Rank Fusion）而不是加权分数
 * 两条来源的分数**不可比**：关键词那一路只有名次（FTS4 没有 bm25，没有相似度），
 * 语义那一路是余弦（0~1，但不同模型的分布完全不同，0.6 在这家可能很高、在那家很普通）。
 * 硬把两者加权求和要先做归一化，而归一化参数没法在设备上标定。
 * RRF 只看名次（`1/(k+rank)`），不需要任何分数校准，是混合检索里最稳的做法。
 *
 * ## 二级信号怎么加（M17）
 * PRD §7.1 要求把标题匹配、时间、标签也作为排序信号，但它们的"强度"同样无法标定。
 * 做法：信号以 **RRF 单位的固定小比例**叠加到融合分上，全部加成封顶约等于
 * 每路 50 个名次**总跨度**（`SPREAD`）的三分之一。效果是：把"多路都沾边"
 * 与"只沾一路"的近似平局分开、把标题命中顶到前面，同时**绝不会**让一条相关性
 * 明显更低的结果翻到榜首 —— 那种翻转会毁掉用户对搜索的信任。
 *
 * ## 平局怎么定
 * `k = 60` 让名次差异被压缩得很平（第 1 名 1/61，第 10 名 1/70），
 * 因此平局很常见。按以下顺序决出先后：
 * 1. 融合分（含信号加成）高者先；
 * 2. **有关键词命中的在前** —— 用户明确敲的词出现了，比"模型觉得像"更可信；
 * 3. 关键词名次靠前者先；
 * 4. 语义分高者先；
 * 5. 最后按 id 字典序，保证同一输入两次检索顺序完全一致。
 */
object HybridRanker {

    /** RRF 的平滑常数。60 是原论文（Cormack et al. 2009）的推荐值。 */
    const val RRF_K: Int = 60

    /**
     * 单路名次上限：两路上限都来自 `SearchRecordsUseCase.DEFAULT_LIMIT = 50`，
     * `SPREAD` 用它来标定信号的量级，让"加成"与"名次差"可比。
     */
    private const val MAX_RANK: Int = 50

    /**
     * 单路名次的总跨度：第 1 名与第 50 名的分差。所有信号加成加起来
     * 不超过它的四分之一 —— 这是"信号只能微调、不能翻盘"的量化保证。
     */
    private val SPREAD: Double =
        1.0 / (RRF_K + 1) - 1.0 / (RRF_K + MAX_RANK + 1)

    private const val TITLE_WEIGHT = 0.12
    private const val TAG_WEIGHT = 0.06
    private const val PINNED_WEIGHT = 0.06
    private const val FAVORITE_WEIGHT = 0.03

    /**
     * 时间衰减的最大加成；实际值随记录变旧按 `30/(天数+30)` 衰减。
     * 0.06 × SPREAD 略大于相邻两个名次的 RRF 分差（1/61 − 1/62 ≈ 0.000264）：
     * 刚收集的新记录对"很久以前"的记录能恰好越过一位，但也只是一位 ——
     * "新鲜"是个微弱信号，两个都较新的记录之间它几乎不起作用。
     */
    private const val RECENCY_WEIGHT = 0.06

    private const val DAY_MILLIS: Double = 86_400_000.0

    /**
     * @param keywordIds 关键词命中（已按相关性排序）
     * @param semanticHits 语义命中（已按相似度降序，通常已过滤低分）
     * @param limit 结果上限
     * @param boost 二级重排信号，缺省时行为与 M12 一致
     */
    fun fuse(
        keywordIds: List<String>,
        semanticHits: List<ScoredRecordId>,
        limit: Int,
        boost: RankBoost = RankBoost(),
    ): List<String> {
        if (limit <= 0) return emptyList()
        return rank(keywordIds, semanticHits, boost).take(limit)
    }

    /** 完整排序（不截断）。供调用方先拿候选再收信号时复用。 */
    private fun rank(
        keywordIds: List<String>,
        semanticHits: List<ScoredRecordId>,
        boost: RankBoost,
    ): List<String> {
        val keywordRank = keywordIds.withIndex()
            .associate { (index, id) -> id to index }
        val semanticScore = semanticHits.associate { it.recordId to it.score }
        val semanticRank = semanticHits.withIndex()
            .associate { (index, hit) -> hit.recordId to index }

        // 只在一路里出现的记录也要参与（LinkedHashSet 顺带固定遍历顺序，便于排查）
        val candidates = LinkedHashSet<String>(keywordIds.size + semanticHits.size)
        candidates += keywordIds
        candidates += semanticHits.map { it.recordId }

        return candidates
            .sortedWith(
                compareByDescending<String> {
                    fused(it, keywordRank, semanticRank, boost)
                }
                    .thenBy { if (keywordRank.containsKey(it)) 0 else 1 }
                    .thenBy { keywordRank[it] ?: Int.MAX_VALUE }
                    .thenByDescending { semanticScore[it] ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it },
            )
    }

    private fun fused(
        id: String,
        keywordRank: Map<String, Int>,
        semanticRank: Map<String, Int>,
        boost: RankBoost,
    ): Double {
        var score = 0.0
        keywordRank[id]?.let { score += 1.0 / (RRF_K + it + 1) }
        semanticRank[id]?.let { score += 1.0 / (RRF_K + it + 1) }
        return score + boostOf(id, boost)
    }

    private fun boostOf(id: String, boost: RankBoost): Double {
        if (id !in boost.titleMatchIds &&
            id !in boost.tagMatchIds &&
            id !in boost.pinnedIds &&
            id !in boost.favoriteIds &&
            boost.nowMillis <= 0L
        ) {
            return 0.0
        }
        var bonus = 0.0
        if (id in boost.titleMatchIds) bonus += TITLE_WEIGHT * SPREAD
        if (id in boost.tagMatchIds) bonus += TAG_WEIGHT * SPREAD
        if (id in boost.pinnedIds) bonus += PINNED_WEIGHT * SPREAD
        if (id in boost.favoriteIds) bonus += FAVORITE_WEIGHT * SPREAD

        val createdAt = boost.createdAtById[id]
        if (boost.nowMillis > 0L && createdAt != null && createdAt < boost.nowMillis) {
            val ageDays = (boost.nowMillis - createdAt) / DAY_MILLIS
            bonus += RECENCY_WEIGHT * SPREAD * 30.0 / (ageDays + 30.0)
        }
        return bonus
    }
}
