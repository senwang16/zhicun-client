package com.zhicun.app.fake

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus

/** 测试数据工厂：集中一处，避免每个用例里重复写 14 个字段。 */
fun testRecord(
    id: String = "record-1",
    type: RecordType = RecordType.TEXT_NOTE,
    captureSource: CaptureSource = CaptureSource.MANUAL,
    rawText: String? = "第一行标题\n第二行正文",
    sourceUrl: String? = null,
    sourceTitle: String? = null,
    sourceApp: String? = null,
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L,
    localStatus: LocalStatus = LocalStatus.SAVED,
    processingStatus: ProcessingStatus = ProcessingStatus.PENDING,
    sensitivityStatus: SensitivityStatus = SensitivityStatus.UNKNOWN,
    recordRevision: Int = 1,
    cloudConfirmedRevision: Int? = null,
    cloudConfirmedScope: Set<ContentPart> = emptySet(),
    webFetchConfirmedRevision: Int? = null,
    isDeleted: Boolean = false,
    isFavorite: Boolean = false,
    isPinned: Boolean = false,
    archivedAt: Long? = null,
): Record = Record(
    id = id,
    type = type,
    captureSource = captureSource,
    rawText = rawText,
    sourceUrl = sourceUrl,
    sourceTitle = sourceTitle,
    sourceApp = sourceApp,
    createdAt = createdAt,
    updatedAt = updatedAt,
    localStatus = localStatus,
    processingStatus = processingStatus,
    sensitivityStatus = sensitivityStatus,
    recordRevision = recordRevision,
    cloudConfirmedRevision = cloudConfirmedRevision,
    cloudConfirmedScope = cloudConfirmedScope,
    webFetchConfirmedRevision = webFetchConfirmedRevision,
    isDeleted = isDeleted,
    isFavorite = isFavorite,
    isPinned = isPinned,
    archivedAt = archivedAt,
)

fun testAsset(
    id: String = "asset-1",
    recordId: String = "record-1",
    mimeType: String = "image/png",
    fileSize: Long = 2048L,
    saveStatus: AssetSaveStatus = AssetSaveStatus.SAVED,
    fileName: String? = null,
    orderIndex: Int = 0,
): Asset = Asset(
    id = id,
    recordId = recordId,
    mimeType = mimeType,
    localPath = "/data/user/0/com.zhicun.app/files/assets/$recordId/$id",
    fileSize = fileSize,
    fileHash = null,
    originalUri = null,
    saveStatus = saveStatus,
    fileName = fileName,
    orderIndex = orderIndex,
)

fun testDerived(
    id: String = "derived-1",
    recordId: String = "record-1",
    contentType: DerivedType = DerivedType.OCR_TEXT,
    content: String? = "识别出的文字",
    errorMessage: String? = null,
    assetId: String? = null,
): DerivedContent = DerivedContent(
    id = id,
    recordId = recordId,
    contentType = contentType,
    content = content,
    processorVersion = "test/1.0",
    processedAt = 2_000L,
    errorMessage = errorMessage,
    assetId = assetId,
)
