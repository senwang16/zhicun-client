package com.zhicun.app.presentation.detail

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.presentation.common.ProcessingStatusBadge
import com.zhicun.app.presentation.common.captureSourceLabelRes
import com.zhicun.app.presentation.common.formatTimestamp
import com.zhicun.app.presentation.common.recordTypeLabelRes
import com.zhicun.app.presentation.common.uiLocale

/**
 * 详情页正文（从上到下，2026-10 改版）：已删横幅 → **AI 摘要** → 原文 → 按记录类型的派生分区 → 整理 → 相关记录 → 来源 → 状态 → 附件。
 *
 * 这个文件只负责**装配与顺序**；各分区自己的实现在
 * `RecordDetailDerived.kt`（OCR/网页/语音）、`RecordDetailAi.kt`（AI 摘要与标签）、
 * `RecordDetailInfo.kt`（信息行/附件）里 —— 详情页界面曾挤在一个 960 行的文件里，
 * 按分区拆开后每次改某个分区不用再在整份文件里找位置（M16 技术债 D-1）。
 */
@Composable
internal fun DetailBody(
    record: Record,
    assets: List<Asset>,
    derived: List<DerivedContent>,
    modifier: Modifier = Modifier,
    tags: List<Tag> = emptyList(),
    onRetry: () -> Unit = {},
    playback: VoicePlaybackState = VoicePlaybackState(),
    /** 参数是音频附件 id（2026-10-29 多音频）；null = 第一段，兼容老调用点。 */
    onPlayPause: (String?) -> Unit = {},
    onConfirmCloud: () -> Unit = {},
    /** 点"查看快照"回调，参数是快照文件绝对路径（C）。 */
    onOpenSnapshot: (String) -> Unit = {},
    /**
     * 云端为什么不可用（2026-10-23 审查 R-5）：`null` = 可用。
     *
     * 由 ViewModel 用 `CloudUploadGate` 算出后一路透传，界面自己不去读设置 ——
     * 否则"为什么没上传"会在 UI 里出现第二套判据，与门禁逐渐走样。
     */
    cloudBlockReason: CloudUploadGate.Reason? = null,
    /** 是否要显示"待确认"横幅（2026-10-23 R-2）；由 ViewModel 用确认范围算出，界面不自己判。 */
    cloudConfirmPending: Boolean = false,
    /**
     * 网页抓取是否还要单独出"允许抓取"按钮（2026-10-24 用户反馈）。
     *
     * 云端通路可用时 AI 分区的确认同时放行抓取，这里为 false；通路不可用时才退回独立入口。
     */
    webFetchConfirmVisible: Boolean = true,
    /** 网页正文尚未抓取、且这次确认会一并抓取（决定 AI 横幅的文案）。 */
    webFetchPending: Boolean = false,
    /**
     * 现在点重试会不会真的做事（2026-10-23 整改 F-4）。
     *
     * 判据在 ViewModel 里算好（与 `RetryProcessingUseCase` 共用 `ProcessingJobTargets.runnable`），
     * 界面只负责"不能重试时换一个入口"。默认 true 让既有调用点与测试零改动。
     */
    canRetry: Boolean = true,
    /**
     * 空摘要 + 记录确实有正文（2026-11-05 线上回归兜底）。
     *
     * 那种记录状态是 SUCCESS，`hasFailure` 为假，AI 分区原本不给重试入口。
     * 这里只负责透传给 [AiBlock]，由它并进"失败"判据。默认 false，既有调用点零改动。
     */
    canRetryEmptySummary: Boolean = false,
    /**
     * 语音转写任务**自身**是否失败（2026-10-29 修复 P2）。
     *
     * 记录级 `processingStatus.hasFailure` 会被摘要/嵌入失败污染（PARTIAL），
     * 转写文本已成功落库时误报"转写失败"。判据在 ViewModel 里算好
     * （TRANSCRIPTION 任务 status == FAILED），这里只透传给 [VoiceBlock]。
     * 默认 false，既有调用点与测试零改动。
     */
    transcribeFailed: Boolean = false,
    /** 点"去设置里打开云端 AI"；null = 不给入口（预览/测试可以直接不传）。 */
    onOpenSettings: (() -> Unit)? = null,
    /** 点"允许抓取此网页"（2026-10-23 复核 F-5）。 */
    onConfirmFetch: () -> Unit = {},
    /** 标签行编辑保存（U-7，2026-10-24）：传入编辑框解析出的全量标签名。 */
    onTagsEdited: (List<String>) -> Unit = {},
    /**
     * 点附件条目（2026-10-24）。
     *
     * 打开动作在 `RecordDetailScreen` 里做（FileProvider + `ACTION_VIEW`），
     * 分区组件保持纯展示：它不认识 Activity、Intent 与 FileProvider。
     */
    onOpenAsset: (Asset) -> Unit = {},
    /**
     * 分享附件（2026-10-28 用户反馈）：系统查看器在部分 ROM 上只能编辑、没有分享，
     * 这里给一条独立的 `ACTION_SEND` 路径，方便发到微信/QQ 等社交平台。
     */
    onShareAsset: (Asset) -> Unit = {},
    /** 附件行「删除附件」（2026-10-29）：只发起请求，确认与执行在页面壳。 */
    onRequestDeleteAsset: (Asset) -> Unit = {},
    /** 原文编辑状态（2026-10-26）：编辑中把原文卡换成输入框，保存后触发 AI 重算。 */
    contentEdit: DetailContentEditState = DetailContentEditState(),
    onStartRawTextEdit: () -> Unit = {},
    onRawTextDraftChange: (String) -> Unit = {},
    onCancelRawTextEdit: () -> Unit = {},
    onSaveRawText: () -> Unit = {},
    /**
     * 复制某一块的文字（2026-10-26 用户裁决：每个模块各自复制）。
     * `null` = 调用方没有剪贴板能力（预览/部分测试），不画复制按钮。
     */
    onCopyText: ((String) -> Unit)? = null,
    /** 合集成员与过期状态（M20）。非合集为空状态，既有调用点零改动。 */
    collection: DetailCollectionState = DetailCollectionState(),
    /** 点合集来源卡片跳回原记录（M20）。 */
    onRecordClick: (String) -> Unit = {},
    organizeSection: @Composable () -> Unit = {},
    /** 相关记录分区（M18）：空列表时调用方不渲染任何东西，这里只管给位置。 */
    relatedSection: @Composable () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            // 点页面空白处收起焦点（2026-10-26）：标签编辑靠失焦取消，
            // 没有这一层的话，用户点完别处仍然停在编辑态，只能去找取消按钮
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (record.isDeleted) {
            DeletedBanner()
        }

        // AI 派生结果（M11）：2026-10 改版后是详情页第一区块（设计稿 02）——
        // 用户点开一条记录最想看的是"AI 帮我提炼了什么"，原文往下排
        SectionTitle(text = stringResource(R.string.detail_section_ai))
        AiBlock(
            record = record,
            derived = derived,
            tags = tags,
            onRetry = onRetry,
            onConfirm = onConfirmCloud,
            cloudBlockReason = cloudBlockReason,
            cloudConfirmPending = cloudConfirmPending,
            canRetry = canRetry,
            canRetryEmptySummary = canRetryEmptySummary,
            webFetchPending = webFetchPending,
            onOpenSettings = onOpenSettings,
            onTagsEdited = onTagsEdited,
            onCopyText = onCopyText,
        )

        // 过期提示（M20）：只对"已经生成过摘要、成员内容又变了"的合集出现。
        // 还在处理中的合集不该看到"重新生成"——那会让人以为后台没在干活；
        // 敏感待确认时也不出现：AI 分区的确认按钮本身就会重新排队。
        if (
            record.type == RecordType.COLLECTION &&
            collection.stale &&
            record.processingStatus == ProcessingStatus.DONE &&
            !cloudConfirmPending
        ) {
            CollectionStaleBanner(
                canRegenerate = canRetry,
                onRegenerate = onRetry,
                onOpenSettings = onOpenSettings,
            )
        }

        SectionTitle(
            text = stringResource(
                if (record.type == RecordType.COLLECTION) {
                    R.string.detail_section_collection_description
                } else {
                    R.string.detail_section_content
                },
            ),
        )
        RawTextCard(
            record = record,
            editState = contentEdit,
            onStartEdit = onStartRawTextEdit,
            onDraftChange = onRawTextDraftChange,
            onCancelEdit = onCancelRawTextEdit,
            onSave = onSaveRawText,
            onCopyText = onCopyText,
            emptyTextRes = if (record.type == RecordType.COLLECTION) {
                R.string.detail_collection_no_description
            } else {
                R.string.detail_no_content
            },
        )

        // 附件紧跟原文（2026-10-24）：原来在页面最底部（状态之后），用户根本翻不到。
        // 没有附件时整块不渲染 —— "无附件"这行字对用户没有信息量。
        if (assets.isNotEmpty()) {
            SectionTitle(text = stringResource(R.string.detail_section_assets))
            AssetsBlock(
                assets = assets,
                onOpenAsset = onOpenAsset,
                onShareAsset = onShareAsset,
                onDeleteAsset = onRequestDeleteAsset,
            )
        }

        // 派生结果分区：M7 接入图片 OCR，M8 接入网页正文提取，M9 接入语音转写
        when (record.type) {
            RecordType.IMAGE -> {
                SectionTitle(text = stringResource(R.string.detail_section_ocr))
                OcrBlock(
                    record = record,
                    assets = assets,
                    derived = derived,
                    onRetry = onRetry,
                    canRetry = canRetry,
                    onOpenSettings = onOpenSettings,
                    onCopyText = onCopyText,
                )
            }

            RecordType.WEB_LINK -> {
                SectionTitle(text = stringResource(R.string.detail_section_web))
                WebBlock(
                    record = record,
                    derived = derived,
                    assets = assets,
                    onOpenSnapshot = onOpenSnapshot,
                    onRetry = onRetry,
                    canRetry = canRetry,
                    onOpenSettings = onOpenSettings,
                    onConfirmFetch = onConfirmFetch,
                    showFetchConfirm = webFetchConfirmVisible,
                    onCopyText = onCopyText,
                )
            }

            RecordType.VOICE_NOTE -> {
                SectionTitle(text = stringResource(R.string.detail_section_voice))
                VoiceBlock(
                    record = record,
                    assets = assets,
                    derived = derived,
                    playback = playback,
                    onPlayPause = onPlayPause,
                    onRetry = onRetry,
                    cloudBlockReason = cloudBlockReason,
                    canRetry = canRetry,
                    transcribeFailed = transcribeFailed,
                    onOpenSettings = onOpenSettings,
                    onCopyText = onCopyText,
                )
            }

            RecordType.TEXT_NOTE,
            RecordType.SHARED_TEXT,
            RecordType.FILE,
            -> Unit

            // 合集来源清单（M20）：成员的原记录/附件不复制，卡片点回原记录看完整内容。
            // 已删除/已归档成员照常展示并打标，是否参与重新生成由处理链路决定。
            RecordType.COLLECTION -> {
                SectionTitle(text = stringResource(R.string.detail_section_collection_members))
                CollectionMembersBlock(
                    state = collection,
                    onRecordClick = onRecordClick,
                    onOpenAsset = onOpenAsset,
                    onShareAsset = onShareAsset,
                )
            }
        }

        // 整理（M16）：收藏 / 置顶 / 归档 + 用户标签。
        // 用槽位而不是直接调用：整理分区的实现已经另起文件（DetailOrganizeBlock.kt），
        // 这里只负责给它一个位置。默认空实现时什么都不渲染，不会留下一个孤零零的标题。
        organizeSection()

        // 相关记录（M18）：同模型向量余弦过下限的历史记录，辅助"想起还有这条"
        relatedSection()

        SectionTitle(text = stringResource(R.string.detail_section_source))
        InfoGroup {
            InfoRow(R.string.detail_label_type, stringResource(recordTypeLabelRes(record.type)))
            InfoRow(
                R.string.detail_label_capture_source,
                stringResource(captureSourceLabelRes(record.captureSource)),
            )
            InfoRow(R.string.detail_label_source_app, record.sourceApp)
            InfoRow(R.string.detail_label_source_url, record.sourceUrl, clickableUrl = true)
            InfoRow(R.string.detail_label_created_at, formatTimestamp(record.createdAt, uiLocale()))
            InfoRow(R.string.detail_label_updated_at, formatTimestamp(record.updatedAt, uiLocale()))
        }

        SectionTitle(text = stringResource(R.string.detail_section_status))
        InfoGroup {
            InfoRowSlot(labelRes = R.string.detail_label_processing) {
                ProcessingStatusBadge(status = record.processingStatus)
            }
            InfoRow(
                R.string.detail_label_local_status,
                stringResource(
                    if (record.localStatus == LocalStatus.SAVED) {
                        R.string.detail_local_saved
                    } else {
                        R.string.detail_local_failed
                    },
                ),
            )
            InfoRow(
                R.string.detail_label_sensitivity,
                stringResource(sensitivityLabelRes(record.sensitivityStatus)),
            )
            InfoRow(R.string.detail_label_revision, record.recordRevision.toString())
        }

        // 手动重新处理（2026-10-26 用户反馈）：只在未完成时出现 ——
        // 已达成的记录再点一次只会白白重算；卡住/状态说不清时，这里是唯一的自救入口。
        if (record.processingStatus != ProcessingStatus.DONE && !record.isDeleted) {
            ManualReprocessRow(
                canRetry = canRetry,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DeletedBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.detail_deleted_banner),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
