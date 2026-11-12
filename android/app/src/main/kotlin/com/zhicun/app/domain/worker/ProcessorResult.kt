package com.zhicun.app.domain.worker

/**
 * 一次处理任务的执行结果（由具体 Worker 的处理器返回）。
 *
 * 分成五态而不是布尔值：**"能不能重试"、"成功没成功"和"该不该跑"是三件事**。
 * 网络抖动该重试；文件不存在、权限被拒、内容格式不支持则重试多少次都一样，
 * 把它们混成 `false` 会让重试策略变成空转 3 次然后失败，浪费用户电量与时间；
 * 而上游任务（OCR / 转写 / 网页提取）还没产出正文时，等待不该被计成一次失败。
 */
sealed interface ProcessorResult {

    /** 处理完成，结果已写入派生内容。 */
    data object Success : ProcessorResult

    /**
     * 失败但值得重试（网络超时、服务不可用、临时读写失败）。
     *
     * @param reason 只用于日志与 `ProcessingJob.errorCategory`，不直接展示给用户
     */
    data class Retryable(val reason: String) : ProcessorResult

    /**
     * 上游任务还没产出正文，等它跑完再试。
     *
     * 与 [Retryable] 的本质区别：**等待不消耗重试次数**（2026-10-23 QODER P2-2）。
     * 语音转写单次可达 120 秒，而重试预算只有 3 次、退避约 90 秒 —— 若把"上游还在跑"
     * 当普通失败计数，摘要/向量几乎必然在上游完成前耗尽重试，永久卡死在 FAILED，
     * 且没有任何机制重新触发。等待循环的终止条件是上游任务到达终态
     * （DONE → 有正文；FAILED/CANCELLED → 不再等待，按无正文处理）。
     *
     * @param reason 同上，供日志与 `errorCategory` 使用
     */
    data class WaitUpstream(val reason: String) : ProcessorResult

    /**
     * 被网关卡了限流（429）：**等待，且不消耗重试次数**。
     *
     * 与 [Retryable] 的本质区别：限流不是故障，是"这一小段时间别发了"。
     * 若按普通失败计数，用户连点几下就把一条记录的 3 次机会用光，
     * 之后真正的网络抖动再没有重试机会 —— 表现就是"偶尔一次限流，
     * 这条记录从此永远失败"。
     *
     * 与 [WaitUpstream] 的区别在**退避依据**：这里优先用服务端的 `Retry-After`，
     * 而等待上游用的是固定的指数曲线。
     *
     * @param retryAfterSeconds 服务端建议的等待秒数；缺失时由 Executor 走默认退避
     */
    data class RateLimited(
        val reason: String,
        val retryAfterSeconds: Long? = null,
    ) : ProcessorResult

    /**
     * 确定性失败（文件不存在、格式不支持、URL 被拒绝），重试也不会变好。
     *
     * @param reason 同上，供日志与错误分类使用
     */
    data class Permanent(val reason: String) : ProcessorResult

    /**
     * 策略性不上传（云端开关关闭 / 网关未配置 / 疑似敏感未确认）。
     *
     * 这不是失败：内容好好地在本地，只是按用户的隐私选择不出设备。
     * 任务落为 `CANCELLED`（终态、不重试、不计入失败聚合），记录侧由
     * `ProcessingJobExecutor` 聚合为"仅本地"。此前该情形返回 [Permanent]，
     * 会把"用户自己关掉的开关"渲染成一排红色"处理失败"，是误导（QODER P2-1）。
     *
     * @param reason 同上，供日志与 `errorCategory` 使用
     */
    data class Blocked(val reason: String) : ProcessorResult
}
