package com.zhicun.app.domain.ai

/**
 * AI 网关的连接配置（base URL + access token）。
 *
 * 抽成接口而不是直接读 SharedPreferences：设置页（M14）、转写 Worker（M9）、
 * 摘要 Worker（M11）、向量 Worker（M12）都要读它，而单测需要能秒填一个假配置。
 *
 * 安全约定（ARCHITECTURE §6.6）：**App 端只存网关地址与令牌，永不存第三方模型 API Key**；
 * 令牌只用于向自家 Worker 证明"这台设备被授权"，模型 Key 在 Cloudflare Secrets 里。
 */
interface AiGatewaySettings {

    /** 形如 `https://gw.example.com`（无尾部斜杠）；未配置为 null。 */
    val baseUrl: String?

    /** 网关访问令牌；未配置为 null。 */
    val token: String?

    /** 两项都齐才算可用 —— 只有地址没有令牌，请求必定 401。 */
    val isConfigured: Boolean

    /** 用户在设置页保存配置；传 null 或空串表示清除。 */
    fun save(baseUrl: String?, token: String?)
}
