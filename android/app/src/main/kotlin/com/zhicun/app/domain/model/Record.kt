package com.zhicun.app.domain.model

/**
 * 一条记录（Domain 模型，对应 ARCHITECTURE.md §4.1 / PRD §9.1）。
 *
 * `rawText` 是用户原始内容，**任何 AI 派生产物都不得写回此字段**（PRD §4.3 原始/派生分离）。
 * `recordRevision` 在用户修改原始内容时递增，AI 派生结果绑定到具体的 revision，
 * 用于判断派生内容是否已经过期。
 */
data class Record(
    val id: String,
    val type: RecordType,
    val captureSource: CaptureSource,
    val rawText: String?,
    val sourceUrl: String?,
    val sourceTitle: String?,
    val sourceApp: String?,
    /**
     * 采集时间窗（2026-10-27，PRD v1.2 §4.2）。
     *
     * 目前只有录音写入：开始/结束是墙钟时间（epoch millis），[captureDurationMs]
     * 是实际录音时长（不含暂停），与 `createdAt`（落库时间）语义不同。
     * 其他入口保持 null —— 不拿 `createdAt` 冒充采集时间。
     */
    val captureStartedAt: Long? = null,
    val captureEndedAt: Long? = null,
    val captureDurationMs: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val localStatus: LocalStatus,
    val processingStatus: ProcessingStatus,
    val sensitivityStatus: SensitivityStatus,
    val recordRevision: Int = 1,
    /**
     * 用户明确同意"这条内容可以上传云端"时的内容版本（2026-10-23 QODER P2-6）；null = 未确认过。
     *
     * PRD §6.4："不因一次确认就把整条记录永久标记为安全"。因此确认**不写进**
     * [sensitivityStatus]（疑似标记原样保留，风险徽标一直在），而是独立记录
     * "用户在第几版内容上点过确认"；内容一旦被编辑（`recordRevision` 递增），
     * 旧确认自动失效，上传闸门重新关上。
     */
    val cloudConfirmedRevision: Int? = null,
    /**
     * 用户确认时，这条记录里**已经存在**的内容块（2026-10-23 审查 R-2）。
     *
     * 只比版本号不够：派生内容（OCR / 转写）落地时不递增 revision，
     * 于是"确认过 rawText"会把之后才出现的 OCR 文本也一起放行。
     * 外发前用 [cloudConfirmedFor] 校验"这次要发的东西是不是都在用户看过的那批里"。
     *
     * v3 之前确认的老记录在迁移时被补成 `RAW_TEXT` —— 当时的确认语义就是"原文已看过"。
     */
    val cloudConfirmedScope: Set<ContentPart> = emptySet(),
    val isDeleted: Boolean,
    /** 收藏（M16）：用户标星，用于筛出重要记录。 */
    val isFavorite: Boolean = false,
    /** 置顶（M16）：在收件箱顶部固定。 */
    val isPinned: Boolean = false,
    /**
     * 归档时间（M16）；null = 未归档。
     *
     * 归档**不是**删除，但它也不只是"从收件箱移出"（2026-10-24 裁决）：
     * 归档后**不再出现在全文搜索与相似推荐里**，只在归档箱中可见；数据本身不删，
     * 导出备份仍包含它（换手机不丢），取消归档即恢复可检索。
     *
     * 判据的落地位置：FTS 触发器（`archived_at IS NULL` 才建索引）+ 向量召回的 JOIN 条件。
     * 别在这里之外再写第三种过滤方式。
     */
    val archivedAt: Long? = null,
    /**
     * 用户单独允许"抓取这个网页"时的内容版本（2026-10-23 复核 F-5）；null = 从未允许过。
     *
     * 与 [cloudConfirmedRevision] 分成两列，是因为两者的**放行条件**不同：
     * 抓取只把 **URL** 发到那个网站（正文回到本机）；上传把**正文**发到 AI 网关，
     * 且要求云端通路可用。
     *
     * 2026-10-24 用户反馈后，详情页的敏感确认**一次点击同时写两列**（云端可用时）：
     * 用户的心智是"我知悉敏感，允许出网"，不该为同一个页面点两次。列仍分开保存，
     * 是为了保留单独授权抓取的能力 —— 云端开关关闭 / 网关未配置时，抓取不该被卡死，
     * 此时网页分区会单独问一次"允许抓取"。
     *
     * 与 [cloudConfirmedRevision] 还有一处关键差异：**它不会被派生正文的敏感复查作废**。
     * 抓回来的是公开页面的正文，命中敏感只说明"这个页面有敏感内容"，
     * 推不翻"用户允许访问这个 URL"；若也作废，一次抓取成功就会立刻自我撤销（死循环）。
     */
    val webFetchConfirmedRevision: Int? = null,
) {
    /** 是否已归档。界面上"归档 / 取消归档"共用一处判断，避免各处写 `!= null`。 */
    val isArchived: Boolean get() = archivedAt != null

    /**
     * 敏感记录的云端上传是否已被用户在**当前内容版本**上确认过。
     *
     * 判据放在 Record 上而不是各用例里：调度侧、重试侧、执行侧的闸门都要回答
     * 同一个问题，三处各写一遍 `cloudConfirmedRevision == recordRevision` 迟早走样。
     *
     * 注意这只是**版本级**判据（"第几版"，不含范围）。真正外发前应当用
     * [cloudConfirmedFor]，否则 R-2 的漏洞仍在（详见 [cloudConfirmedScope]）。
     */
    val cloudConfirmed: Boolean
        get() = cloudConfirmedRevision != null && cloudConfirmedRevision == recordRevision

    /**
     * 这一次要外发的内容块，是否全在用户确认过的范围内（2026-10-23 审查 R-2）。
     *
     * 只比对 [ContentPart.requiresConfirmation] 为真的块：摘要是 AI 自己浓缩的产物，
     * 不含新信息，却会被下一步（向量化）反过来外发 —— 若也要求确认就会形成死循环，
     * 语义检索永久不可用（详见该属性的注释）。
     *
     * @param required 本次外发实际用到的内容块（由
     *   [com.zhicun.app.domain.ai.ProcessingInputText] 与正文一起算出，
     *   保证"用了什么"与"报了什么"是同一份判断）
     */
    fun cloudConfirmedFor(required: Set<ContentPart>): Boolean =
        cloudConfirmed && cloudConfirmedScope.containsAll(required.filter { it.requiresConfirmation })

    /**
     * 用户是否允许**在内容当前这一版上**向源站请求这个网页（2026-10-23 复核 F-5）。
     *
     * 判据与 [cloudConfirmed] 同构：绑定版本号，内容被编辑后自动失效 ——
     * 用户改过 URL 或正文，就该重新问一次"允许访问这个地址吗"。
     */
    val webFetchConfirmed: Boolean
        get() = webFetchConfirmedRevision != null && webFetchConfirmedRevision == recordRevision
}

