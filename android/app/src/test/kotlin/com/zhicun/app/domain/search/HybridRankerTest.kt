package com.zhicun.app.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合检索的融合排序（M12，纯函数）。
 *
 * 要钉住的三件事：
 * 1. 只有一路有结果时，顺序必须与那一路的名次完全一致（否则 M10 的关键词排序会被打乱）；
 * 2. 两路都命中的记录排在只被一路命中的前面（RRF 的本意）；
 * 3. 同一输入的两次结果必须完全一致（平局有确定性的决出规则）。
 */
class HybridRankerTest {

    private fun semantic(vararg pairs: Pair<String, Double>) =
        pairs.map { ScoredRecordId(it.first, it.second) }

    @Test
    fun Fuse_onlyKeyword_keepsKeywordOrder() {
        val fused = HybridRanker.fuse(
            keywordIds = listOf("c", "a", "b"),
            semanticHits = emptyList(),
            limit = 10,
        )

        assertEquals(listOf("c", "a", "b"), fused)
    }

    @Test
    fun Fuse_onlySemantic_ordersBySemanticRank() {
        val fused = HybridRanker.fuse(
            keywordIds = emptyList(),
            semanticHits = semantic("x" to 0.91, "y" to 0.55, "z" to 0.31),
            limit = 10,
        )

        assertEquals(listOf("x", "y", "z"), fused)
    }

    @Test
    fun Fuse_recordHitByBothRanksAboveSingleSource() {
        // "both" 关键词第 5、语义第 5；"keywordOnly" 关键词第 1。
        // RRF：both = 1/66 + 1/66 ≈ 0.0303；keywordOnly = 1/61 ≈ 0.0164
        val fused = HybridRanker.fuse(
            keywordIds = listOf("keywordOnly") + (2..5).map { "k$it" } + listOf("both"),
            semanticHits = semantic(
                "s1" to 0.9, "s2" to 0.8, "s3" to 0.7, "s4" to 0.6, "both" to 0.5,
            ),
            limit = 10,
        )

        assertEquals("两路都命中的记录应排第一", "both", fused.first())
    }

    @Test
    fun Fuse_keywordHitWinsTieAgainstSemanticOnlyHit() {
        // 两者融合分完全相同（各在第 1 名），决胜规则是"有关键词命中的在前"
        val fused = HybridRanker.fuse(
            keywordIds = listOf("kw"),
            semanticHits = semantic("sem" to 0.99),
            limit = 10,
        )

        assertEquals(listOf("kw", "sem"), fused)
    }

    @Test
    fun Fuse_deduplicatesRecordHitByBothPaths() {
        val fused = HybridRanker.fuse(
            keywordIds = listOf("a", "b"),
            semanticHits = semantic("b" to 0.9, "a" to 0.8),
            limit = 10,
        )

        assertEquals("同一记录不能出现两次", 2, fused.size)
        // 两路各第 1、第 2 名 → 融合分相同 → 靠关键词名次决出：a 在关键词里更靠前
        assertEquals(listOf("a", "b"), fused)
    }

    @Test
    fun Fuse_respectsLimit() {
        val fused = HybridRanker.fuse(
            keywordIds = (1..10).map { "k$it" },
            semanticHits = semantic(*(11..20).map { "s$it" to 0.5 }.toTypedArray()),
            limit = 3,
        )

        assertEquals(3, fused.size)
    }

    @Test
    fun Fuse_zeroOrNegativeLimit_returnsEmpty() {
        assertTrue(HybridRanker.fuse(listOf("a"), emptyList(), limit = 0).isEmpty())
        assertTrue(HybridRanker.fuse(listOf("a"), emptyList(), limit = -1).isEmpty())
    }

    @Test
    fun Fuse_emptyInputs_returnsEmpty() {
        assertTrue(HybridRanker.fuse(emptyList(), emptyList(), limit = 10).isEmpty())
    }

