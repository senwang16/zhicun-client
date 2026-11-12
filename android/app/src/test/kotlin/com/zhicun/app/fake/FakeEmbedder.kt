package com.zhicun.app.fake

import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.Embedding
import kotlinx.coroutines.CompletableDeferred

/**
 * 向量化假实现（M12）。
 *
 * 默认返回一个固定的 4 维向量：维度小是为了让断言失败时的消息可读，
 * 与真实网关的 768/1024 维没有任何关系 —— 被测逻辑（幂等、门禁、降级）
 * 都不依赖具体维度。
 */
class FakeEmbedder : Embedder {

    /** 每次调用的入参文本，用于断言"送去向量化的是什么"。 */
    val calls: MutableList<String> = mutableListOf()

    var result: Embedding = Embedding(model = "test-model", vector = floatArrayOf(1f, 0f, 0f, 0f))

    /** 置为非 null 时 [embed] 抛该异常，用于测降级与重试分类。 */
    var failure: Throwable? = null

    /**
     * 置为非 null 时 [embed] 挂起直到该 Deferred 完成：测两段式检索的先后顺序
     * （第一段关键词结果应先于向量化返回到达界面）。
     */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun embed(text: String): Embedding {
        calls += text
        failure?.let { throw it }
        gate?.let { it.await() }
        return result
    }
}
