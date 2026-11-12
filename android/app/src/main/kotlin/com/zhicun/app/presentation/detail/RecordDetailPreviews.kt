package com.zhicun.app.presentation.detail

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.presentation.theme.AppTheme

/** 详情页的 @Preview（从 RecordDetailScreen 拆出，与页面逻辑分开放，避免预览代码挤占阅读路径）。 */

// 预览样例里的 localPath 是"看起来像真实私有目录"的假值，只为让预览接近线上形态。
// 它不参与任何运行时逻辑，也不是硬编码存储路径 —— lint 的 SdCardPath 在此不适用。
@SuppressLint("SdCardPath")
@Preview(showBackground = true, name = "详情 · 正常")
@Composable
private fun RecordDetailPreview() {
    AppTheme {
        RecordDetailContent(
            uiState = RecordDetailUiState.Content(
                record = Record(
                    id = "1",
                    type = RecordType.WEB_LINK,
                    captureSource = CaptureSource.SYSTEM_SHARE,
                    rawText = "Jetpack Compose 是 Android 的现代声明式 UI 工具包。\n用更少的代码、更直观的 API 构建原生界面。",
                    sourceUrl = "https://developer.android.com/jetpack/compose",
                    sourceTitle = "Compose 官方文档",
                    sourceApp = "Chrome",
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_300_000L,
                    localStatus = LocalStatus.SAVED,
                    processingStatus = ProcessingStatus.DONE,
                    sensitivityStatus = SensitivityStatus.CLEAR,
                    recordRevision = 2,
                    isDeleted = false,
                ),
                assets = listOf(
                    Asset(
                        id = "a1",
                        recordId = "1",
                        mimeType = "image/png",
                        localPath = "/data/user/0/com.zhicun.app/files/assets/1/cover.png",
                        fileSize = 348_912,
                        fileHash = null,
                        originalUri = null,
                        saveStatus = AssetSaveStatus.SAVED,
                    ),
                ),
            ),
            onBack = {},
        )
    }
}
