package com.zhicun.app.presentation.detail

import com.zhicun.app.domain.ai.CollectionMemberInput
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.ai.SummaryPolicy
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessingJobTargets

/**
 * 详情页的门禁判据推导 —— 从 `RecordDetailViewModel.render()` 原样抽出。
 *
 * 纯计算、无副作用、不碰协程与状态：输入是记录 + 附件 + 派生内容 + 云端开关，
 * 输出是界面要用的几个布尔/原因。抽出的目的是让 ViewModel 回到 400 行以内
 * （AGENTS.md §2.1），判据本身一处都没改。
 *
 * 集中在这里还有一个额外好处：这些推导必须与 `RetryProcessingUseCase` /
 * `ConfirmCloudProcessingUseCase` 的口径保持一致，放在同一个文件里更好对照。
 */
internal data class DetailGateState(
    /** 云端为什么不可用（null = 可用）。 */
    val cloudBlockReason: CloudUploadGate.Reason?,
    /** 是否需要用户再点一次"确认上传"。 */
    val cloudConfirmPending: Boolean,
    /** 现在点"重试"会不会真的做事。 */
    val canRetry: Boolean,
    /**
     * 摘要已落库但内容为空、而记录**确实有正文**（2026-11-05 线上回归兜底）。
     *
     * 那次上游没返回摘要却被记成了成功，于是 `hasFailure` 为假、界面不给重试入口，
     * 用户只能删掉重建。这里把"该重试"补回来：界面把它并进 `failed` 即可。
     *
     * 判据里必须带 `canRetry`：云端不可用时点重试是空动作，不该给按钮。
     * 也必须是"正文达到摘要阈值"（`SummaryPolicy`）——本来就没有正文、或短到
     * 不需要摘要的记录（FILE 无文本层、「买牛奶」这类备忘）重试多少次都是空摘要。
     */
    val canRetryEmptySummary: Boolean,
    /** 网页抓取是否仍需要用户单独点一次"允许抓取"。 */
    val webFetchConfirmVisible: Boolean,
    /** 网页正文尚未抓取、且这次确认会一并抓取。 */
    val webFetchPending: Boolean,
)

/**
 * 按记录、附件、派生内容与云端配置算出详情页的门禁状态。
 *
 * 每一项的判据来源（勿随意改写，与对应 UseCase 强耦合）：
 * - `cloudBlockReason` ← [CloudUploadGate.cloudPathBlockedReason]（R-5）
 * - `cloudConfirmPending` ← `record.cloudConfirmedFor(presentParts(...))`（R-2）
 * - `canRetry` ← [ProcessingJobTargets.runnable]（F-4）
 * - `webFetchPending` / `webFetchConfirmVisible` ← 网页抓取与云端通路解耦（F-5）
 */
internal fun computeDetailGateState(
    record: Record,
    assets: List<Asset>,
    derived: List<DerivedContent>,
    cloudAiEnabled: Boolean,
    gatewayConfigured: Boolean,
    collectionMembers: List<CollectionMemberView> = emptyList(),
): DetailGateState {
    // 合集的有效敏感状态 = 未删除成员的实时并集 + 合集说明的扫描结果。
    // 库里那份是快照：成员刚被改过、刷新还没落库时，不能拿旧值判闸。
    val effectiveSensitivity = if (record.type == RecordType.COLLECTION) {
        CollectionSensitivity.union(
            collectionMembers
                .filterNot { it.record.isDeleted }
                .map { it.record.sensitivityStatus } + record.sensitivityStatus,
        )
    } else {
        record.sensitivityStatus
    }
    val gatedRecord = record.copy(sensitivityStatus = effectiveSensitivity)

    // "为什么没上传"由门禁给出唯一答案：界面不自己拼条件（R-5）
    val cloudBlockReason = CloudUploadGate.cloudPathBlockedReason(
        cloudAiEnabled = cloudAiEnabled,
        gatewayConfigured = gatewayConfigured,
    )
    // 确认范围必须与真正外发的内容同源（M20）：合集发的是成员正文，
    // 只按合集自己的说明算范围，会让成员内容被一次旧确认放行。
    val requiredParts = if (record.type == RecordType.COLLECTION) {
        ProcessingInputText.collectionPresentParts(
            collection = record,
            descriptionDerived = derived,
            members = collectionMembers.map { CollectionMemberInput(it.record, it.derived) },
        )
    } else {
        ProcessingInputText.presentParts(record, derived, assets.any { it.isUploadable })
    }
    // "要不要再确认一次"同理：判据仍是 `cloudConfirmedFor`，界面不自己比版本号（R-2）
    val confirmPending = effectiveSensitivity.isSensitive &&
        !record.cloudConfirmedFor(requiredParts)
    // "重试会不会做事"（F-4）：与重试用例共用 `runnable` 判据。
    // 这里按记录级门禁算（含敏感未确认），不含具体内容块 —— 与 `RetryProcessingUseCase`
    // 的入口预检同口径，界面上"按钮出现 ⇔ 点了有用"。
    val cloudBlockedForRetry = CloudUploadGate.reasonOf(
        cloudAiEnabled = cloudAiEnabled,
        gatewayConfigured = gatewayConfigured,
        record = gatedRecord,
    ) != null
    val canRetry = ProcessingJobTargets.runnable(record.type, cloudBlockedForRetry).isNotEmpty()
    // 存量兜底：空摘要 + 记录确实有正文 ⇒ 那次上游没给出摘要却被记成了成功，
    // 状态是 SUCCESS 所以界面不给重试入口。这里把"该重试"补回来。
    // 有正文的判据必须与 SummarizeWorker 送摘要的输入同源，否则会出现"按钮点了还是空"。
    val summaryBlank = derived
        .firstOrNull { it.contentType == DerivedType.SUMMARY }
        ?.content
        ?.isBlank() == true
    val summaryBody = if (record.type == RecordType.COLLECTION) {
        ProcessingInputText.collectionSummaryInput(
            collection = record,
            descriptionDerived = derived,
            members = collectionMembers.map { CollectionMemberInput(it.record, it.derived) },
        ).text
    } else {
        ProcessingInputText.summaryInput(record, derived).text
    }
    val canRetryEmptySummary = summaryBlank && SummaryPolicy.requiresSummary(summaryBody) && canRetry
    // 网页抓取是否还卡在敏感闸上（2026-10-24 用户反馈）：与 `WebBlock` 的判据同源，
    // 只在这里算一次。云端通路可用时，这一个确认同时覆盖上传与抓取，
    // 网页分区不再显示第二个按钮；通路不可用时才退回单独的"允许抓取"。
    val pageTextExists = derived.any { it.contentType == DerivedType.PAGE_TEXT }
    val webFetchPending = record.type == RecordType.WEB_LINK &&
        record.processingStatus == ProcessingStatus.LOCAL_ONLY &&
        !pageTextExists &&
        CloudUploadGate.webFetchBlockedReason(record) != null
    val oneClickCoversFetch = webFetchPending && confirmPending && cloudBlockReason == null

    return DetailGateState(
        cloudBlockReason = cloudBlockReason,
        cloudConfirmPending = confirmPending,
        canRetry = canRetry,
        canRetryEmptySummary = canRetryEmptySummary,
        webFetchConfirmVisible = webFetchPending && !oneClickCoversFetch,
        webFetchPending = webFetchPending,
    )
}
