package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.search.ScoredRecordId
import com.zhicun.app.domain.search.VectorMath
import javax.inject.Inject

/**
 * 一条相关记录及其与当前记录的相似度。
 *
 * 分数随记录一起返回而不是只返回排序后的列表：界面要显示"为什么它在这儿"。
 * 不显示分数时，用户只能看到五条来路不明的卡片，"相关"就退化成一个无法验证的断言（2026-10-24）。
 */
data class RelatedRecord(
    val record: Record,
    val score: Double,
)

/**
 * 相关记录推荐的结果。
 *
 * [total] 是**过线的候选总数**，[items] 是截断后用于展示的前
 * [GetRelatedRecordsUseCase.DEFAULT_LIMIT] 条。两者分开给：
 * 只返回 top-N 时，"一共就 5 条"与"有 40 条只显示了 5 条"在界面上完全一样，
 * 用户没法判断这分区是"就这么多"还是"被截断了"。
 */
data class RelatedRecords(
    val items: List<RelatedRecord> = emptyList(),
    val total: Int = 0,
) {
    /** 空结果：调用方据此**整块不渲染**，而不是留一个"暂无相关记录"的空分区。 */
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 与某条记录最相似的历史记录（M18，详情页"相关记录"分区）。
 *
 * ## 为什么复用语义索引的向量
 * 向量已经在 `derived_contents`（T-003），"找相似的"和"按查询找"在数学上是同一件事：
 * 换掉查询向量（query → 当前记录的向量）即可。零新依赖、零数据库迁移，
 * 代价只是多一次全量余弦扫描（千级记录十几毫秒，进入详情页时跑一次，可接受）。
 *
 * ## 为什么推荐侧的下限比搜索高（2026-10-24 复审推翻旧决策）
 * 旧决策是"搜索与推荐共用一个 `VectorMath.MIN_SIMILARITY`（0.30），否则会出现
 * 搜索里算相关、推荐里又不算的矛盾"。实测下来这个统一标准是把**两种相反的预期**
 * 强行按同一个数：
 * - 搜索是用户**主动找**，宁多勿漏，0.30 的松下限是对的（漏召回比多召回更伤）；
 * - 推荐是用户**没找**、系统主动塞给他的，宁少勿滥 —— 凑不满宁可只给两条。
 *
 * 更要紧的是线上用的 embedding 是 bge-m3（见 `server/`），这类模型对无关文本对
 * 给出的余弦基线本身偏高，0.30 实际上几乎不筛：五条里混进两三条弱相关的，
 * 用户看到的就是"这推荐很随意"。所以推荐侧单独用 [RECOMMEND_MIN_SIMILARITY]。
 *
 * 换 embedding 模型时**两个数都要重标**，它们不是同一个事实。
 *
 * ## 失败一律返回空，不上抛
 * 相关推荐是**附加信息**：向量库坏了、记录没有向量、取实体失败，都应该只是
 * "这个分区不出现"，绝不能让详情页从"看得到原文"变成 Error 页。
 */
class GetRelatedRecordsUseCase @Inject constructor(
    private val embeddingRepository: EmbeddingRepository,
    private val recordRepository: RecordRepository,
) {

    suspend operator fun invoke(recordId: String, limit: Int = DEFAULT_LIMIT): RelatedRecords {
        if (recordId.isBlank() || limit <= 0) return RelatedRecords()

        val self = embeddingRepository.getByRecordId(recordId) ?: return RelatedRecords()

        // 用同一条记录的来源一起过滤：同名模型在不同供应商上的向量不可比（v10）
        val hits = embeddingRepository.getAll(self.model, self.providerKey)
            .filter { it.recordId != recordId }
            .mapNotNull { candidate ->
                val score = VectorMath.cosine(self.vector, candidate.vector)
                // 维度不符 / 全零 / NaN 会被 cosine 归零，自然落在下限之外
                if (score >= RECOMMEND_MIN_SIMILARITY) {
                    ScoredRecordId(candidate.recordId, score)
                } else {
                    null
                }
            }
            // 同分按 id 兜底：与语义搜索同款确定性规则
            .sortedWith(compareByDescending<ScoredRecordId> { it.score }.thenBy { it.recordId })

        if (hits.isEmpty()) return RelatedRecords()

        // 只取展示需要的这 N 条实体；total 用截断前的候选数，
        // 免得为了数一个数字把上千条记录全部读进内存
        val top = hits.take(limit)
        // getAll 已排除软删除；getByIds 再滤一次"向量还在、记录刚被删"的窗口期
        val byId = recordRepository.getByIds(top.map { it.recordId }).associateBy { it.id }
        return RelatedRecords(
            items = top.mapNotNull { hit -> byId[hit.recordId]?.let { RelatedRecord(it, hit.score) } },
            total = hits.size,
        )
    }

    companion object {
        /**
         * 推荐上限 5：这是详情页底部的一个辅助分区，不是第二张收件箱 ——
         * 给五六个"可能相关"的入口刚刚好，再多就把用户的注意力拖走了。
         */
        const val DEFAULT_LIMIT: Int = 5

        /**
         * 相关推荐的相似度下限（**与搜索的 0.30 是两个事实**，见类注释）。
         *
         * 0.45 是保守起点：宁可推荐分区经常只有两三条，也不要五条里混着三条噪声。
         * 卡片上会显示每条的实际分数，线上跑一段时间后用真实分布再校准这个值 ——
         * 它比任何拍脑袋的数字都可靠。
         */
        const val RECOMMEND_MIN_SIMILARITY: Double = 0.45
    }
}
