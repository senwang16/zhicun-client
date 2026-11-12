package com.zhicun.app.data.remote

import com.zhicun.app.data.settings.ClientInstanceId
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.AiProviderConfig
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.ai.GatewayRateLimitedException
import com.zhicun.app.domain.ai.GatewayRejectedException
import com.zhicun.app.domain.ai.GatewayTransientException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * AI 网关的通用 HTTP 层（M9 建立，M11/M12 复用）。
 *
 * 三件事：拼请求（鉴权 + 基地址）、把 HTTP 状态翻译成 [com.zhicun.app.domain.ai] 的三类异常、
 * 解析 JSON。单独抽出来是因为转写/摘要/向量三个端点的鉴权与错误处理完全一致，
 * 复制三份必然在某个端点漏掉 429 或 401 的处理。
 *
 * ## 超时为什么不复用全局 OkHttpClient
 * 全局 client 的 `callTimeout` 是 10 秒（M8 网页抓取要求）。转写 10 分钟音频、
 * 生成摘要都可能超过 10 秒，用同一个 client 会导致"服务端还在算，客户端已经放弃"。
 * 这里用 `newBuilder()` 继承连接池，只把超时放宽到 120 秒。
 */
@Singleton
class AiGatewayHttp @Inject constructor(
    client: OkHttpClient,
    private val settings: AiGatewaySettings,
    private val clientInstanceId: ClientInstanceId,
) {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 网关重定向没有意义：地址写错就该立刻失败，而不是被转到别处发送音频
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 搜索等**用户正等着看结果**的请求专用快 client（2026-10-24 搜索卡顿整改）。
     *
     * 语义路的查询向量化实测要 2~3.5 秒；用 120 秒的长超时 client 时，弱网下
     * 搜索页会转圈近两分钟才降级。这里收紧到个位数秒：超时抛
     * [GatewayTransientException]，上层按既有的"仅关键词结果"降级处理。
     * 同样 `newBuilder()` 派生：连接池共享，长路的连接复用不受影响。
     */
    private val fastClient: OkHttpClient = this.client.newBuilder()
        .connectTimeout(FAST_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(FAST_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 网关身份键（查询向量缓存用）：换地址/换令牌后必须视为另一个网关 ——
     * 不同网关的 embedding 模型空间不同，旧向量混入只会得到噪声排序。
     * 用 token 的 hashCode 而不是原文：键会进内存缓存，没必要再多存一份明文令牌。
     */
    val identityKey: String
        get() = "${settings.baseUrl.orEmpty()}#${settings.token?.hashCode() ?: 0}"

    /**
     * 官方网关地址（向量空间身份用）。
     *
     * 只用地址、不含令牌：令牌轮换不会改变向量空间，用 `identityKey`（带 token 哈希）
     * 当向量身份会导致"换个令牌就要全部重新向量化"。
     */
    val officialBaseUrl: String?
        get() = settings.baseUrl

    val json: Json = Json {
        // 服务端字段会增删，客户端不能因为多一个字段就整条失败（版本错配时尤其明显）
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 单并发闸（防薅羊毛，见 [executeWith] 的说明）。
     *
     * 用 [Mutex] 而不是信号量：国内网关按共享令牌计数，本机多发一个请求
     * 就等于多占其他所有用户共享的一个名额，队列化是唯一安全的形态。
     */
    private val gatewayMutex = Mutex()

    /**
     * 搜索等"用户正等着"的短请求专用闸（2026-11-05 复核）。
     *
     * 不能与 [gatewayMutex] 共用：摘要/转写最长可占 120 秒，搜索请求排在它后面
     * 会让"快路 6 秒超时"形同虚设（等锁不设超时）。这里给快路单独一个座位，
     * 全局最多同时两个请求（一个长任务 + 一个搜索），仍远低于改造前的无上限并发。
     */
    private val fastMutex = Mutex()

    /** 拼一个带鉴权的 POST 请求；未配置网关时立刻抛 [GatewayNotConfiguredException]。 */
    fun post(path: String, body: RequestBody, contentType: String? = null): Request {
        val baseUrl = settings.baseUrl ?: throw GatewayNotConfiguredException()
        val token = settings.token ?: throw GatewayNotConfiguredException()

        // 显式拒绝非 HTTPS：请求会带上 Bearer token，安全边界不能只依赖
        // Manifest 的 cleartext 默认策略（2026-10-22 审核建议）。
        val httpUrl = "$baseUrl$path".toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?: throw GatewayRejectedException(
                code = "INSECURE_URL",
                message = "网关地址必须是 HTTPS，已拒绝携带令牌发送（请检查设置页的地址）",
            )

        return Request.Builder()
            .url(httpUrl)
            .header("Authorization", "Bearer $token")
            // 实例标识：让网关按「令牌 + 实例」分桶限流，而不是所有用户共用一个桶
            // （国内包共享同一个 Bearer token，见 ClientInstanceId 的说明）
            .header(HEADER_CLIENT_ID, clientInstanceId.value)
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .post(body)
            .build()
    }

    /**
     * 拼一个直连 OpenAI 兼容接口的 POST 请求（开源改造 P0·模式 B）。
     *
     * 与官方网关请求的三处差异：
     * 1. 鉴权用用户自己的 API Key（允许为空，自托管服务常不校验）；
     * 2. **不发 `X-Client-Id`**：那是官方网关按实例分桶限流的约定，第三方供应商不认识它，
     *    多送一个来路不明的头只会增加隐私暴露面；
     * 3. 地址由用户填写，允许 `http://`（局域网 Ollama 是支持范围内的用法）。
     *
     * 与官方请求共享同一套超时/并发闸/错误翻译（[execute] / [executeFast]），
     * 不允许出现"直连模式的 429 被当成普通失败"这类分叉。
     *
     * @throws GatewayRejectedException 地址无法解析（用户可修复，终态）
     */
    fun postProvider(
        config: AiProviderConfig,
        path: String,
        body: RequestBody,
        contentType: String? = null,
    ): Request {
        val httpUrl = providerUrl(config.baseUrl, path)
            ?: throw invalidProviderUrl(config.baseUrl)
        return Request.Builder()
            .url(httpUrl)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                header("Accept", "application/json")
                if (contentType != null) header("Content-Type", contentType)
            }
            .post(body)
            .build()
    }

    /**
     * 拼一个 Anthropic Messages 协议的 POST 请求（开源改造 P2）。
     *
     * 与 OpenAI 方言的差异（不能只改 URL 混过去）：
     * - 鉴权头是 `x-api-key`，不是 `Authorization: Bearer`；
     * - 必须带 `anthropic-version: 2023-06-01`（缺了会被 400）；
     * - 端点路径是 `{base}/messages`，请求体里 system 是**顶层字段**而不是一条 message。
     *
     * 允许空 Key：本地代理（如某些 OpenAI/Anthropic 双向网关）可能不校验；
     * 但真 Anthropic API 空 Key 必然 401，错误文案会指向设置页。
     */
    fun postAnthropic(
        config: AiProviderConfig,
        path: String,
        body: RequestBody,
        contentType: String? = null,
    ): Request {
        val httpUrl = providerUrl(config.baseUrl, path)
            ?: throw invalidProviderUrl(config.baseUrl)
        return Request.Builder()
            .url(httpUrl)
            .apply {
                if (config.apiKey.isNotBlank()) header("x-api-key", config.apiKey.trim())
                header("anthropic-version", ANTHROPIC_VERSION)
                header("Accept", "application/json")
                if (contentType != null) header("Content-Type", contentType)
            }
            .post(body)
            .build()
    }

    private fun invalidProviderUrl(baseUrl: String) = GatewayRejectedException(
        code = "INVALID_URL",
        message = "API 地址无法解析，请检查设置页里的地址：$baseUrl",
    )

    /**
     * 执行请求并解析响应。
     *
     * @param parse 成功（2xx）时的解析逻辑
     * @throws GatewayRateLimitedException 429 限流（按 `Retry-After` 退避，不消耗重试预算）
     * @throws GatewayTransientException 5xx / 网络故障（可重试）
     * @throws GatewayRejectedException 4xx / 内容被拒（终态）
     */
    suspend fun <T> execute(request: Request, parse: (String) -> T): T =
        executeWith(client, gatewayMutex, request, parse)

    /** 与 [execute] 同一套错误翻译，只换用快超时 client（见 [fastClient] 的说明）。 */
    suspend fun <T> executeFast(request: Request, parse: (String) -> T): T =
        executeWith(fastClient, fastMutex, request, parse)

    private suspend fun <T> executeWith(
        target: OkHttpClient,
        gate: Mutex,
        request: Request,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        // 单并发闸：同一时刻只允许一个云端请求在飞。
        //
        // 国内包内置共享令牌，网关限流按令牌指纹计数 —— 所有用户共用一个桶。
        // 客户端并发发得越多，越容易被判限流，而限流又是全局的：一个人把桶打满，
        // 其他用户一起被 429。这里把并发压到 1，让"一台设备"最多占用一个名额，
        // 也让重试退避真正生效（并发时不排队，退避等于没退）。
        //
        // 长任务用 [gatewayMutex]、搜索用 [fastMutex]：快路不能排在 120 秒的转写后面。
        gate.withLock {
            val response = try {
                target.newCall(request).execute()
            } catch (e: IOException) {
                // 连接中断、DNS 失败、超时：都值得重试
                throw GatewayTransientException("访问 AI 网关失败", e)
            }

            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                // 错误码先解析一次：限流与配额耗尽都可能是 429，只靠状态码分不开
                val errorCode = if (resp.isSuccessful) null else errorCodeOf(text)
                when {
                    resp.isSuccessful -> parse(text)

                    resp.code == 401 || resp.code == 403 -> throw GatewayRejectedException(
                        code = errorCode ?: "AUTH_REQUIRED",
                        message = "AI 服务拒绝访问（${resp.code}），请检查 API Key 或访问令牌",
                    )

                    resp.code == 413 -> throw GatewayRejectedException(
                        code = "PAYLOAD_TOO_LARGE",
                        message = "内容超过服务允许的大小",
                    )

                    // 配额耗尽与普通限流都是 429，但处理方式相反：
                    // 配额耗尽重试只会白烧电（终态，提示用户充值/换 Key），
                    // 普通限流才该退避（不消耗任务重试预算）。OpenAI 系用 insufficient_quota。
                    resp.code == 429 && isQuotaExhausted(errorCode) -> throw GatewayRejectedException(
                        code = errorCode ?: "QUOTA_EXHAUSTED",
                        message = "AI 服务配额已用尽（429），请检查账户余额或更换 API Key",
                    )

                    // 429 单独一类：按 Retry-After 退避，且不消耗任务重试预算。
                    // 与 5xx 合并成同一条会让"限流"表现成"服务故障"，重试得更凶。
                    resp.code == 429 -> throw GatewayRateLimitedException(
                        message = "AI 服务限流（429），请稍后重试",
                        retryAfterSeconds = resp.header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it >= 0 },
                    )

                    resp.code == 402 -> throw GatewayRejectedException(
                        code = errorCode ?: "QUOTA_EXHAUSTED",
                        message = "AI 服务配额已用尽（402），请检查账户余额",
                    )

                    // 404 在直连模式下几乎总是"地址或模型名填错"，说清楚比只报状态码有用
                    resp.code == 404 -> throw GatewayRejectedException(
                        code = errorCode ?: "NOT_FOUND",
                        message = "AI 服务找不到该接口或模型（404），请检查 API 地址与模型名称",
                    )

                    resp.code >= 500 -> throw GatewayTransientException(
                        "AI 服务暂时不可用（${resp.code}）",
                    )

                    else -> throw GatewayRejectedException(
                        code = errorCode ?: "HTTP_${resp.code}",
                        message = "AI 服务返回 ${resp.code}",
                    )
                }
            }
        }
    }

    /**
     * 从各家供应商的错误信封里尽力取出机器可读错误码。
     *
     * 已覆盖的四种形状（2026-11-12 开源改造，直连模式调研）：
     * - OpenAI 标准：`{"error":{"code":"…","type":"…"}}`
     * - OpenAI 只有 type：`{"error":{"type":"rate_limit_exceeded"}}`
     * - 部分国内供应商：`{"code":"…"}` / `{"error_code":"…"}`
     *
     * 取不到就返回 null，由调用方按状态码兜底 —— 解析失败本身不该升级成异常。
     */
    private fun errorCodeOf(body: String): String? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"] as? JsonObject
        sequenceOf(
            codeOf(error?.get("code")),
            codeOf(error?.get("type")),
            codeOf(root["code"]),
            codeOf(root["error_code"]),
        ).firstOrNull { it != null }
    } catch (e: Exception) {
        null
    }

    private fun codeOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** 配额耗尽类错误码：都意味着"再试也没用"，必须与普通限流分开。 */
    private fun isQuotaExhausted(code: String?): Boolean =
        code != null && code.lowercase() in QUOTA_EXHAUSTED_CODES

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 120L

        /** 客户端实例标识请求头名（与 `server/src/router.ts` 的 `clientIdOf` 约定一致）。 */
        const val HEADER_CLIENT_ID = "X-Client-Id"

        /** Anthropic Messages 的版本头；2023-06-01 仍是当前稳定的最小版本。 */
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** 快路（搜索）：连接 5 秒、读/写 4 秒、整次调用 6 秒。 */
        const val FAST_CONNECT_TIMEOUT_SECONDS = 5L
        const val FAST_TIMEOUT_SECONDS = 4L
        const val FAST_CALL_TIMEOUT_SECONDS = 6L

        /** 各家的"配额耗尽"错误码写法（大小写不一致，统一按小写比较）。 */
        val QUOTA_EXHAUSTED_CODES = setOf(
            "insufficient_quota",
            "quota_exceeded",
            "quota_exhausted",
            "billing_hard_limit_reached",
        )

        /**
         * 把"用户填的地址 + 接口路径"拼成完整 URL（两家方言共用同一套规范化）。
         *
         * 要处理的三种输入（设置页不强制格式，用户会怎么顺手怎么填）：
         * - `https://api.openai.com/v1` → 补成 `/v1/` 再拼，否则 OkHttp 会把 `v1` **替换**掉；
         * - `https://api.openai.com/v1/` → 直接用；
         * - `https://api.siliconflow.cn` → 补 `/v1/`（文档约定的"填到域名也能用"）。
         *
         * @return 解析失败（含非 http/https 协议）返回 null，由调用方转成用户可读的错误
         */
        internal fun providerUrl(baseUrl: String, path: String): HttpUrl? {
            val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
            if (base.scheme != "https" && base.scheme != "http") return null

            val builder = base.newBuilder()
            if (base.pathSegments.filter { it.isNotEmpty() }.isEmpty()) {
                builder.addPathSegment("v1")
            }
            // 无尾斜杠时 addPathSegments 会替换掉最后一段（把 v1 吃掉），必须补一个空段
            if (!builder.build().pathSegments.lastOrNull().isNullOrEmpty()) {
                builder.addPathSegment("")
            }
            return builder.addPathSegments(path.trimStart('/')).build()
        }
    }
}
