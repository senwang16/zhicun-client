package com.zhicun.app.domain.ai

/**
 * 一次"内容增强"的结果（M11）。
 *
 * 三个字段都可能为空：模型选出不来标题、正文太短没有摘要、标签为空，都是**正常结果**，
 * 不是失败。把它们当失败会让大量记录挂红，用户反而分不清"真的坏了"和"没什么好总结的"。
 */
data class Enrichment(
    val title: String?,
    val summary: String?,
    val tags: List<String>,
) {
    /** 三项全空 = 这次调用没有产生任何可用内容（文本太短/全是符号时可能发生）。 */
    val isEmpty: Boolean
        get() = title.isNullOrBlank() && summary.isNullOrBlank() && tags.isEmpty()
}

/**
 * 云端内容增强能力（摘要 + 短标题 + 标签）。
 *
 * 抽成接口的原因与 [SpeechTranscriber] 相同：Worker 的判定逻辑（敏感门禁、空文本、
 * 上游未就绪）要能在 JVM 上穷举，不能让测试依赖真实网关。
 *
 * 约定：
 * - **不返回 null**，字段空用 null/空列表表达；
 * - 失败用异常表达，分类交给 `AiGatewayHttp`（可重试 / 终态）；
 * - 输入文本由调用方截断，实现方不再二次裁剪（避免两处上限不一致）。
 */
interface ContentEnricher {

    /**
     * @param text 待处理的正文（已由调用方拼接与截断）
     * @throws GatewayNotConfiguredException 网关未配置（终态）
     * @throws GatewayRejectedException 内容被拒 / 鉴权失败（终态）
     * @throws GatewayTransientException 网络或限流（可重试）
     */
    suspend fun enrich(text: String): Enrichment
}
