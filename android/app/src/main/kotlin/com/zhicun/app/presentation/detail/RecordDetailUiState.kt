package com.zhicun.app.presentation.detail

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.settings.CloudUploadGate

// 本文件承接从 RecordDetailViewModel.kt 原样移出的详情页状态定义
// （AGENTS.md §2.1 单文件 400 行上限）。
// 纯移动、零行为变更：字段、默认值、KDoc 一字未改。

/** 详情页状态。 */
sealed interface RecordDetailUiState {

    data object Loading : RecordDetailUiState

    /** 数据库里没有这个 id（被彻底删除 / 深链参数错误）。 */
    data object NotFound : RecordDetailUiState

    data class Content(
        val record: Record,
        val assets: List<Asset>,
        /** 派生内容（M7 起：OCR 文字）。默认空列表，便于不关心它的用例构造状态。 */
        val derived: List<DerivedContent> = emptyList(),
        /** 标签（M11：AI 与用户）。默认空列表，理由同上。 */
        val tags: List<Tag> = emptyList(),
        /**
         * 云端为什么不可用（`null` = 可用，2026-10-23 审查 R-5）。
         *
         * 由 `CloudUploadGate` 算出后随状态下发：界面据此把"未上传"说成真实原因，
         * 而不是一律说成"疑似含敏感信息"。
         */
        val cloudBlockReason: CloudUploadGate.Reason? = null,
        /**
         * 是否需要用户再点一次"确认上传"（2026-10-23 审查 R-2）。
         *
         * 不能只看 `record.cloudConfirmed`（那是版本级判据）：确认之后 OCR / 转写才落地时，
         * 那块内容不在确认范围里，闸门仍会拦下摘要。此时横幅若按"已确认"隐藏，
         * 用户就再也点不了确认，界面永远停在"正在生成摘要"。
         */
        val cloudConfirmPending: Boolean = false,
        /**
         * 现在点"重试"会不会真的做事（2026-10-23 整改 F-4）。
         *
         * 判据与 `RetryProcessingUseCase` 完全一致（`ProcessingJobTargets.runnable` 非空）：
         * `TEXT_NOTE` / `SHARED_TEXT` / `FILE` / `VOICE_NOTE` 的任务全是云端任务，
         * 云端被挡时一个都跑不了 —— 此时界面给设置入口，不给那个点了没反应的按钮。
         * IMAGE / WEB_LINK 还有本地任务（OCR / 网页提取），照常可重试。
         */
        val canRetry: Boolean = true,
        /**
         * 空摘要 + 记录确实有正文（2026-11-05 线上回归兜底）。
         *
         * 这种记录状态是 SUCCESS、`hasFailure` 为假，AI 分区原本不会给重试入口，
         * 用户只能删掉重建。界面把它并进"失败"判据，让「重新生成」按钮出现。
         */
        val canRetryEmptySummary: Boolean = false,
        /**
         * 网页抓取是否仍需要用户**单独**点一次"允许抓取"（2026-10-24 用户反馈）。
         *
         * 敏感网页在云端通路可用时，详情页只保留 AI 分区的一个确认按钮，
         * 点它同时放行上传与抓取；这里为 false，网页分区不再显示第二个按钮。
         * 只有云端通路不可用（AI 分区给不出确认按钮）时才为 true —— 抓取不经网关，
         * 不该被云端配置卡死。
         */
        val webFetchConfirmVisible: Boolean = false,
        /**
         * 网页正文尚未抓取、且这次确认会一并抓取：AI 横幅据此说清"确认后会抓取网页"。
         */
        val webFetchPending: Boolean = false,
        /**
         * 合集成员与过期状态（M20）。非合集为空状态，既有调用点无需改。
         *
         * 成员是**实时读取**的原记录 + 派生内容，不是创建时的副本；成员内容变了，
         * 下次进入详情页看到的就是新内容。
         */
        val collection: DetailCollectionState = DetailCollectionState(),
        /**
         * 语音转写任务**自身**是否失败（2026-10-29 修复 P2）。
         *
         * 不能拿记录级 `processingStatus.hasFailure` 代替：转写成功产出空文本算成功
         * （静音录音很常见，TranscribeWorker 契约），但随后的摘要会因"没有正文"失败，
         * 记录被打成 PARTIAL —— 那时转写区若看记录级状态就会误报"转写失败"。
         * 判据在 ViewModel 里精确读 TRANSCRIPTION 任务的状态，这里只负责下发。
         */
        val transcribeJobFailed: Boolean = false,
    ) : RecordDetailUiState

    data class Error(val messageRes: Int) : RecordDetailUiState
}