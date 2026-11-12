package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus

/**
 * 记录表（对应 ARCHITECTURE.md §4.1）。
 *
 * 约束：
 * - 本 Entity **禁止**出现在 domain/ 或 presentation/ 包中，必须经 mapper 转换（AGENTS.md §2.3）。
 * - `raw_text` 是原始内容的唯一真源，AI 派生结果只能写入 derived_contents。
 * - 软删除用 `is_deleted`，行不物理删除，便于 M15 清理附件文件后统一回收。
 * - 整理相关的三个标记（收藏 / 置顶 / 归档，M16）都只是"用户怎么看这批记录"，
 *   **不参与** AI 处理与导出范围判定：归档的记录仍然会被导出（它不是删除）。
 */
@Entity(
    tableName = "records",
    indices = [
        Index(value = ["is_deleted", "created_at"]),
        Index(value = ["processing_status"]),
        // 收件箱查询是"未删除 + 未归档，置顶优先"，把这三列放进一条索引
        Index(value = ["is_deleted", "archived_at"]),
    ],
)
data class RecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "type")
    val type: RecordType,

    @ColumnInfo(name = "capture_source")
    val captureSource: CaptureSource,

    @ColumnInfo(name = "raw_text")
    val rawText: String?,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String?,

    @ColumnInfo(name = "source_title")
    val sourceTitle: String?,

    @ColumnInfo(name = "source_app")
    val sourceApp: String?,

    /** 录音采集开始时间（epoch millis）；非录音入口为 null（2026-10-27，PRD v1.2 §4.2）。 */
    @ColumnInfo(name = "capture_started_at")
    val captureStartedAt: Long? = null,

    /** 录音采集结束时间（epoch millis）；非录音入口为 null。 */
    @ColumnInfo(name = "capture_ended_at")
    val captureEndedAt: Long? = null,

    /** 实际录音时长（不含暂停）；非录音入口为 null。 */
    @ColumnInfo(name = "capture_duration_ms")
    val captureDurationMs: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "local_status")
    val localStatus: LocalStatus,

    @ColumnInfo(name = "processing_status")
    val processingStatus: ProcessingStatus,

    @ColumnInfo(name = "sensitivity_status")
    val sensitivityStatus: SensitivityStatus,

    @ColumnInfo(name = "record_revision", defaultValue = "1")
    val recordRevision: Int,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean,

    /** 收藏（M16）：用户主动标星，用于在收件箱里筛出"重要的那几条"。 */
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,

    /** 置顶（M16）：在收件箱顶部固定，**不影响**任何处理流程。 */
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,

    /**
     * 归档时间（M16）；null = 未归档。
     *
     * 存时间戳而不是布尔：归档列表要按"何时归档"排序，界面上也要能说清归档于何时。
     * 归档不是删除 —— 记录仍可搜索、仍会被导出（M15），只是不再出现在收件箱里。
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    /**
     * 用户同意上传云端时的内容版本（2026-10-23 QODER P2-6）；null = 从未确认过。
     *
     * 独立成一列而不是改写 `sensitivity_status`：PRD §6.4 禁止"因一次确认就把
     * 整条记录永久标记为安全"，疑似标记必须原样保留；确认绑定的版本号让
     * "编辑内容后旧确认自动失效"成为一次整数比较，不依赖任何流程记得去清。
     */
    @ColumnInfo(name = "cloud_confirmed_revision")
    val cloudConfirmedRevision: Int? = null,

    /**
     * 用户确认时这条记录里已存在的内容块（2026-10-23 审查 R-2），逗号分隔的
     * `ContentPart.name`，如 `RAW_TEXT,OCR_TEXT`。
     *
     * 只存版本号不够：OCR / 转写落地时不递增 `record_revision`，一次确认会把
     * 之后才出现的内容一起放行。存成文本而不是关联表：内容块是**固定的枚举**，
     * 没有查询需求，一张表换不来任何东西。
     */
    @ColumnInfo(name = "cloud_confirmed_scope")
    val cloudConfirmedScope: String? = null,

    /**
     * 用户单独允许"抓取这个网页"时的内容版本（2026-10-23 复核 F-5）；null = 从未允许过。
     *
     * ## 为什么不能复用 `cloud_confirmed_revision`
     * 网页抓取与云端 AI 是两件事：前者向**源站**取页面（URL 出设备），后者把正文发到
     * AI 网关。复用一列会造成语义污染 —— 用户为了"能抓到正文"点一下，会顺带授权
     * 将来的 AI 上传；而确认 AI 上传又要求云端通路可用，默认无网关的安装包里
     * 敏感网页就永远抓不下来（F-5 记录的死路）。
     *
     * ## 为什么它不会被 [com.zhicun.app.data.local.dao.RecordDao.elevateSensitivity] 清掉
     * 抓出去的是 URL、抓回来的是公开页面的正文；正文复查命中敏感说明"这个页面本身有
     * 敏感内容"，并不能推翻"用户允许访问这个 URL"的授权，否则用户每抓一次都要重新授权
     * （抓取→正文命中→授权作废→再抓=死循环）。云端确认则相反：它授权的是"把正文发出去"，
     * 一旦新内容被发现敏感就必须作废。
     */
    @ColumnInfo(name = "web_fetch_confirmed_revision")
    val webFetchConfirmedRevision: Int? = null,
)
