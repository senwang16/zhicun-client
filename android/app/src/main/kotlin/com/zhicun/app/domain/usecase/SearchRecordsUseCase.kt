package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.SearchRepository
import com.zhicun.app.domain.repository.SemanticIndexRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.search.HybridRanker
import com.zhicun.app.domain.search.RankBoost
import com.zhicun.app.domain.search.ScoredRecordId
import com.zhicun.app.domain.search.SearchQuery
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * 检索结果。
 *
 * @param records 命中的记录，已按融合排序
 * @param keywordOnly 本次是否**只有关键词**在起作用。为 true 时界面要显示
 *   "当前为关键词搜索结果"——这句提示是诚实的钩子：用户搜不到某个东西时，
 *   需要知道"是库里没有"还是"语义检索今天没上班"。
 * @param degradeReason 语义检索为什么没参与（`null` = 正常参与或本来就不需要）。
 *   交给上层打日志：domain 层不引 `android.util.Log`，但也不能把失败烂在肚子里。
 */
data class SearchOutcome(
    val records: List<Record>,
    val keywordOnly: Boolean,
    val degradeReason: String? = null,
)

/**
 * 混合检索（M10 关键词 + M12 语义 + M17 二级重排）。
 *
 * ## 流程
 * 1. 归一化查询串（空 → 不检索，返回空而不是全部记录）
 * 2. 关键词路：FTS4 + 子串（[SearchRepository]）
 * 3. 语义路：把查询串向量化 → 在库里扫同模型向量 → 高于下限的命中
 *    （[Embedder] + [SemanticIndexRepository]）
 * 4. 收集二级信号（M17）：标题命中、标签命中、置顶/收藏、时间衰减 ——
 *    只对**候选集**生效；两条信号查询（全部 TITLE / 全部标签关联）本身是全量读、
 *    内存里按候选集过滤，个人知识库量级毫秒级 —— 不再为信号做额外的候选集专用查询
 * 5. [HybridRanker] 用 RRF 融合两路名次，叠加信号微调
 * 6. 一次性取实体（`getByIds`），并按融合顺序重排（SQL 不保证顺序）
 *
 * ## 语义路的失败一律降级，不上抛
 * 检索是"用户正在输入时反复触发"的高频动作，为了一个语义服务的 5xx 弹出错误页
 * 属于把可选能力当成了硬依赖。语义失败 → 只剩关键词结果 + 明确提示，
 * 这比"搜索报错"对用户有用得多。
 *
 * 唯一的例外是 [CancellationException]：它代表调用方主动取消（用户接着敲了下一个字），
 * 必须原样抛出，否则协程的取消语义会被吞掉、旧结果反而可能写回界面。
 *
 * ## 信号收集也必须能降级
 * 标题/标签查询失败不该毁掉一次本来成功的检索：信号只是微调，
 * 拿不到就按"没有信号"排序（与 M12 一致）。否则一个辅助信号把整个搜索变成 500，
 * 是本末倒置。
 */
