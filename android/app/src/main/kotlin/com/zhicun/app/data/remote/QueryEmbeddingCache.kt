package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.Embedding

/**
 * 查询向量的 LRU 缓存（2026-10-24 搜索卡顿整改）。
 *
 * 搜索是"每敲一个词就要跑"的高频操作，而同一个词的向量是确定的 ——
 * 重复搜索（改一个字再改回来、来回切筛选）不该每次都付 2~3.5 秒的云端往返。
 * 只缓存**查询侧**：库内记录的向量在 [com.zhicun.app.data.repository.EmbeddingRepositoryImpl]，
 * 两者生命周期与失效条件完全不同，不共用。
 *
 * 纯内存、容量个位数到十几：个人知识库用户一次搜索会话里重复的查询词有限，
 * 不值得为它引入持久化（写盘还要考虑模型变更后的失效，得不偿失）。
 *
 * ## 失效纪律：身份键 + 模型签名 + TTL（2026-10-24 r4/deepseek 审查 L-4）
 * 缓存键带网关身份（换地址/令牌后旧键天然不命中），但**网关换 embedding 模型**
 * （只改部署 vars，地址令牌都不动）时键不变 —— 旧模型的查询向量与新模型的库内向量
 * 不可比，继续命中只会得到"看似合理、实则噪声"的排序。因此 [put] 携带
 * `modelSignature`（网关身份 + 模型名），签名一变，旧条目**整体作废**。
 *
 * 但签名只在 [put] 时更新：命中路径不发请求，就永远学不到模型已经换了。
 * 固定搜索同一个词的用户会一直命中旧条目，直到碰巧有一个未命中的词触发 [put]。
 * 因此条目带 [ttlMs]：过期视同未命中，必然发一次请求 —— 拿回新模型名后，
 * [put] 的签名比对会顺带清掉其它旧条目。代价是最多 TTL 长的静默降级窗口。
 *
 * 线程安全：搜索（主线程发起的协程）与后台任务可能并发访问，用同步块足够 ——
 * 操作是纯内存的纳秒级，竞争窗口可忽略。
 *
 * @param nowMs 时钟注入点，测试用它推进时间；生产走系统墙钟。
 */
internal class QueryEmbeddingCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val embedding: Embedding, val storedAtMs: Long)

    // accessOrder=true：get 也算"使用"，淘汰的是最久未被查询的词，不是最久未写入的
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    /** 当前缓存条目所属的"网关身份 + 模型"签名；null = 还没有任何条目。 */
    private var modelSignature: String? = null

    fun get(key: String): Embedding? = synchronized(map) {
        val entry = map[key] ?: return@synchronized null
        if (nowMs() - entry.storedAtMs > ttlMs) {
            map.remove(key)
            return@synchronized null
        }
        entry.embedding
    }

    /**
     * 写入一条查询向量。[modelSignature] 由调用方用**同一份**网关身份键 + 向量自带的
     * 模型名拼出；与已存签名不同（换网关或换模型）时，先清空旧条目再写入 ——
     * 不同空间的向量混在一个缓存里，命中的每一条都是噪声。
     */
    fun put(key: String, value: Embedding, modelSignature: String) = synchronized(map) {
        if (this.modelSignature != null && this.modelSignature != modelSignature) {
            map.clear()
        }
        this.modelSignature = modelSignature
        map[key] = Entry(value, nowMs())
    }

    fun clear() = synchronized(map) {
        map.clear()
        modelSignature = null
    }

    companion object {
        private const val DEFAULT_CAPACITY = 16

        /**
         * 条目存活时间。30 分钟是"模型被换掉"与"重复搜索省一次云端往返"之间的折中：
         * 个人知识库的一次连续搜索会话远短于它，而同词反复搜索仍能全部命中。
         */
        private const val DEFAULT_TTL_MS = 30 * 60 * 1000L

        /**
         * 缓存键 = 网关身份 + 查询词。**必须带网关身份**：换网关/换 embedding 模型后，
         * 旧向量的空间与库内新向量不可比，混算余弦只会得到看似合理、实则噪声的排序
         * （与 [com.zhicun.app.data.repository.EmbeddingRepositoryImpl] 按 processorVersion
         * 过滤是同一条纪律）。查询词放后面，理论上可读性无关紧要，仅利于调试。
         * 模型层面的失效不靠键（见类注释的 modelSignature），键只管网关身份。
         */
        fun keyOf(gatewayIdentity: String, text: String): String = "$gatewayIdentity\n$text"
    }
}
