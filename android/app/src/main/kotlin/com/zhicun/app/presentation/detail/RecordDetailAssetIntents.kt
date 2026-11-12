package com.zhicun.app.presentation.detail

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import java.io.File

/**
 * 用系统应用打开附件（2026-10-24）。
 *
 * 私有目录的 `file://` 从 API 24 起不能跨应用传递，所以必须经 FileProvider 换
 * `content://` 并只授予**本次**读权限（`FLAG_GRANT_READ_URI_PERMISSION`）。
 * 可见范围由 `res/xml/file_paths.xml` 限定为 `files/assets/` 一个子目录。
 *
 * @return 是否已成功发起打开。`false` 的两种原因（文件不在 / 没有应用能处理）
 *   由调用方区分文案 —— 用户需要知道是"装个应用"还是"附件没了"。
 */
private const val TAG = "RecordDetail"

internal fun openAssetWithSystemViewer(context: Context, asset: Asset): Boolean {
    if (asset.localPath.isBlank()) return false
    val file = File(asset.localPath)
    if (!file.isFile) return false
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, asset.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // ActivityNotFoundException（没有阅读器）/ IllegalArgumentException（路径不在
        // file_paths 声明范围内）/ SecurityException 都归为"打不开"，由界面给提示。
        // 只记异常类别、不记 message：message 可能带文件路径（需要脱敏），
        // 类别已足够区分"没有应用可打开"和"file_paths 覆盖范围不符"。
        Log.w(TAG, "系统查看器打开失败：${e.javaClass.simpleName}")
        false
    }
}

/**
 * 分享附件（2026-10-28）。
 *
 * 不经过系统查看器：部分 ROM 的相册打开图片后只提供编辑、不提供分享，
 * 直接 `ACTION_SEND` 才能稳定把原文件交给微信/QQ 等接收方。
 * 与打开一样，私有目录文件必须经 FileProvider 换成 `content://` 并授予本次读权限。
 */
internal fun shareAssetWithSystemChooser(context: Context, asset: Asset): Boolean {
    if (asset.localPath.isBlank()) return false
    val file = File(asset.localPath)
    if (!file.isFile) return false
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(asset.mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.detail_asset_share_chooser))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    } catch (e: Exception) {
        // 与打开相同的脱敏口径：只记异常类别，不记可能带路径的 message
        Log.w(TAG, "分享附件失败：${e.javaClass.simpleName}")
        false
    }
}
