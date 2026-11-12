package com.zhicun.app.domain.ai

import java.io.IOException

/**
 * AI 网关的失败分类（M9 起共用，M11/M12 复用）。
 *
 * 分成三类而不是一个 `Exception`：**能重试的**和**重试没用的**必须区分，
 * 否则要么白耗电（对 400 重试三次），要么把一次网络抖动判成终态失败。
 *
 * 与 M6 的 `ProcessingJobExecutor` 天然对齐：
 * - [GatewayTransientException] 继承 [IOException] → 自动被判为"可重试"；
 * - 另外两个不是 IO 异常 → 自动被判为"确定性失败"。
 * 这样任务重试策略不需要为"云端调用"再写一套规则。
 */

/** 未配置网关地址/令牌。用户侧可修复（M14 设置页），自动重试无意义。 */
class GatewayNotConfiguredException :
    Exception("尚未配置 AI 网关（地址或访问令牌为空）")

/**
 * 网关明确拒绝：4xx、鉴权失败、内容被供应商拒绝、请求体过大。
 *
 * @param code 网关返回的机器可读错误码（如 `PAYLOAD_TOO_LARGE`），没有则为 null
 */
class GatewayRejectedException(
    val code: String?,
    message: String,
) : Exception(message)

/** 可重试的网关故障：5xx、429、超时、连接中断。 */
class GatewayTransientException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 网关限流（429）。
 *
 * 单独一类而不是复用 [GatewayTransientException]，因为它的"重试"语义完全不同：
 * 限流不是故障，是"你发太快了"。立刻重试只会加剧拥堵，而且**不该消耗**
 * 任务的重试次数 —— 否则用户连点几次就把一条记录的 3 次机会用光，
 * 后续真正的网络抖动再也没机会重试。
 *
 * 仍然继承 [IOException]：在 Executor 眼里它依旧属于"可重试"，
 * 只是要按 [retryAfterSeconds] 推迟，而不是走默认退避。
 *
 * @param retryAfterSeconds 服务端 `Retry-After` 的秒数；缺失时为 null（按默认退避）
 */
class GatewayRateLimitedException(
    message: String,
    val retryAfterSeconds: Long? = null,
) : IOException(message)