    @Test
    fun Fuse_isDeterministicAcrossRuns() {
        val keywordIds = listOf("k1", "k2", "k3")
        val semanticHits = semantic("s1" to 0.5, "s2" to 0.5, "s3" to 0.5)

        val first = HybridRanker.fuse(keywordIds, semanticHits, limit = 6)
        val second = HybridRanker.fuse(keywordIds, semanticHits, limit = 6)

        assertEquals("同分必须有稳定的决出顺序，否则每次刷新列表都在跳", first, second)
        // 同分时"有关键词命中的在前"：k1 与 s1 同为第 1 名 → k1 先，k2/s2、k3/s3 同理
        assertEquals(listOf("k1", "s1", "k2", "s2", "k3", "s3"), first)
    }

    @Test
    fun Fuse_earlierSemanticRankOutranksLaterScore() {
        // 语义分本身不参与融合分的计算，只有名次参与：
        // 高名次（即使分数低一点）应当靠前，这正是 RRF 抗"分数不可比"的方式
        val fused = HybridRanker.fuse(
            keywordIds = emptyList(),
            semanticHits = semantic("first" to 0.40, "second" to 0.95),
            limit = 10,
        )

        assertEquals(listOf("first", "second"), fused)
    }

    // ------------------------------------------------------------ 二级重排信号（M17）

    /** 标题命中的记录要能翻过相邻的关键词名次（PRD §7.1 的标题信号）。 */
    @Test
    fun Fuse_titleMatch_breaksNearbyRankOrder() {
        val fused = HybridRanker.fuse(
            keywordIds = listOf("plain", "titled"),
            semanticHits = emptyList(),
            limit = 10,
            boost = RankBoost(titleMatchIds = setOf("titled")),
        )

        assertEquals("标题命中应顶到相邻名次之前", listOf("titled", "plain"), fused)
    }

    /**
     * 关键的护栏：加成封顶之后，**绝不能**让排最后的标题命中翻过排第一的普通命中。
     * 50 个名次的 RRF 总跨度远大于全部加成之和，这是"信号只能微调"的量化保证。
     */
    @Test
    fun Fuse_boostCannotOverturnLargeGap() {
        val many = (1..50).map { "k$it" }
        val fused = HybridRanker.fuse(
            keywordIds = many,
            semanticHits = emptyList(),
            limit = 50,
            boost = RankBoost(
                titleMatchIds = setOf("k50"),
                tagMatchIds = setOf("k50"),
                pinnedIds = setOf("k50"),
                favoriteIds = setOf("k50"),
            ),
        )

        assertEquals("末位的全加成翻不过第 1 名", "k1", fused.first())
        assertEquals("第 1 名也不能被挤出前三", true, fused.indexOf("k1") < 3)
    }

    /** 时间衰减只在显式传入"现在"时参与；`nowMillis = 0` 表示禁用（测试确定性用）。 */
    @Test
    fun Fuse_recencyDisabledWithoutNow() {
        val keywordIds = listOf("old", "new")
        val boost = RankBoost(createdAtById = mapOf("old" to 1L, "new" to 900L), nowMillis = 0L)

        val fused = HybridRanker.fuse(keywordIds, emptyList(), limit = 10, boost = boost)

        assertEquals("禁用时钟时名次就是一切", listOf("old", "new"), fused)
    }

    /** 显式给时钟后，明显更新（60 秒前 vs 60 天前）的记录在同分时排前面。 */
    @Test
    fun Fuse_recencyBoostsNewerRecordOnTie() {
        // old 与 new 都只在关键词路：名次不同（第 1 与第 2）。
        // 时间加成的"差值"要大于相邻名次差，明显更新的一条应排第一。
        val now = 5_184_000_000L // 60 天
        val fused = HybridRanker.fuse(
            keywordIds = listOf("old", "new"),
            semanticHits = emptyList(),
            limit = 10,
            boost = RankBoost(
                createdAtById = mapOf("old" to 0L, "new" to 5_183_900_000L),
                nowMillis = now,
            ),
        )

        assertEquals(listOf("new", "old"), fused)
    }

    /** 收藏与置顶是独立信号：单独给也应该生效。 */
    @Test
    fun Fuse_pinnedAlone_breaksNearbyRankOrder() {
        val fused = HybridRanker.fuse(
            keywordIds = listOf("plain", "pinned"),
            semanticHits = emptyList(),
            limit = 10,
            boost = RankBoost(pinnedIds = setOf("pinned")),
        )

        assertEquals(listOf("pinned", "plain"), fused)
    }
}
