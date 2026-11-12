package com.zhicun.app.presentation.inbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.presentation.theme.AppTheme


/* 收件箱的 @Preview（与页面逻辑分开放，避免预览代码挤占阅读路径）。 */

@Preview(showBackground = true, name = "收件箱 · 空状态")
@Composable
private fun InboxEmptyPreview() {
    AppTheme {
        InboxContent(uiState = InboxUiState.Empty, onRecordClick = {}, onCreateClick = {})
    }
}

@Preview(showBackground = true, name = "收件箱 · 选择模式")
@Composable
private fun InboxSelectionPreview() {
    AppTheme {
        InboxContent(
            uiState = InboxUiState.Content(
                records = previewRecords(),
                filter = InboxFilter.FAVORITE,
                inboxTotal = 6,
            ),
            onRecordClick = {},
            onCreateClick = {},
            selection = InboxSelection(active = true, selectedIds = setOf("1")),
        )
    }
}

@Preview(showBackground = true, name = "收件箱 · 有数据")
@Composable
private fun InboxContentPreview() {
    AppTheme {
        InboxContent(
            uiState = InboxUiState.Content(records = previewRecords()),
            onRecordClick = {},
            onCreateClick = {},
        )
    }
}

private fun previewRecords(): List<Record> = listOf(
    Record(
        id = "1",
        type = RecordType.WEB_LINK,
        captureSource = CaptureSource.SYSTEM_SHARE,
        rawText = "Jetpack Compose 是 Android 的现代声明式 UI 工具包。",
        sourceUrl = "https://developer.android.com/jetpack/compose",
        sourceTitle = "Compose 官方文档",
        sourceApp = "Chrome",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.DONE,
        sensitivityStatus = SensitivityStatus.CLEAR,
        recordRevision = 1,
        isDeleted = false,
        isPinned = true,
        isFavorite = true,
    ),
    Record(
        id = "2",
        type = RecordType.IMAGE,
        captureSource = CaptureSource.GALLERY,
        rawText = null,
        sourceUrl = null,
        sourceTitle = null,
        sourceApp = null,
        createdAt = 1_700_000_060_000L,
        updatedAt = 1_700_000_060_000L,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.PENDING,
        sensitivityStatus = SensitivityStatus.UNKNOWN,
        recordRevision = 1,
        isDeleted = false,
    ),
)
