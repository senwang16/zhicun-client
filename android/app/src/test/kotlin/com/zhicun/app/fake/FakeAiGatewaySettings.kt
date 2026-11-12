package com.zhicun.app.fake

import com.zhicun.app.domain.ai.AiGatewaySettings

/**
 * 内存版网关配置。
 *
 * 默认视为"已配置"：绝大多数用例只关心业务行为，不想为每一条都填地址和令牌；
 * 需要验证"网关未配置"闸门的用例显式把 [baseUrl] 或 [token] 置空。
 */
class FakeAiGatewaySettings(
    override var baseUrl: String? = "https://gateway.test",
    override var token: String? = "test-token",
) : AiGatewaySettings {

    override val isConfigured: Boolean
        get() = baseUrl != null && token != null

    override fun save(baseUrl: String?, token: String?) {
        this.baseUrl = baseUrl
        this.token = token
    }
}
