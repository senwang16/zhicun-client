package com.zhicun.app.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record

/**
 * OCR 结果块（M7）。
 *
 * 四态与判定顺序见 [DerivedBlock]；这里只负责把这四种形态映射到 OCR 的文案。
 *
 * OCR 是**本地**任务，不受云端闸门影响，所以没有"未上传"态：
 * 没有结果又不能算"结果为空"时，只可能是没跑成（[ProcessingStatus.isIncomplete]），
 * 直接按失败呈现并给重试，比一直显示"正在识别"诚实。
 *
 * 多图（2026-10-29）：图片 ≥ 2 且结果确实按附件落库时，逐张分段展示；
 * 只有一条记录级结果的老数据保持原来的单块形态，等升级后的补跑自然变成分段。
 */
@Composable
internal fun OcrBlock(
    record: Record,
    assets: List<Asset>,
    derived: List<DerivedContent>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 重试是否真的会做事（F-4）；OCR 是本地任务，通常为 true。 */
    canRetry: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    /** 复制识别出的文字（2026-10-26 用户裁决：每个模块各自复制）。 */
    onCopyText: ((String) -> Unit)? = null,
) {
    val images = assets.filter { it.mimeType.startsWith("image/", ignoreCase = true) }
    val rows = derived.filter { it.contentType == DerivedType.OCR_TEXT }
    val imageIds = images.map { it.id }.toSet()
    // 只有"确实按附件落过结果"且图片 ≥ 2 才分段（2026-10-29 多图）。
    // 升级后尚未重跑的老记录只有一条 assetId 为空的结果，此时保持旧样子，
    // 等后台补跑完成会自然变成分段 —— 否则老记录会同时出现 N 个"正在识别"和一段旧文字。
    val perAssetRows = rows.filter { it.assetId != null && it.assetId in imageIds }

    if (images.size < 2 || perAssetRows.isEmpty()) {
        val single = perAssetRows.firstOrNull() ?: rows.firstOrNull()
        DerivedBlock(
            modifier = modifier,
            heading = null,
            text = single?.content?.trim().orEmpty(),
            partialHint = null,
            failureText = stringResource(R.string.detail_ocr_failed),
            pendingText = stringResource(R.string.detail_ocr_pending),
            emptyText = stringResource(R.string.detail_ocr_empty),
            retryText = stringResource(R.string.detail_ocr_retry),
            retryTag = RecordDetailTestTags.OCR_RETRY,
            textTag = RecordDetailTestTags.OCR_TEXT,
            failed = record.processingStatus.isIncomplete,
            hasResult = single != null,
            onRetry = onRetry,
            canRetry = canRetry,
            onOpenSettings = onOpenSettings,
            onCopyText = onCopyText,
        )
        return
    }

    val legacyRow = rows.firstOrNull { it.assetId == null }
    val segments = images.mapNotNull { image ->
        perAssetRows.firstOrNull { it.assetId == image.id }?.content?.trim()?.takeIf(String::isNotEmpty)
    } + listOfNotNull(legacyRow?.content?.trim()?.takeIf(String::isNotEmpty))
    val combined = segments.joinToString("\n\n")
    val failed = record.processingStatus.isIncomplete

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        images.forEachIndexed { index, image ->
            OcrSegmentCard(
                index = index + 1,
                image = image,
                row = perAssetRows.firstOrNull { it.assetId == image.id },
                failed = failed,
            )
        }
        // 老格式结果（升级前写入的 assetId='' 行）单独作为一段：既不丢内容，
        // 也不把它硬塞给某一张图 —— 那会让人以为只有这张图识别成功
        if (legacyRow != null) {
            OcrLegacyCard(row = legacyRow, failed = failed)
        }

        if (combined.isNotEmpty() || failed || !canRetry) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onCopyText != null && combined.isNotEmpty()) {
                    CopyTextButton(
                        text = combined,
                        onCopy = onCopyText,
                        testTag = "${RecordDetailTestTags.OCR_TEXT}_copy",
                    )
                }
                when {
                    failed && canRetry -> DetailPillButton(
                        label = stringResource(R.string.detail_ocr_retry),
                        iconRes = R.drawable.ic_action_refresh,
                        onClick = onRetry,
                        modifier = Modifier.testTag(RecordDetailTestTags.OCR_RETRY),
                    )

                    failed && onOpenSettings != null -> DetailPillButton(
                        label = stringResource(R.string.detail_open_cloud_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(RecordDetailTestTags.OPEN_SETTINGS),
                    )
                }
            }
        }
    }
}

/**
 * 单张图片的识别结果卡（多图时使用）。
 *
 * 标题用"第 N 张 · 原文件名"而不是附件 id：用户认的是文件名与顺序，
 * 顺序与附件区块一致（`order_index`），两处对不上会让人怀疑识别结果串了。
 */
@Composable
private fun OcrSegmentCard(
    index: Int,
    image: Asset,
    row: DerivedContent?,
    failed: Boolean,
) {
    val name = assetDisplayName(image)
    val title = if (name != null) {
        stringResource(R.string.detail_ocr_segment_title, index, name)
    } else {
        stringResource(R.string.detail_ocr_segment_title_untitled, index)
    }
    SegmentSurface(title = title) {
        SegmentedDerivedText(
            text = row?.content?.trim().orEmpty(),
            errorMessage = row?.errorMessage,
            failed = failed,
            pendingText = stringResource(R.string.detail_ocr_pending),
            emptyText = stringResource(R.string.detail_ocr_empty),
            failureText = stringResource(R.string.detail_ocr_segment_failed),
            testTag = RecordDetailTestTags.ocrTextTag(image.id),
        )
    }
}

/** 老格式 OCR 行（assetId 为空）：多图记录里只剩它时单独成卡，不冒充某一张图。 */
@Composable
private fun OcrLegacyCard(row: DerivedContent, failed: Boolean) {
    SegmentSurface(title = stringResource(R.string.detail_ocr_legacy_heading)) {
        SegmentedDerivedText(
            text = row.content?.trim().orEmpty(),
            errorMessage = row.errorMessage,
            failed = failed,
            pendingText = stringResource(R.string.detail_ocr_pending),
            emptyText = stringResource(R.string.detail_ocr_empty),
            failureText = stringResource(R.string.detail_ocr_segment_failed),
            testTag = RecordDetailTestTags.OCR_TEXT,
        )
    }
}
