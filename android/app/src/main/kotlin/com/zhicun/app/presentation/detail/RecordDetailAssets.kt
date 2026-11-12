package com.zhicun.app.presentation.detail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.presentation.common.formatFileSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件分区（2026-10-24 重做）。
 *
 * ## 为什么不再把 MIME 当标题
 * 原来一行是 `audio/mp4 · 1.2 MB · 已保存` —— 录音在用户眼里变成了"MP4 文件"，
 * PDF 也只是个 `application/pdf`。现在主行是**人类可读类型名**（图片 / PDF / 录音 / 视频 / 文本 / 文件），
 * 文件名退到第二行灰色小字（真实文件名来自 `localPath`，不是 MIME）。
 *
 * ## 位置
 * 由 `RecordDetailSections` 放在**原文下面**：用户看完自己写的东西，紧接着就是附件，
 * 而不是滚到页面最底部（状态、来源之后）才发现"原来还有一张图"。
 *
 * ## 点击
 * 图片显示缩略图，其它类型显示类型图标。可点击的判据是
 * `saveStatus == SAVED` 且 `localPath` 非空 —— 文件在不在由真正的打开动作判断
 * （这里不碰文件系统：界面层做 IO 会让 Robolectric 下的行为与真机不一致）。
 */
@Composable
internal fun AssetsBlock(
    assets: List<Asset>,
    modifier: Modifier = Modifier,
    onOpenAsset: (Asset) -> Unit = {},
    onShareAsset: (Asset) -> Unit = {},
    /**
     * 删除单个附件（2026-10-29）。
     *
     * `null` = 这个调用点不支持删除（合集来源里的附件属于成员记录，
     * 必须回原记录去删，不能在这里动别人的数据）。
     */
    onDeleteAsset: ((Asset) -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        assets.forEach { asset ->
            AssetRow(
                asset = asset,
                onOpen = { onOpenAsset(asset) },
                onShare = { onShareAsset(asset) },
                onDelete = onDeleteAsset?.let { delete -> { delete(asset) } },
            )
        }
    }
}

/** 附件类型（界面分类，与 MIME 不是一一对应：所有 audio/ 前缀都算录音）。 */
internal enum class AssetKind {
    IMAGE,
    PDF,
    AUDIO,
    VIDEO,
    TEXT,
    OTHER,
}

/**
 * MIME → 界面分类。判定顺序有意义：`application/pdf` 不以 text/ 开头，
 * 但个别 ROM 会给 PDF 报 `application/x-pdf`，所以用"以 pdf 结尾"而不是只等值判断。
 */
internal fun assetKindOf(mimeType: String): AssetKind {
    val mime = mimeType.lowercase()
    return when {
        mime.startsWith("image/") -> AssetKind.IMAGE
        mime == "application/pdf" || mime.endsWith("pdf") -> AssetKind.PDF
        mime.startsWith("audio/") -> AssetKind.AUDIO
        mime.startsWith("video/") -> AssetKind.VIDEO
        mime.startsWith("text/") -> AssetKind.TEXT
        else -> AssetKind.OTHER
    }
}

