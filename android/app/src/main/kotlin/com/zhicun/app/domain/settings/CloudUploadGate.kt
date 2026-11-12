package com.zhicun.app.domain.settings

import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.Record

/**
 * "这条记录的内容能不能发出去"的唯一判据（M14 建立，2026-10-23 扩展）。
 *
 * 三个理由合在一处写，是因为它们回答的是同一个问题、后果也相同（内容出设备、不可撤销）：
 * 1. 用户关掉了云端 AI 处理 —— "我不希望我的东西被上传"；
 * 2. 网关没配置（地址/令牌缺失）—— 任务注定失败，与其让每条记录挂上
 *    "AI 处理失败"，不如在策略层面就不上传（QODER P2-3：默认安装包没有网关配置）；
 * 3. 记录疑似/已确认含敏感信息且用户未确认 —— PRD §6.4 要求暂停自动上传，
 *    等用户逐条确认；确认只对"确认时的内容版本"有效（[Record.cloudConfirmed]）。
 *
 * ## 两个层级：通路可用性 与 单条记录可发
 * [cloudPathBlockedReason] 只回答"云端这条路现在能不能走"（开关 + 网关），
 * 与具体记录无关 —— 检索侧（搜索词本身也会发到云端）需要的就是这一层。
 * [reasonOf] 在它之上叠加记录的敏感状态，供上传侧使用。
 *
 * 拆成两层而不是让检索侧自己写一个 `if (!cloudAiEnabled)`：判据一旦有两份实现，
 * 迟早有一处漏掉网关条件，而漏掉的后果是"隐私承诺被静默违背"，没有测试会红
 * （2026-10-23 审查 R-8 指出的正是这种分叉）。
 *
 * ## 调用点
 * - 调度侧（`ScheduleProcessingUseCase`）：不排任务，从源头上没有上传路径；
 * - 重试侧（`RetryProcessingUseCase`）：用户点重试 ≠ 同意上传，同样过滤；
 * - 确认侧（`ConfirmCloudProcessingUseCase`）：云端根本不可用时不要"空耗"一次确认；
 * - 补投侧（`ResumeCloudJobsUseCase`）：开关重开后只复活真正能跑的任务；
 * - 检索侧（`SearchRecordsUseCase`）：只判通路（[cloudPathBlockedReason]）；
 * - 各云端 Worker（摘要 / 向量 / 转写）：**纵深防御**，因为任务可能来自启动补投
 *   （`RecoverPendingJobsUseCase`）或开关关闭前已经排进队列的那一批。
 */
object CloudUploadGate {

    /** "为什么没上传"的结构化原因：同一份文案既写进任务表，也用于界面提示。 */
    enum class Reason(val message: String) {
        /** 用户关掉了云端 AI 处理。 */
        CLOUD_DISABLED("云端 AI 处理已关闭，内容未上传"),

        /** 网关地址/令牌缺失。 */
        NOT_CONFIGURED("AI 网关未配置，内容未上传"),

        /** 记录疑似/已确认敏感且用户未确认当前内容版本。 */
        SENSITIVE("疑似敏感内容，待用户确认后再上传"),
    }

    /** 关掉云端 AI 时给用户的解释（也是任务表的终态原因）。 */
    val REASON_CLOUD_DISABLED: String get() = Reason.CLOUD_DISABLED.message

    /** 网关未配置时给用户的解释。 */
    val REASON_NOT_CONFIGURED: String get() = Reason.NOT_CONFIGURED.message

    /** 疑似敏感且未确认时给用户的解释。 */
    val REASON_SENSITIVE: String get() = Reason.SENSITIVE.message

    /**
     * 云端通路是否可用（与具体记录无关）。
     *
     * @return 允许发往云端返回 `null`；否则返回被挡的原因
     */
    fun cloudPathBlockedReason(
        cloudAiEnabled: Boolean,
        gatewayConfigured: Boolean,
    ): Reason? = when {
        !cloudAiEnabled -> Reason.CLOUD_DISABLED
        !gatewayConfigured -> Reason.NOT_CONFIGURED
        else -> null
    }

