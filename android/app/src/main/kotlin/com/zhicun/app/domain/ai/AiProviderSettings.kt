package com.zhicun.app.domain.ai

/**
 * AI 能力的配置总入口（开源改造 P0）。
 *
 * 取代"一个网关套三种能力"的 [AiGatewaySettings]，把它拆成：
 * - **官方服务**：地址 + 访问令牌（模式 A，与改造前语义一致）；
 * - **三组独立 Provider**：Chat / Embedding / ASR，各自有地址、Key、模型（模式 B）。
 *
 * ## 一个开关决定"走哪条路"
 * [useOfficialService] 是用户的意图，不是能力探测结果：用户勾了官方服务但没填令牌时，
 * 路由侧仍会走官方路径并抛出"未配置"（文案准确），**不会**在背后偷偷改用别的 Provider
 * —— 那等于用用户的另一个 Key 替他做了一次他没同意的外发。
 *
 * ## 可用性是"此刻能不能真的发出去"
 * [chatAvailable] / [embedAvailable] / [asrAvailable] 已经把上面的规则算进去了：
 * 勾官方且官方已配置 → 可用；否则看该能力自己的自定义配置在不在。
 * 界面据此显示"已配置/未配置"，执行侧据此决定要不要拦任务（见 `CloudUploadGate`）。
 *
 * ## 安全约定
 * API Key 与官方令牌一样**必须加密落盘**（`TokenCipher`）；本接口只暴露"是否已配置"，
 * 不提供"读回明文 Key"的方法 —— 设置页保存后不回填，用户要改就重新输入。
 */
interface AiProviderSettings {

    // ------------------------------------------------------------ 官方服务（模式 A）

    /** 官方网关地址；未配置为 null。 */
    val officialBaseUrl: String?

    /** 官方网关访问令牌；未配置为 null。 */
    val officialToken: String?

    /** 地址与令牌都齐才算可用（只有地址没有令牌，请求必定 401）。 */
    val officialConfigured: Boolean

    /** 用户是否选择"使用官方服务"；默认 true，与改造前的行为保持一致。 */
    val useOfficialService: Boolean

    fun setUseOfficialService(value: Boolean)

    /** 保存官方服务配置；传 null 或空串表示清除。 */
    fun saveOfficial(baseUrl: String?, token: String?)

    // ------------------------------------------------------------ 自定义 Provider（模式 B）

    /** 摘要与标签（OpenAI 兼容 / Anthropic Messages 两种协议之一）；未配置为 null。 */
    val chatConfig: AiProviderConfig?

    /** Chat 使用的协议方言；未配置过时为 [ProviderProtocol.OPENAI]（老用户零迁移）。 */
    val chatProtocol: ProviderProtocol

    /**
     * 单独保存 Chat 协议方言。
     *
     * 与 [saveChat] 分开的理由：协议是"先选后填"的开关，用户可能在填地址之前就切换；
     * 若走 saveChat，调用方必须把当前地址/模型一起传回来，等于让 UI 负责"回写没改的字段"。
     */
    fun saveChatProtocol(protocol: ProviderProtocol)

    /**
     * 保存 Chat 配置。
     *
     * @param apiKey `null` = **保留原有密钥不变**（用户只想改模型名时不必重新粘贴 Key）；
     *   空串 = 清除密钥（本地无鉴权服务）
     */
    fun saveChat(baseUrl: String?, apiKey: String?, model: String?)

    /** 摘要/标签此刻是否可用（官方已配置且勾选官方，或自定义配置齐备）。 */
    val chatAvailable: Boolean

    /** 向量化；未配置为 null。 */
    val embedConfig: AiProviderConfig?

    fun saveEmbed(baseUrl: String?, apiKey: String?, model: String?)

    /** 向量化此刻是否可用。 */
    val embedAvailable: Boolean

    /** 语音转写；未配置为 null。 */
    val asrConfig: AiProviderConfig?

    fun saveAsr(baseUrl: String?, apiKey: String?, model: String?)

    /** 转写此刻是否可用。 */
    val asrAvailable: Boolean

    // ------------------------------------------------------------ 汇总

    /**
     * 云端通路是否可用 = 三种能力里至少一种此刻能发出去。
     *
     * 现有的 `CloudUploadGate` 用的是粗粒度判据（一个布尔管住全部云端任务），
     * 因此这里必须把三组配置算进去，否则"自定义模式下网关未配置"会把用户
     * 明明配好的能力一并挡掉。按能力细分的闸门见 `docs/开源开发计划.md` §2-3。
     */
    val anyProviderAvailable: Boolean
}