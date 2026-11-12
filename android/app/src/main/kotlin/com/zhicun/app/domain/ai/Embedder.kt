package com.zhicun.app.domain.ai

/**
 * 一段文本的语义向量（M12）。
 *
 * @param model 产出该向量的模型标识（网关回显，缺省为网关的固定名）。
 *   **必须跟着向量一起存**：不同模型的向量维度可能相同但空间完全不同，
 *   混在一起算余弦只会得到看似合理、实则无意义的排序。
 * @param vector 浮点向量；空数组表示"网关没给出向量"（由调用方决定怎么处理）
 */
data class Embedding(val model: String, val vector: FloatArray) {

    val dimensions: Int get() = vector.size

    val isEmpty: Boolean get() = vector.isEmpty()

    // data class + FloatArray 必须手写 equals/hashCode，否则比较的是数组引用
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Embedding) return false
        return model == other.model && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * model.hashCode() + vector.contentHashCode()
}

/**
 * 文本 → 向量的云端能力（M12，T-003 决策见 ARCHITECTURE.md §1.2 T-003）。
 *
 * 与 [ContentEnricher] 一样是接口而不是直接依赖 HTTP 实现：检索链路的
 * "融合排序 / 降级"规则是纯逻辑，值得用假实现穷举，不该被迫搭 MockWebServer。
 */
interface Embedder {

    /**
     * 产出向量的"向量空间身份"（开源改造 P0·模式 B）。
     *
     * 模型名相同但供应商/网关不同，向量可能来自不同部署，不能互相比较。
     * 官方模式填网关地址、直连模式填供应商地址；空串表示"身份未知"，
     * 存储层会把它当作通配（兼容改造前落库、没有身份信息的老向量）。
     *
     * 默认实现返回空串：测试假实现与"只关心文本→向量"的调用方无需关心这一层。
     */
    val providerKey: String
        get() = ""

    /**
     * @throws com.zhicun.app.domain.ai.GatewayNotConfiguredException 未配置网关
     * @throws com.zhicun.app.domain.ai.GatewayTransientException 可重试的网关/网络故障
     * @throws com.zhicun.app.domain.ai.GatewayRejectedException 确定性失败
     */
    suspend fun embed(text: String): Embedding

    /**
     * 搜索路径的查询向量化（2026-10-24 搜索卡顿整改）。
     *
     * 与 [embed] 的语义差异只在**时延预算**：用户正盯着搜索框等结果，实现应当用更紧的
     * 超时并尽量命中缓存，超时/失败由调用方按既有的"仅关键词结果"降级处理；
     * 而 [embed] 服务后台任务（记录入库时算向量），晚几秒无所谓，超时可以放宽。
     * 默认实现不做区分 —— 测试 fake 与其它实现零改动，HTTP 实现单独覆写。
     */
    suspend fun embedQuery(text: String): Embedding = embed(text)
}