/** 记录类型（PRD §4.1）。 */
enum class RecordType {
    TEXT_NOTE,
    SHARED_TEXT,
    WEB_LINK,
    IMAGE,
    VOICE_NOTE,
    FILE,

    /**
     * 合集（M20）：把多条已有记录组织成一个阅读单元。
     *
     * 它仍是一条普通记录（有自己的摘要/标签/向量/整理状态），成员关系存在
     * `collection_members`；成员内容不复制，详情页实时读取。
     */
    COLLECTION,
}

/** 记录来源入口（PRD §5）。 */
enum class CaptureSource {
    MANUAL,
    SYSTEM_SHARE,
    CLIPBOARD,
    FLOATING,
    GALLERY,

    /** 悬浮面板截图（2026-10-27，PRD v1.2 §3）。 */
    SCREENSHOT,
}

/** 本地落盘状态。只有 SAVED 才能向用户展示"已保存"（ARCHITECTURE §6.2）。 */
enum class LocalStatus {
    SAVED,
    FAILED,
}

/** AI 派生处理状态（PRD §6.2）。 */
enum class ProcessingStatus {
    PENDING,
    PROCESSING,
    DONE,

    /** 部分任务成功、部分失败：有成果，但缺了一块。 */
    PARTIAL,

    /** 任务重试耗尽后仍失败。 */
    FAILED,

    /** 按策略没有上传云端（开关关闭 / 网关未配置 / 敏感未确认），本地结果不受影响。 */
    LOCAL_ONLY,
    ;

    /**
     * 是否有任务真的失败了。
     *
     * 与 [isIncomplete] 的区别：`LOCAL_ONLY` 不是失败，而是"按策略没做"，
     * 文案与配色都不同（见详情页 `DerivedBlock`），不能混为一谈。
     */
    val hasFailure: Boolean
        get() = this == FAILED || this == PARTIAL

    /**
     * 是否"预期有结果、但实际上没有"：界面上必须给出恢复入口。
     *
     * 定义放在枚举上，是因为详情页有四处派生分区各自判断（OCR / 网页 / 转写 / 摘要），
     * 此前四处都写 `processingStatus == FAILED`，聚合状态引入 `PARTIAL` 之后
     * 四处同时失效 —— 记录明明缺摘要、界面却一直显示"正在生成"，而且没有重试按钮
     * （2026-10-23 审查 R-1）。一个判据只有一份定义，新增状态时才不会集体漏改。
     */
    val isIncomplete: Boolean
        get() = hasFailure || this == LOCAL_ONLY
}

/** 敏感信息状态（PRD §6.4）。 */
enum class SensitivityStatus {
    UNKNOWN,
    SUSPECTED,
    CONFIRMED_SENSITIVE,
    CLEAR,
    ;

    /**
     * 是否按"敏感"处理：疑似与已确认都算。
     *
     * 定义放在枚举上而不是各处的私有扩展里 —— 调度侧（`ScheduleProcessingUseCase`）、
     * 重试侧（`RetryProcessingUseCase`）与各 Worker 都要用同一条判据，
     * 四份拷贝迟早走样，而走样的后果是"某一条路径把敏感内容传上去了"。
     */
    val isSensitive: Boolean
        get() = this == SUSPECTED || this == CONFIRMED_SENSITIVE
}
