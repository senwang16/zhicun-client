package com.zhicun.app.domain.model

/**
 * 合集与成员的关联（M20，数据库 v7）。
 *
 * 只描述"谁在合集里、按什么顺序读"，**不复制**成员内容：详情页按 [recordId] 实时读取
 * 原记录与派生内容，成员编辑后合集页看到的就是新内容。
 */
data class CollectionMember(
    val collectionId: String,
    val recordId: String,
    /** 合集内阅读顺序，从 0 开始（按成员 `createdAt` 升序）。 */
    val position: Int,
    val addedAt: Long,
    /** 上次成功生成合集摘要时的内容指纹；null = 从未参与过生成。 */
    val snapshotFingerprint: String?,
)

/** 成员关系 + 当前原记录（详情页与处理链路共用，避免各自再查一遍）。 */
data class CollectionMemberWithRecord(
    val member: CollectionMember,
    val record: Record,
)

/**
 * 成员内容指纹（M20 过期判定）。
 *
 * ## 为什么不用 `updated_at`
 * 收藏 / 置顶也会推进 `updated_at`，却完全没有改变内容。用时间戳会让"给成员点了个星"
 * 就弹出"合集来源有更新，请重新生成"，用户会学会忽略这个横幅。
 *
 * ## 组成
 * - `recordRevision`：用户改原文（合集说明对成员不适用，但成员正文会变）；
 * - `isDeleted`：已删除成员退出重新生成，横幅文案也要跟着变；
 * - `isArchived`：归档不改变内容，但详情页要显示「已归档」标记，快照需记录生成时的事实；
 * - 正文类派生内容的最新 `processedAt`：OCR / 网页正文 / 转写重新生成后，指纹必须变。
 *
 * 收藏、置顶、敏感标记都不参与：它们不影响"合集摘要是对哪份内容的总结"。
 */
object CollectionMemberFingerprint {

    private val BODY_TYPES = setOf(
        DerivedType.PAGE_TEXT,
        DerivedType.OCR_TEXT,
        DerivedType.TRANSCRIPTION,
    )

    fun of(record: Record, derived: List<DerivedContent>): String {
        val latestBodyAt = derived
            .filter { it.contentType in BODY_TYPES }
            .maxOfOrNull { it.processedAt }
            ?: 0L
        return buildString {
            append("rev=").append(record.recordRevision)
            append(";deleted=").append(if (record.isDeleted) 1 else 0)
            append(";archived=").append(if (record.isArchived) 1 else 0)
            append(";body=").append(latestBodyAt)
        }
    }
}

/**
 * 合集敏感状态：成员并集（M20 隐私边界）。
 *
 * 严重度顺序：`CONFIRMED_SENSITIVE > SUSPECTED > UNKNOWN > CLEAR`。
 * 任一成员未知就落 `UNKNOWN`，而不是当干净处理 —— 未知的含义是"还没有判据"，
 * 在敏感闸门里按最保守的方向落。
 *
 * **只传会真正参与合集摘要的成员**（未删除；归档仍参与）。已删除成员的内容不会外发，
 * 把它算进并集只会让合集一直被拦，却拦不到任何真实的外发内容。
 */
object CollectionSensitivity {

    fun union(statuses: Collection<SensitivityStatus>): SensitivityStatus = when {
        statuses.any { it == SensitivityStatus.CONFIRMED_SENSITIVE } ->
            SensitivityStatus.CONFIRMED_SENSITIVE
        statuses.any { it == SensitivityStatus.SUSPECTED } -> SensitivityStatus.SUSPECTED
        statuses.isEmpty() || statuses.any { it == SensitivityStatus.UNKNOWN } ->
            SensitivityStatus.UNKNOWN
        else -> SensitivityStatus.CLEAR
    }
}
