package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.Embedding
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 云端向量化（M12）：`POST {base}/api/v1/embed`。
 *
 * 请求：`{"text":"..."}`。响应：
 * - `{"embedding":[0.01,-0.23,...],"model":"text-embedding-3-small"}`
 * - `{"result":{"embedding":[...],"model":"..."}}`（与转写/enrich 端点保持一致）
 *
 * ## 为什么 `model` 可以不返回
 * 网关可能把模型名写死在服务端。此时用 [DEFAULT_MODEL] 兜底 —— 但**必须所有端点
 * 一致**：客户端拿这个字符串做"能不能互相比"的判据，若网关有时返回有时不返回，
 * 就会出现同一批向量被分成两个 model 分组、语义检索莫名少一半数据。
 * 因此这里是"要么全部由网关回显，要么全部走默认值"，混用请改网关。
 *
 * ## 为什么空向量不抛异常
 * "这条记录没算出向量"（太短、纯符号）是正常业务结果，不是协议错误。
 * 抛异常会被 Executor 判成终态失败，让记录挂上"处理失败"——而用户什么也没做错。
 */
@Singleton
class RemoteEmbedder @Inject constructor(
    private val gateway: AiGatewayHttp,
) : Embedder {

    private val queryCache = QueryEmbeddingCache()

    override val providerKey: String
        get() = gateway.officialBaseUrl.orEmpty()

    override suspend fun embed(text: String): Embedding =
        gateway.execute(buildRequest(text)) { body -> parse(body) }

    /**
     * 搜索路径（2026-10-24 搜索卡顿整改）：先查缓存（同词重复搜索零网络），
     * 未命中走**快超时** client（用户在等结果，弱网下几秒就该降级为关键词结果，
     * 而不是跟着 120 秒的长超时转圈）。空向量不缓存：那是"网关没给出"的正常业务
     * 结果，缓存它会让这个词永远失去重试机会。
     *
     * 模型签名随写入传给缓存（r4 审查 L-4）：网关只换 embedding 模型（地址令牌不动）
     * 时键不变，靠签名变化把旧模型的条目整体作废，避免旧向量对上新模型的库内向量。
     */
    override suspend fun embedQuery(text: String): Embedding {
        val identity = gateway.identityKey
        val key = QueryEmbeddingCache.keyOf(identity, text)
        queryCache.get(key)?.let { return it }

        val embedding = gateway.executeFast(buildRequest(text)) { body -> parse(body) }
        if (!embedding.isEmpty) {
            queryCache.put(key, embedding, modelSignature = "$identity#${embedding.model}")
        }
        return embedding
    }

    private fun buildRequest(text: String): Request {
        val payload = gateway.json.encodeToString(EmbedRequest(text = text))
        return gateway.post(
            path = EMBED_PATH,
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
            contentType = JSON_CONTENT_TYPE,
        )
    }

    private fun parse(body: String): Embedding {
        val response = gateway.json.decodeFromString(EmbedResponse.serializer(), body)
        val vector = (response.embedding ?: response.result?.embedding).orEmpty()
        val model = (response.model ?: response.result?.model)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MODEL

        return Embedding(
            model = model,
            vector = vector.toFloatArray(),
        )
    }

    @Serializable
    private data class EmbedRequest(val text: String)

    @Serializable
    private data class EmbedResponse(
        val embedding: List<Float>? = null,
        val model: String? = null,
        val result: EmbedPayload? = null,
    )

    @Serializable
    private data class EmbedPayload(
        val embedding: List<Float>? = null,
        val model: String? = null,
    )

    companion object {
        /** 网关未回显模型名时的兜底标识；改了它等于让库里已有向量全部失效。 */
        const val DEFAULT_MODEL: String = "gateway-embed/v1"

        private const val EMBED_PATH = "/api/v1/embed"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()
    }
}