@Composable
private fun AssetRow(
    asset: Asset,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val kind = assetKindOf(asset.mimeType)
    val canOpen = asset.saveStatus == AssetSaveStatus.SAVED && asset.localPath.isNotBlank()
    val fileName = remember(asset.fileName, asset.localPath) { assetDisplayName(asset) }.orEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordDetailTestTags.ASSET_ITEM)
            // 用 clickable 而不是 Surface(onClick=)：失败态要保留同一行的视觉，只去掉点击，
            // 否则"保存失败"的附件会看起来像另一种东西
            .clickable(enabled = canOpen, onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssetThumbnail(asset = asset, kind = kind)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.detail_asset_meta,
                        stringResource(kind.labelRes()),
                        stringResource(
                            R.string.detail_asset_meta,
                            formatFileSize(asset.fileSize),
                            stringResource(asset.saveStatus.labelRes()),
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (fileName.isNotBlank()) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canOpen) {
                DetailIconAction(
                    iconRes = R.drawable.ic_action_open,
                    contentDescription = stringResource(R.string.detail_asset_open_cd),
                    onClick = onOpen,
                    modifier = Modifier.testTag(RecordDetailTestTags.assetOpenTag(asset.id)),
                )
            }
            // 分享与删除收进 ⋮（2026-10-29 R4）：破坏性动作不在主界面裸露；
            // 保存失败的附件打不开、分享不了，但**仍然可以删** —— 那正是用户想做的事
            if (canOpen || onDelete != null) {
                DetailOverflowMenu(
                    contentDescription = stringResource(R.string.detail_asset_more_cd),
                    testTag = RecordDetailTestTags.assetMoreTag(asset.id),
                ) { dismiss ->
                    if (canOpen) {
                        DetailMenuItem(
                            iconRes = R.drawable.ic_action_share,
                            label = stringResource(R.string.detail_asset_share),
                            onClick = {
                                dismiss()
                                onShare()
                            },
                            testTag = RecordDetailTestTags.assetShareTag(asset.id),
                        )
                    }
                    if (onDelete != null) {
                        DetailMenuItem(
                            iconRes = R.drawable.ic_action_delete,
                            label = stringResource(R.string.detail_asset_delete),
                            onClick = {
                                dismiss()
                                onDelete()
                            },
                            danger = true,
                            testTag = RecordDetailTestTags.assetDeleteTag(asset.id),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 附件展示名：原始文件名优先，回退到磁盘文件基名。
 *
 * 导入器拿不到原名时会落 `asset.m4a` 这种默认名（录音全是它），对用户没有信息量，
 * 宁可不显示 —— 否则"录音"下面又冒出一个看起来像文件类型的字样。
 * 删除确认框也用这个名字点名对象，用户才知道自己删的是哪一个。
 */
internal fun assetDisplayName(asset: Asset): String? {
    val original = asset.fileName?.trim()?.takeIf { it.isNotEmpty() }
    if (original != null) return original
    return File(asset.localPath).name
        .takeIf { it.isNotBlank() && !DEFAULT_FILE_NAME.matches(it) }
}

/** 图片给缩略图，其它类型给类型图标；缩略图解不出来（损坏/无权限）时回落图标。 */
@Composable
private fun AssetThumbnail(asset: Asset, kind: AssetKind) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            // 44dp（设计稿 §8.3）：与 10dp 上下内边距凑成 ≥ 64dp 的行高
            .size(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = if (kind == AssetKind.IMAGE && asset.localPath.isNotBlank()) {
            rememberThumbnail(asset.localPath)
        } else {
            null
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(RecordDetailTestTags.ASSET_THUMBNAIL),
            )
        } else {
            Icon(
                painter = painterResource(kind.iconRes()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 异步解码缩略图。
 *
 * 用 `BitmapFactory` 而不是引入图片库：详情页只需要"这张图长什么样"，
 * 多一个依赖（Coil）会连带 Ktor/OkHttp 与磁盘缓存策略，收益不值。
 * `inSampleSize` 固定降采样：44dp 的框不需要原图，1 亿像素的手机照片直接解码会 OOM。
 *
 * 失败一律回落 `null`（界面显示类型图标）：附件损坏不该让详情页崩。
 */
@Composable
private fun rememberThumbnail(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = THUMBNAIL_SAMPLE_SIZE }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

private fun AssetKind.iconRes(): Int = when (this) {
    AssetKind.IMAGE -> R.drawable.ic_type_image
    AssetKind.PDF -> R.drawable.ic_type_pdf
    AssetKind.AUDIO -> R.drawable.ic_type_voice
    AssetKind.VIDEO -> R.drawable.ic_type_video
    AssetKind.TEXT -> R.drawable.ic_type_text
    AssetKind.OTHER -> R.drawable.ic_type_file
}

private fun AssetKind.labelRes(): Int = when (this) {
    AssetKind.IMAGE -> R.string.detail_asset_type_image
    AssetKind.PDF -> R.string.detail_asset_type_pdf
    AssetKind.AUDIO -> R.string.detail_asset_type_audio
    AssetKind.VIDEO -> R.string.detail_asset_type_video
    AssetKind.TEXT -> R.string.detail_asset_type_text
    AssetKind.OTHER -> R.string.detail_asset_type_file
}

private fun AssetSaveStatus.labelRes(): Int = when (this) {
    AssetSaveStatus.SAVED -> R.string.detail_asset_saved
    AssetSaveStatus.FAILED -> R.string.detail_asset_failed
    AssetSaveStatus.MISSING -> R.string.detail_asset_missing
}

/** 缩略图降采样倍数：44dp 目标尺寸下 8 倍已足够清晰，内存占用降到 1/64。 */
private const val THUMBNAIL_SAMPLE_SIZE = 8

/** 导入器的默认文件名（`ExternalAssetImporterImpl.safeFileName` 在拿不到原名时用它）。 */
private val DEFAULT_FILE_NAME = Regex("""^asset([-_]\d+)?(\.[A-Za-z0-9]{1,8})?$""")
