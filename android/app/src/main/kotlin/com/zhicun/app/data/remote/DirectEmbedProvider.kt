package com.zhicun.app.data.remote

import android.util.Log
import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.ai.GatewayRejectedException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 直连 OpenAI 兼容 Embeddings 的向量化实现（开源改造 P0·模式 B）。
 *
 * 协议差异比 Chat 小得多，真正的风险在**向量空间**而不是解析：
 * 换模型 → 旧向量与新向量不可比 → 语义检索必须按模型分组（见 `EmbeddingRepository`）。
 * 本类只负责"把这段文字换成一条向量"，分组与重建由存储层和设置页负责。
 *
 * ## 三条约束
 * 1. `model` 缺省用**用户填的模型名**：部分供应商不回显 model 字段，
 *    若这里编一个默认名，库里同一批向量会被切成两组、语义检索莫名少一半
 *    （`RemoteEmbedder` 踩过同一个坑，见其 DEFAULT_MODEL 注释）。
 * 2. 空 `data` / 空数组 = "这段文本拿不到向量"，是正常业务结果，不抛异常
 *    （纯符号、超短文本时常见）。
 * 3. 维度超过 [MAX_DIMENSIONS] = Provider 配错了（多半把 Chat 端点填成了 Embedding），
 *    按终态失败上报并说清楚，避免把几十 MB 的向量写进数据库。
 */
@Singleton
class DirectEmbedProvider @Inject constructor(
    private val gateway: AiGatewayHttp,
    private val settings: AiProviderSettings,
) : Embedder {

    private val queryCache = QueryEmbeddingCache()

    /**
     * 向量空间的身份 = 供应商地址。
     *
     * 与官方模式（网关地址）是同一层级的概念：同一家供应商的同名模型，
     * 换一个地址（代理/中转站）也可能落到不同部署上，不能当同一个空间。
     */
    override val providerKey: String
        get() = settings.embedConfig?.baseUrl.orEmpty()

    override suspend fun embed(text: String): Embedding =
        gateway.execute(buildRequest(text)) { parse(it) }

    /**
     * 搜索路径：先查缓存，未命中走快超时。
     *
     * 缓存身份键必须带供应商地址：换供应商后旧查询向量与新库内向量不可比，
     * 继续命中只会得到"看似有排序、实则噪声"的结果（与官方模式同一条纪律）。
     */
    override suspend fun embedQuery(text: String): Embedding {
        val config = settings.embedConfig ?: throw GatewayNotConfiguredException()
        val identity = "direct:${config.baseUrl}"
        val key = QueryEmbeddingCache.keyOf(identity, text)
        queryCache.get(key)?.let { return it }

        val embedding = gateway.executeFast(buildRequest(text)) { parse(it) }
        if (!embedding.isEmpty) {
            queryCache.put(key, embedding, modelSignature = "$identity#${embedding.model}")
        }
        return embedding
    }

    private fun buildRequest(text: String): Request {
        val config = settings.embedConfig ?: throw GatewayNotConfiguredException()
        val payload = gateway.json.encodeToString(EmbedRequest(model = config.model, input = text))
        return gateway.postProvider(
            config = config,
            path = EMBED_PATH,
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
            contentType = JSON_CONTENT_TYPE,
        )
    }

    private fun parse(body: String): Embedding {
        val response = gateway.json.decodeFromString(EmbedResponse.serializer(), body)
        val vector = response.data.firstOrNull()?.embedding.orEmpty()
        if (vector.size > MAX_DIMENSIONS) {
            throw GatewayRejectedException(
                code = "EMBEDDING_TOO_LARGE",
                message = "向量维度异常（${vector.size}），请检查 API 地址是否填成了对话接口",
            )
        }
        val model = response.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.embedConfig?.model.orEmpty()
        if (vector.isEmpty()) {
            Log.i(TAG, "供应商未返回向量 model=$model")
        }
        return Embedding(model = model, vector = vector.toFloatArray())
    }

    @Serializable
    private data class EmbedRequest(
        val model: String,
        val input: String,
    )

    @Serializable
    private data class EmbedResponse(
        val data: List<EmbedItem> = emptyList(),
        val model: String? = null,
    )

    @Serializable
    private data class EmbedItem(
        val embedding: List<Float> = emptyList(),
        val index: Int = 0,
    )

    companion object {
        /** 设置页 & 文档共用：相对用户填的 baseUrl。 */
        const val EMBED_PATH: String = "/embeddings"

        /** 与服务端 `MAX_EMBEDDING_DIMENSIONS` 对齐：主流模型最大 3072 维，8192 是上限余量。 */
        const val MAX_DIMENSIONS: Int = 8192

        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()
        private const val TAG = "DirectEmbedProvider"
    }
}