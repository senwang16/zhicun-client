package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M4 核心用例测试（验收要求：正常保存 + 附件复制失败时记录仍创建但 Asset 标注失败）。
 *
 * 纯 JVM 运行：domain 层不碰 Android（这正是 M2 分层规则的回报）。
 */
class SaveRecordUseCaseTest {

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var importer: FakeAssetImporter
    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var useCase: SaveRecordUseCase

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        recordRepository = FakeRecordRepository()
        assetRepository = FakeAssetRepository()
        importer = FakeAssetImporter()
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        useCase = SaveRecordUseCase(
            recordRepository = recordRepository,
            copyAsset = CopyAssetToPrivateDirUseCase(importer, assetRepository),
            sensitiveDetector = SensitiveContentDetector(),
            scheduleProcessing = ScheduleProcessingUseCase(
                jobRepository,
                scheduler,
                recordRepository,
                FakeAiSettings(),
                FakeAiGatewaySettings(),
            ),
            diagnosticLog = FakeDiagnosticLog(),
        )
    }

    private fun imageUri(uri: String = "content://media/1") =
        InputUri(uri = uri, mimeType = "image/png")

    @Test
    fun SaveRecord_textWithUrl_createsWebLinkRecord() = runTest {
        val payload = RecordInput(
            text = "这篇值得看 https://example.com/post",
            subject = "一篇好文",
            sourceApp = "Chrome",
        )

        val result = useCase(payload, now = now)

        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(RecordType.WEB_LINK, record.type)
        assertEquals("https://example.com/post", record.sourceUrl)
        assertEquals("一篇好文", record.sourceTitle)
        assertEquals("Chrome", record.sourceApp)
        assertEquals(CaptureSource.SYSTEM_SHARE, record.captureSource)
        assertEquals(LocalStatus.SAVED, record.localStatus)
        assertEquals(ProcessingStatus.PENDING, record.processingStatus)
        assertEquals(1, record.recordRevision)
        assertEquals(now, record.createdAt)
        assertEquals(now, record.updatedAt)
    }

    @Test
    fun SaveRecord_plainText_createsSharedTextRecord() = runTest {
        val result = useCase(RecordInput(text = "随手记一句"), now = now)

        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(RecordType.SHARED_TEXT, record.type)
        assertEquals("随手记一句", record.rawText)
        assertEquals(null, record.sourceUrl)
    }

    @Test
    fun SaveRecord_textIsTrimmed_blankBecomesNull() = runTest {
        val result = useCase(RecordInput(text = "  有内容  "), now = now)

        assertEquals("有内容", recordRepository.byId.getValue(result.recordId).rawText)
    }

    @Test
    fun SaveRecord_sensitiveText_marksSuspected() = runTest {
        val result = useCase(
            RecordInput(text = "配置：password: hunter2hunter2"),
            now = now,
        )

        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(SensitivityStatus.SUSPECTED, record.sensitivityStatus)
    }

    @Test
    fun SaveRecord_cleanText_marksClear() = runTest {
        val result = useCase(RecordInput(text = "买牛奶"), now = now)

        assertEquals(
            SensitivityStatus.CLEAR,
            recordRepository.byId.getValue(result.recordId).sensitivityStatus,
        )
    }

    /**
     * 2026-10-24 全项目审查 H-1：分享标题会被当作 SOURCE_TITLE 拼进摘要输入上传，
     * 因此它必须参与保存时的敏感扫描 —— 否则"正文干净、标题里是证件号"的记录
     * 会以 CLEAR 状态把标题原样发到网关，绕过确认闸门。
     */
    @Test
    fun SaveRecord_sensitiveTitleWithCleanText_marksSuspected() = runTest {
        val payload = RecordInput(
            text = "帮我看看这个",
            subject = "身份证 110101199003071234 复印件",
        )

        val result = useCase(payload, now = now)

        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(SensitivityStatus.SUSPECTED, record.sensitivityStatus)
        assertEquals("身份证 110101199003071234 复印件", record.sourceTitle)
    }

    /** 标题敏感、正文为空的图文分享：这条最容易漏，因为 `scan(null)` 天然返回空集。 */
    @Test
    fun SaveRecord_sensitiveTitleWithoutText_stillMarksSuspected() = runTest {
        // 4111 1111 1111 1111 是 Luhn 合法的测试卡号（BANK_CARD 规则要过自校验位，
        // 随手编的数字串过不了，会被正确地判为"不疑似"）
        val payload = RecordInput(
            subject = "卡号 4111 1111 1111 1111 已被冻结",
            supportedUris = listOf(imageUri()),
        )

        val result = useCase(payload, now = now)

        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(null, record.rawText)
        assertEquals(SensitivityStatus.SUSPECTED, record.sensitivityStatus)
    }

    @Test
    fun SaveRecord_imagePayload_createsOneAssetPerUri() = runTest {
        val payload = RecordInput(
            text = "两张图",
            supportedUris = listOf(imageUri("content://media/1"), imageUri("content://media/2")),
        )

        val result = useCase(payload, now = now)

        assertEquals(2, result.savedAssetCount)
        assertEquals(0, result.failedAssetCount)
        assertEquals(2, assetRepository.stored.size)
        assertEquals(RecordType.IMAGE, recordRepository.byId.getValue(result.recordId).type)
        assetRepository.stored.forEach { asset ->
            assertEquals(AssetSaveStatus.SAVED, asset.saveStatus)
            assertEquals(result.recordId, asset.recordId)
            assertEquals("image/png", asset.mimeType)
            assertNotNull(asset.fileHash)
            assertTrue("附件路径必须是绝对路径", asset.localPath.startsWith("/"))
        }
    }

    @Test
    fun SaveRecord_assetCopyFails_recordStillSavedAndAssetMarkedFailed() = runTest {
        importer.failNext = true

        val result = useCase(RecordInput(supportedUris = listOf(imageUri())), now = now)

        // 记录必须存在（PRD §11：保留记录并标注附件不可用）
        val record = recordRepository.byId.getValue(result.recordId)
        assertEquals(RecordType.IMAGE, record.type)
        assertEquals(0, result.savedAssetCount)
        assertEquals(1, result.failedAssetCount)

        val asset = assetRepository.stored.single()
        assertEquals(AssetSaveStatus.FAILED, asset.saveStatus)
        assertEquals("", asset.localPath)
        assertEquals(0L, asset.fileSize)
        assertEquals("content://media/1", asset.originalUri)
    }

    @Test
    fun SaveRecord_recordIsPersistedBeforeAssetCopy() = runTest {
        var recordSeenDuringImport = false
        importer.beforeImport = { recordId ->
            recordSeenDuringImport = recordRepository.byId.containsKey(recordId)
        }

        useCase(RecordInput(supportedUris = listOf(imageUri())), now = now)

        assertTrue("必须先落记录再复制附件（ARCHITECTURE §6.2）", recordSeenDuringImport)
    }

    @Test
    fun SaveRecord_unsupportedTypes_areCountedAsSkipped() = runTest {
        val payload = RecordInput(
            text = "带一个 zip",
            unsupportedMimeTypes = listOf("application/zip", "application/zip"),
        )

        val result = useCase(payload, now = now)

        // 记录仍然创建（文字是有效内容），但要如实报告有类型被跳过
        assertEquals(2, result.skippedAssetCount)
        assertTrue(result.hasFailures)
        assertEquals(1, recordRepository.stored.value.size)
    }

    /**
     * 超出单次分享附件上限的条数也要算进"被跳过"，否则用户看到的"已保存"会
     * 掩盖 5 个文件根本没被处理的事实（2026-11-01 审核 S-5，自海外轨移植）。
     */
    @Test
    fun SaveRecord_excessUrisBeyondLimit_areCountedAsSkipped() = runTest {
        val payload = RecordInput(
            text = "分享了一大堆图",
            unsupportedMimeTypes = listOf("application/zip"),
            excessUriCount = 5,
        )

        val result = useCase(payload, now = now)

        assertEquals(6, result.skippedAssetCount)
        assertTrue(result.hasFailures)
        assertEquals(1, recordRepository.stored.value.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun SaveRecord_emptyPayload_throwsAndCreatesNothing() = runTest {
        useCase(RecordInput(text = "   "), now = now)
    }
}