class SearchRecordsUseCase @Inject constructor(
    private val searchRepository: SearchRepository,
    private val recordRepository: RecordRepository,
    private val embedder: Embedder,
    private val semanticIndex: SemanticIndexRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val tagRepository: TagRepository,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
) {

    suspend operator fun invoke(
        raw: String?,
        limit: Int = DEFAULT_LIMIT,
        /** 显式传入便于测试固定；生产用真实时钟。0 = 禁用时间衰减信号。 */
        nowMillis: Long = System.currentTimeMillis(),
    ): SearchOutcome {
        val normalized = SearchQuery.normalize(raw)
            ?: return SearchOutcome(emptyList(), keywordOnly = true)

        val keywordIds = searchRepository.searchIds(normalized, limit)
        return fusedOutcome(normalized, keywordIds, limit, nowMillis)
    }

    /**
     * 两段式检索（2026-10-24 搜索卡顿整改）：同一查询发射两次结果。
     *
     * - **第一次**：只跑关键词路（FTS/子串，本地毫秒级），不做语义、不收集二级信号 ——
     *   这一段的唯一目标是"让界面立刻有内容"。排序即关键词相关度，
     *   置顶/收藏等微调属于锦上添花，留给终态。
     * - **第二次**：补语义路（云端向量化，秒级网络往返）与二级信号，输出最终融合排序。
     *   关键词命中**复用**第一次的结果，FTS/LIKE 不查第二遍 —— 检索是每敲一个字
     *   都要跑的高频操作，代价不能翻倍。
     *
     * 背景：语义路的网络往返实测 2~3.5 秒，此前两路串行合并、一次性返回，
     * 界面在本地结果早已就绪的情况下跟着网络一起等。
     *
     * 排序在第二次发射时刷新一次（条目可能移动）是该模式的固有行为；
     * 调用方取消收集（用户输入了新词）即丢弃未完成的第二段。
     */
    fun searchStaged(
        raw: String?,
        limit: Int = DEFAULT_LIMIT,
        nowMillis: Long = System.currentTimeMillis(),
    ): Flow<SearchOutcome> = flow {
        val normalized = SearchQuery.normalize(raw) ?: return@flow

        val keywordIds = searchRepository.searchIds(normalized, limit)
        val byId = if (keywordIds.isEmpty()) {
            emptyMap()
        } else {
            recordRepository.getByIds(keywordIds).associateBy { it.id }
        }
        emit(
            SearchOutcome(
                records = keywordIds.mapNotNull { byId[it] },
                // 第一段的 keywordOnly 恒为 true（语义还没参与）；是否当作"降级"展示由调用方
                // 结合"语义还在路上"的上下文决定，不由这里谎报
                keywordOnly = true,
            ),
        )
        emit(fusedOutcome(normalized, keywordIds, limit, nowMillis))
    }

    /** 语义路 + 二级信号 + RRF 融合；两段式的第二段与一次性 [invoke] 共用。 */
    private suspend fun fusedOutcome(
        normalized: String,
        keywordIds: List<String>,
        limit: Int,
        nowMillis: Long,
    ): SearchOutcome {
        val semantic = runSemantic(normalized, limit)

        // 候选集 = 两路命中之并。信号只在这个小集合里收集，
        // 不做任何全库扫描 —— 检索是输入框里每敲一个字都要跑的高频操作
        val candidates = LinkedHashSet<String>(keywordIds.size + semantic.hits.size)
        candidates += keywordIds
        candidates += semantic.hits.map { it.recordId }

        val byId = if (candidates.isEmpty()) {
            emptyMap()
        } else {
            recordRepository.getByIds(candidates.toList()).associateBy { it.id }
        }

        val ids = HybridRanker.fuse(
            keywordIds = keywordIds,
            semanticHits = semantic.hits,
            limit = limit,
            boost = collectSignals(candidates, normalized, byId, nowMillis),
        )

        return SearchOutcome(
            // mapNotNull 既是保序，也顺手过滤掉"检索后被删掉"的 id
            records = ids.mapNotNull { byId[it] },
            keywordOnly = semantic.degradeReason != null,
            degradeReason = semantic.degradeReason,
        )
    }

    /**
     * 收集二级重排信号。任何一步失败都返回空信号 —— 见类注释"信号收集也必须能降级"。
     *
     * 为什么标题匹配在 domain 层做子串判断而不是让 FTS 判：标题不是 FTS 的行，
     * 它是派生内容；为它再开一条查询链路属于过度设计。
     * 查询串已经归一化过，这里两边都转小写即可（中文不受大小写影响）。
     */
    private suspend fun collectSignals(
        candidates: LinkedHashSet<String>,
        normalized: String,
        byId: Map<String, Record>,
        nowMillis: Long,
    ): RankBoost {
        if (candidates.isEmpty()) return RankBoost()

        return try {
            val query = normalized.lowercase()
            val titleMatchIds = derivedContentRepository
                .getByType(DerivedType.TITLE)
                .filter { it.recordId in candidates }
                .filter { query in (it.content ?: "").lowercase() }
                .map { it.recordId }
                .toSet()
            val tagMatchIds = tagRepository.observeAllWithRecord()
                .first()
                .filter { it.recordId in candidates }
                .filter { query in it.tag.name.lowercase() }
                .map { it.recordId }
                .toSet()

            RankBoost(
                titleMatchIds = titleMatchIds,
                tagMatchIds = tagMatchIds,
                pinnedIds = byId.values.filter { it.isPinned }.map { it.id }.toSet(),
                favoriteIds = byId.values.filter { it.isFavorite }.map { it.id }.toSet(),
                createdAtById = byId.values.associate { it.id to it.createdAt },
                nowMillis = nowMillis,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 与语义路同款降级：辅助信号拿不到就当没有，主结果不能被它拖死
            RankBoost()
        }
    }

    /**
     * 语义路。返回的 `degradeReason` 为 null 表示语义检索**确实参与了**。
     *
     * "参与了"的判据是"至少比较过一条向量"（`comparedCount > 0`）：
     * 索引为空时虽然没报错，但结果完全来自关键词，界面必须如实说明。
     */
    private suspend fun runSemantic(normalized: String, limit: Int): SemanticAttempt {
        // 搜索词本身也是"会被发到云端的内容"：语义路必须先过这道闸。
        // 判据走 `CloudUploadGate` 而不是自己写 `if (!cloudAiEnabled)`（2026-10-23 审查 R-8）：
        // 此前只判了开关、漏了"网关未配置"，靠 HTTP 层兜底看起来也没事，
        // 但那正是"同一件事两份判据"的老路子 —— 下次改判据时必然只改一处。
        val blocked = CloudUploadGate.cloudPathBlockedReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
        )
        if (blocked != null) {
            // 文案直接用门禁给的那句：两处各写一遍迟早不一致（keywordOnly 已表达"仅关键词"）
            return SemanticAttempt(emptyList(), blocked.message)
        }
        return try {
            // 搜索路用 embedQuery（2026-10-24 搜索卡顿整改）：快超时 + 查询向量缓存。
            // 失败在这里被降级为"仅关键词"，用户等的是结果而不是语义
            val query = embedder.embedQuery(normalized)
            if (query.isEmpty) {
                SemanticAttempt(emptyList(), "网关未返回查询向量")
            } else {
                val result = semanticIndex.search(query, limit, embedder.providerKey)
                SemanticAttempt(
                    hits = result.hits,
                    degradeReason = if (result.comparedCount == 0) "本地还没有可比对的向量" else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不吞错：原因带回给上层写日志，界面也据此显示降级说明
            SemanticAttempt(emptyList(), "语义检索不可用：${e.javaClass.simpleName}")
        }
    }

    private data class SemanticAttempt(
        val hits: List<ScoredRecordId>,
        val degradeReason: String?,
    )

    companion object {
        /**
         * 单次检索上限 50：个人知识库一次看 50 条足够，再多用户也不会往下翻，
         * 而语义路每次要多算 50 条余弦、界面也要多渲染 50 张卡。
         */
        const val DEFAULT_LIMIT: Int = 50
    }
}