    /**
     * 这条记录能不能发往云端。
     *
     * @param requiredParts 本次外发**实际用到**的内容块（由
     *   [com.zhicun.app.domain.ai.ProcessingInputText] 与正文一起产出）。
     *   留空表示"还没算出来"，此时按版本级判据处理 —— 只能用于入口预检，
     *   真正发请求前必须带上它，否则 R-2 的漏洞仍在（确认过一次就永久放行新增内容）。
     * @return 允许上传返回 `null`；否则返回"为什么没上传"
     */
    fun reasonOf(
        cloudAiEnabled: Boolean,
        gatewayConfigured: Boolean,
        record: Record,
        requiredParts: Set<ContentPart> = emptySet(),
    ): Reason? = cloudPathBlockedReason(cloudAiEnabled, gatewayConfigured)
        ?: sensitiveEgressReason(record, requiredParts)

    /**
     * 只看"记录本身是否敏感"这一条（2026-10-23 R-7）。
     *
     * 网页正文提取不属于云端 AI：它向**源站**取页面，与云端开关无关（关掉云端 AI 也照常可用）。
     * 但它确实是一次出网请求 —— URL（可能带预签名令牌）会到第三方站点。
     * 因此它只受这一个条件约束：**疑似敏感且未确认的记录不发请求**，
     * 内容（连 URL）留在手机里。
     *
     * 单独一个函数而不是让 Worker 自己写 `sensitivityStatus.isSensitive && !cloudConfirmed`：
     * 敏感判据目前出现在三处（上传闸门、抓取闸门、聚合），只能有一份定义。
     */
    fun sensitiveEgressReason(
        record: Record,
        requiredParts: Set<ContentPart> = emptySet(),
    ): Reason? {
        if (!record.sensitivityStatus.isSensitive) return null
        // 没给出具体内容块 = 还没算出来（入口预检、网页抓取都属这一档）：退回版本级判据。
        // 一旦有清单，就必须逐块比对 —— 用户没看过的内容不能被一次旧确认放行。
        val confirmed = if (requiredParts.isEmpty()) {
            record.cloudConfirmed
        } else {
            record.cloudConfirmedFor(requiredParts)
        }
        return Reason.SENSITIVE.takeIf { !confirmed }
    }

    /**
     * 网页抓取能不能发出请求（2026-10-23 复核 F-5）。
     *
     * 只受一个条件约束：**疑似敏感且用户没有单独允许抓取这个网页**。
     * 不看云端开关与网关配置 —— 抓取不经 AI 网关，关掉云端 AI 时它本来就该继续可用。
     *
     * 与 [sensitiveEgressReason] 的区别在这里：后者拿"云端上传的确认"当放行条件，
     * 而那个确认要求云端通路可用。默认无网关的安装包里，两者叠加的后果是
     * "敏感网页永远抓不下来，界面还给不出一句真话"（F-5 记录的死路）。
     *
     * 判据只能有一份：Worker 与详情页都用这个函数，界面才可能如实说出"为什么没抓"。
     */
    fun webFetchBlockedReason(record: Record): Reason? =
        Reason.SENSITIVE.takeIf {
            record.sensitivityStatus.isSensitive && !record.webFetchConfirmed
        }

    /**
     * [reasonOf] 的字符串形态：写进任务表的 `errorCategory`。
     *
     * 文案从 [Reason] 派生，不另写一份 —— 界面与任务表说的是同一句话。
     */
    fun blockReason(
        cloudAiEnabled: Boolean,
        gatewayConfigured: Boolean,
        record: Record,
        requiredParts: Set<ContentPart> = emptySet(),
    ): String? = reasonOf(cloudAiEnabled, gatewayConfigured, record, requiredParts)?.message
}
