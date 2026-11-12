package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.Embedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 查询向量 LRU 缓存（2026-10-24 搜索卡顿整改配套）。
 *
 * 钉四件事：命中不丢、按容量淘汰最久未用、不同网关的身份键必须隔离、
 * 换 embedding 模型后旧条目必须整体作废（r4 审查 L-4）。
 */
class QueryEmbeddingCacheTest {

    private fun embedding(seed: Float, model: String = "m") =
        Embedding(model = model, vector = floatArrayOf(seed))

    @Test
    fun Get_afterPut_returnsSameEmbedding() {
        val cache = QueryEmbeddingCache()
        val key = QueryEmbeddingCache.keyOf("https://gw#a", "查询词")

        cache.put(key, embedding(1f), modelSignature = "gw#m")

        assertEquals(embedding(1f), cache.get(key))
    }

    @Test
    fun Put_beyondCapacity_evictsLeastRecentlyUsed() {
        val cache = QueryEmbeddingCache(capacity = 2)
        val k1 = QueryEmbeddingCache.keyOf("gw", "词1")
        val k2 = QueryEmbeddingCache.keyOf("gw", "词2")
        val k3 = QueryEmbeddingCache.keyOf("gw", "词3")

        cache.put(k1, embedding(1f), modelSignature = "gw#m")
        cache.put(k2, embedding(2f), modelSignature = "gw#m")
        // 访问 k1：淘汰的必须是 k2（最久未用），而不是最先写入的 k1
        cache.get(k1)
        cache.put(k3, embedding(3f), modelSignature = "gw#m")

        assertEquals(embedding(1f), cache.get(k1))
        assertNull("LRU 应淘汰最久未用的 k2", cache.get(k2))
        assertEquals(embedding(3f), cache.get(k3))
    }

    @Test
    fun KeyOf_differentGateways_doNotCollide() {
        val a = QueryEmbeddingCache.keyOf("https://one#111", "同一查询词")
        val b = QueryEmbeddingCache.keyOf("https://two#222", "同一查询词")

        org.junit.Assert.assertNotEquals(
            "换网关后旧缓存向量不得命中（embedding 空间不同，混算余弦是噪声排序）",
            a,
            b,
        )
    }

    @Test
    fun Put_withDifferentModel_invalidatesAllOldEntries() {
        val cache = QueryEmbeddingCache()
        val oldWord = QueryEmbeddingCache.keyOf("gw", "旧词")

        cache.put(oldWord, embedding(1f, model = "bge-m3"), modelSignature = "gw#bge-m3")
        // 网关没换、只换了 embedding 模型：键不变，靠签名把旧模型的条目全部作废
        cache.put(
            QueryEmbeddingCache.keyOf("gw", "新词"),
            embedding(2f, model = "new-model"),
            modelSignature = "gw#new-model",
        )

        assertNull(
            "换模型后旧模型的缓存必须作废（不同空间的向量命中即噪声排序）",
            cache.get(oldWord),
        )
        assertEquals(
            embedding(2f, model = "new-model"),
            cache.get(QueryEmbeddingCache.keyOf("gw", "新词")),
        )
    }

    /**
     * 命中路径必须能"自然过期"（2026-10-24 deepseek 审查 P1）。
     *
     * 签名只在 put 时更新；如果条目永不过期，固定搜同一个词的用户会一直命中旧模型的
     * 向量，永远没有机会发请求学到"模型已经换了"。命中也不得延长寿命 ——
     * 否则高频搜索的词会永久不过期，问题原样存在。
     */
    @Test
    fun Get_afterTtl_isMissAndHitDoesNotExtendLifetime() {
        var now = 0L
        val cache = QueryEmbeddingCache(ttlMs = 1_000, nowMs = { now })
        val key = QueryEmbeddingCache.keyOf("gw", "同一个词")
        cache.put(key, embedding(1f), modelSignature = "gw#old")

        now = 1_000
        assertEquals("到期边界（== TTL）仍应命中", embedding(1f), cache.get(key))

        now = 1_001
        assertNull(
            "超过 TTL 必须作废，强制发一次请求拿回新模型名",
            cache.get(key),
        )
    }

    @Test
    fun Put_withSameModel_keepsExistingEntries() {
        val cache = QueryEmbeddingCache()
        val word = QueryEmbeddingCache.keyOf("gw", "词")

        cache.put(word, embedding(1f), modelSignature = "gw#m")
        cache.put(QueryEmbeddingCache.keyOf("gw", "另一个词"), embedding(2f), modelSignature = "gw#m")

        assertEquals("同模型写入不得误清已有条目", embedding(1f), cache.get(word))
    }
}
