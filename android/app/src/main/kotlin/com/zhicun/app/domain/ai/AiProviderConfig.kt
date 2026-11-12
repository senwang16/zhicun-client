package com.zhicun.app.domain.ai

/**
 * 直连模式下使用的协议方言（开源改造 P2）。
 *
 * 目前"通用"的对话协议事实上有两套：OpenAI 的 Chat Completions 与 Anthropic 的 Messages。
 * 两套在端点、鉴权头、请求体与响应结构上都不同，不能靠改一个 URL 蒙混过去。
 *
 * Embeddings 与语音转写没有并入这一层：Anthropic 不提供这两类接口，
 * 现有供应商（OpenAI / 硅基流动 / Ollama）在它们上面也都兼容 OpenAI 格式。
 */
enum class ProviderProtocol {
    /** `POST {base}/chat/completions`，`Authorization: Bearer`，`choices[0].message.content`。 */
    OPENAI,

    /** `POST {base}/messages`，`x-api-key` + `anthropic-version`，`content[].text`。 */
    ANTHROPIC,
}

/**
 * 单个 AI Provider 的接入模式。
 *
 * 两种模式对应《开源技术落地方案》§2.4 的模式 A / 模式 B：
 * - [OFFICIAL]：走智存官方网关协议（`/api/v1/enrich|embed|transcribe`），Bearer 官方令牌；
 * - [CUSTOM]：直连用户选择的通用协议（OpenAI 兼容 / Anthropic Messages），用用户自己的 API Key。
 */
enum class ProviderMode {
    OFFICIAL,
    CUSTOM,
}

/**
 * 一组 AI 能力的连接配置（Chat / Embedding / ASR 各持一份）。
 *
 * ## 为什么是"一份配置一个能力"而不是一个网关套三种能力
 * 《开源规划》§2.3 的核心要求：**不能假设一个模型服务商同时提供全部三种能力**。
 * 现实中商汤只有 chat、硅基流动有 embed + ASR、Ollama 没有 ASR ——
 * 共用一组配置等于强迫用户挑一家"三样都有"的供应商（往往并不存在）。
 *
 * ## apiKey 允许为空
 * 自托管的 Ollama / vLLM / LM Studio 通常不校验密钥，此时不发 `Authorization` 头即可。
 * 空 Key 只对 [ProviderMode.CUSTOM] 有意义：官方模式没有令牌必然 401。
 *
 * @param baseUrl 接口地址，填到版本号为止（`https://api.openai.com/v1`）；
 *   省略 `/v1` 时实现会补上，避免用户被"填了域名却 404"卡住
 * @param apiKey 用户自己的第三方密钥（可空，见上）
 * @param model 模型名，原样发给供应商（`gpt-4o-mini`、`BAAI/bge-m3`）
 * @param mode 接入模式；三组配置各自独立，同一时刻可以一组官方、两组自定义（P2 能力，见设置页）
 * @param protocol 直连对话使用的协议方言（仅 Chat 有值）；默认 OpenAI 兼容，老配置零迁移
 */
data class AiProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val mode: ProviderMode = ProviderMode.CUSTOM,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI,
) {

    /** 地址与模型齐备才算可发起请求；Key 可以缺（本地部署场景）。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * 脱敏后的展示用标识：`api.openai.com/v1 · gpt-4o-mini`。
     *
     * 只用于界面与日志：**任何地方都不该把 apiKey 拼进来**，
     * 它会进 Logcat、进崩溃报告、进用户截图。
     */
    val displayName: String
        get() = "${baseUrl.trim().removePrefix("https://").removePrefix("http://")} · $model"
}