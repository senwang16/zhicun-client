package com.zhicun.app.presentation.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.settings.CloudUploadGate

// 本文件承接从 RecordDetailDerived.kt 原样移出的「网页正文 + 快照 + og:image 预览」区块
// （AGENTS.md §2.1 单文件 400 行上限）。纯移动、零行为变更：
// 判据、文案、testTag、Modifier 链、图片采样参数一字未改。

/** 图片附件的 MIME 前缀（C：og:image 预览）。 */
private const val IMAGE_MIME_PREFIX = "image/"

/** HTML 快照的 MIME（C）。 */
private const val HTML_MIME = "text/html"

/**
 * 网页正文块（M8 + C 快照）。
 *
 * 标题与正文是两条派生内容：标题可能抽不到（比如页面只有 `<title>` 之外的标题结构），
 * 那时只显示正文，不要留一行空的"标题"。
 *
 * 快照与配图（C）都来自附件行：配图直接显示预览，快照给一个查看入口。
 * 两者都是附加能力——缺失时什么都不渲染，绝不影响正文的四态展示。
 */
@Composable
internal fun WebBlock(
    record: Record,
    derived: List<DerivedContent>,
    assets: List<Asset>,
    onOpenSnapshot: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 重试是否真的会做事（F-4）；网页提取是本地任务，通常为 true。 */
    canRetry: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    /** 用户点"允许抓取此网页"（F-5）；只在被敏感闸拦下时出现。 */
    onConfirmFetch: () -> Unit = {},
    /**
     * 是否显示"允许抓取此网页"的独立入口（2026-10-24 用户反馈）。
     *
     * 云端通路可用时，AI 分区的确认按钮同时放行抓取，这里传 false —— 界面上只有一个
     * 确认动作，用户不用为同一份内容点两次。云端通路不可用（AI 分区给不出按钮）时
     * 传 true：抓取不经 AI 网关，不能被云端配置卡死。
     */
    showFetchConfirm: Boolean = true,
    /** 复制抓取到的网页正文（2026-10-26 用户裁决：每个模块各自复制）。 */
    onCopyText: ((String) -> Unit)? = null,
) {
    val title = derived.firstOrNull { it.contentType == DerivedType.TITLE }?.content?.trim()
    val pageText = derived.firstOrNull { it.contentType == DerivedType.PAGE_TEXT }

    // 敏感记录压根没发起请求（R-7）：这类"没有结果"需要用户先允许抓取，重试没有意义。
    // 判据用 webFetchBlockedReason 而不是 sensitiveEgressReason：后者要求"云端上传确认"
    // 才放行，而那个确认要云端通路可用 —— 默认无网关时敏感网页会永远抓不下来（F-5）。
    val sensitiveBlocked = record.processingStatus == ProcessingStatus.LOCAL_ONLY &&
        pageText == null &&
        CloudUploadGate.webFetchBlockedReason(record) != null

    Column(modifier = modifier) {
        // og:image 预览：存在且文件可用才渲染
        val ogImage = assets.firstOrNull { it.isUsableImage() }
        if (ogImage != null) {
            OgImagePreview(localPath = ogImage.localPath)
            Spacer(modifier = Modifier.height(8.dp))
        }

        DerivedBlock(
            heading = title?.takeIf { it.isNotEmpty() },
            text = pageText?.content?.trim().orEmpty(),
            // errorMessage 非空 = 抓到了但偏短（登录墙/脚本渲染），要如实提示"不一定完整"
            partialHint = pageText?.errorMessage?.takeIf { it.isNotEmpty() },
            failureText = stringResource(R.string.detail_web_failed),
            pendingText = stringResource(R.string.detail_web_pending),
            emptyText = stringResource(R.string.detail_web_failed),
            retryText = stringResource(R.string.detail_web_retry),
            retryTag = RecordDetailTestTags.WEB_RETRY,
            textTag = RecordDetailTestTags.WEB_TEXT,
            // 网页提取同样是本地任务（向源站取页面，不经 AI 网关），见下方 `blockedText` 的说明
            // 注意用 hasFailure 而不是 isIncomplete：LOCAL_ONLY 且没内容时，原因是"敏感被拦"
            // （见 blockedText），说成"提取失败"会把策略说成网络故障。
            failed = record.processingStatus.hasFailure,
            hasResult = pageText != null,
            onRetry = onRetry,
            // 敏感记录根本没有发起请求（R-7）：说"提取失败"会把策略说成网络故障。
            // 云端通路可用时不在这里再问一次（2026-10-24 用户反馈）：上方 AI 分区的
            // 确认会一并放行抓取，这里只说明"为什么还没有正文"。
            blockedText = when {
                !sensitiveBlocked -> null
                showFetchConfirm -> stringResource(R.string.detail_web_blocked_sensitive)
                else -> stringResource(R.string.detail_web_blocked_awaiting_confirm)
            },
            // 重试同样会被这道闸拦下：出路是去 AI 分区确认上传，不是反复点重试
            retryableWhenBlocked = !sensitiveBlocked,
            canRetry = canRetry,
            onOpenSettings = onOpenSettings,
            onCopyText = onCopyText,
        )

        // 被敏感闸拦下且云端通路不可用时的出路：单独允许"抓这个网页"（F-5）。
        // 云端可用时这个区块不出现 —— 上方 AI 分区的确认已包含抓取（2026-10-24 用户反馈）。
        if (sensitiveBlocked && showFetchConfirm) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.detail_web_confirm_fetch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onConfirmFetch,
                modifier = Modifier.testTag(RecordDetailTestTags.WEB_CONFIRM_FETCH),
            ) {
                Text(text = stringResource(R.string.detail_web_confirm_fetch))
            }
        }

        // 快照入口：只有 HTML 快照在盘上才给按钮
        val snapshot = assets.firstOrNull { it.isUsableHtmlSnapshot() }
        if (snapshot != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { onOpenSnapshot(snapshot.localPath) },
                modifier = Modifier.testTag(RecordDetailTestTags.WEB_SNAPSHOT),
            ) {
                Text(text = stringResource(R.string.detail_web_snapshot))
            }
        }
    }
}

/** 附件行可用的判定：已保存、路径非空、类型匹配。空串路径参见 CopyAssetToPrivateDirUseCase。 */
private fun Asset.isUsableImage(): Boolean =
    saveStatus == AssetSaveStatus.SAVED &&
        localPath.isNotBlank() &&
        mimeType.startsWith(IMAGE_MIME_PREFIX, ignoreCase = true)

private fun Asset.isUsableHtmlSnapshot(): Boolean =
    saveStatus == AssetSaveStatus.SAVED &&
        localPath.isNotBlank() &&
        mimeType.equals(HTML_MIME, ignoreCase = true)

/**
 * og:image 预览图（C）：BitmapFactory 采样解码，不引图片库。
 * 解码失败（文件被清理、不是图片）返回 null，界面直接不显示——预览是锦上添花。
 */
@Composable
private fun OgImagePreview(localPath: String, modifier: Modifier = Modifier) {
    val bitmap = remember(localPath) { decodeSampledBitmap(localPath, targetWidth = 640) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .testTag(RecordDetailTestTags.WEB_OG_IMAGE),
            contentScale = ContentScale.Crop,
        )
    }
}

/** 采样解码：按目标宽算 inSampleSize，避免把几千像素的原图整个读进内存。 */
private fun decodeSampledBitmap(path: String, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}