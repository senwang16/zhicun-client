package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Enrichment
import com.zhicun.app.domain.settings.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 云端内容增强（M11）：`POST {base}/api/v1/enrich`。
 *
 * 请求：`{"text":"...","language":"zh"}`（JSON）。
 * - `language` 是**显式输出语言**（R-I18N-04/11）：服务端据此在提示词里追加固定指令，
 *   不依赖模型自行判断，也不按原文语言推断；
 * - 缺省（旧客户端）时服务端按 `zh` 处理，行为与升级前一致；
 * - 原文、专有名词与引用不翻译，只有标题/摘要/标签跟随该语言。
 *
 * 响应兼容两种形状：
 * - `{"title":"…","summary":"…","tags":["…"]}`
 * - `{"result":{"title":"…","summary":"…","tags":["…"]}}`（与转写端点的 `result` 包装保持一致）
 *
 * ## 为什么不用流式
 * 摘要输入是几百到几千字，一次请求体最多几十 KB，远小于任何网关限制；
 * 流式只会让"部分 JSON"的容错变复杂，收益为零。
 *
 * ## 空结果不是错误
 * 服务端对"太短/无信息量"的文本可能返回全空字段，这里原样返回 [Enrichment]，
 * 由 `SummarizeWorker` 决定怎么落库（空摘要也写一行，避免界面永远显示"处理中"）。
 */
@Singleton
class RemoteContentEnricher @Inject constructor(
    private val gateway: AiGatewayHttp,
) : ContentEnricher {

    override suspend fun enrich(text: String, language: AppLanguage): Enrichment {
        val payload = gateway.json.encodeToString(
            EnrichRequest(text = text, language = language.code),
        )
        val request = gateway.post(
            path = ENRICH_PATH,
            body = payload.toRequestBody(JSON_MEDIA_TYPE),
            contentType = JSON_CONTENT_TYPE,
        )

        return gateway.execute(request) { body ->
            val response = gateway.json.decodeFromString(EnrichResponse.serializer(), body)
            // 顶层与 result 包装同时支持：网关换代时客户端不用跟着改
            val title = response.title ?: response.result?.title
            val summary = response.summary ?: response.result?.summary
            val tags = response.tags ?: response.result?.tags

            Enrichment(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                summary = summary?.trim()?.takeIf { it.isNotEmpty() },
                tags = tags.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct(),
            )
        }
    }

    @Serializable
    private data class EnrichRequest(
        val text: String,
        /** `zh` / `en`；旧服务端会忽略该字段并按中文输出。 */
        val language: String,
    )

    @Serializable
    private data class EnrichResponse(
        val title: String? = null,
        val summary: String? = null,
        val tags: List<String>? = null,
        val result: EnrichPayload? = null,
    )

    @Serializable
    private data class EnrichPayload(
        val title: String? = null,
        val summary: String? = null,
        val tags: List<String>? = null,
    )

    private companion object {
        const val ENRICH_PATH = "/api/v1/enrich"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()
    }
}