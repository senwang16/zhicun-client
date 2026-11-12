package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.clipboard.ClipboardReadResult
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeClipboardReader
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 剪贴板收录（M13）。
 *
 * 三件事必须钉住：
 * 1. **空 / 非文本 / 读不到都不是异常** —— 各自返回独立结果，界面据此给出不同提示；
 * 2. 收录复用 [SaveRecordUseCase]，因此类型推断、敏感检测、后台调度全都照旧生效；
 * 3. 保存失败要原样抛出，让中转页能区分"没内容"与"存不下"。
 */
class CaptureClipboardUseCaseTest {

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var clipboard: FakeClipboardReader
    private lateinit var useCase: CaptureClipboardUseCase

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        recordRepository = FakeRecordRepository()
        scheduler = FakeProcessingScheduler()
        clipboard = FakeClipboardReader()
        useCase = CaptureClipboardUseCase(
            clipboardReader = clipboard,
            recordRepository = recordRepository,
            saveRecord = SaveRecordUseCase(
                recordRepository = recordRepository,
                copyAsset = CopyAssetToPrivateDirUseCase(FakeAssetImporter(), FakeAssetRepository()),
                sensitiveDetector = SensitiveContentDetector(),
                scheduleProcessing = ScheduleProcessingUseCase(
                    FakeProcessingJobRepository(),
                    scheduler,
                    recordRepository,
                    FakeAiSettings(),
                    FakeAiGatewaySettings(),
                ),
                diagnosticLog = FakeDiagnosticLog(),
            ),
        )
    }

    @Test
    fun Invoke_emptyClipboard_returnsEmptyWithoutSaving() = runTest {
        clipboard.text = null

        val result = useCase(now = now)

        assertEquals(ClipboardCaptureResult.Empty, result)
        assertTrue("空剪贴板不该落库", recordRepository.byId.isEmpty())
        assertTrue("空剪贴板不该排后台任务", scheduler.enqueued.isEmpty())
    }

    @Test
    fun Invoke_blankClipboard_returnsEmpty() = runTest {
        clipboard.text = "   \n\t "

        val result = useCase(now = now)

        assertEquals(ClipboardCaptureResult.Empty, result)
        assertTrue(recordRepository.byId.isEmpty())
    }

    /** 图片/文件等非文本剪贴板必须与"空"区分开（PRD v1.2 §2.2）。 */
    @Test
    fun Invoke_unsupportedClipboard_returnsUnsupported() = runTest {
        clipboard.result = ClipboardReadResult.Unsupported

        val result = useCase(now = now)

        assertEquals(ClipboardCaptureResult.Unsupported, result)
        assertTrue(recordRepository.byId.isEmpty())
    }

    /** 系统限制/读取异常不能被当成"剪贴板为空"（PRD v1.2 §2.2）。 */
    @Test
    fun Invoke_unavailableClipboard_returnsUnavailable() = runTest {
        clipboard.result = ClipboardReadResult.Unavailable

        val result = useCase(now = now)

        assertEquals(ClipboardCaptureResult.Unavailable, result)
        assertTrue(recordRepository.byId.isEmpty())
    }

    @Test
    fun Invoke_plainText_savesRecordWithClipboardSource() = runTest {
        clipboard.text = "  随手记一句  "

        val result = useCase(now = now)

        val saved = result as ClipboardCaptureResult.Saved
        val record = recordRepository.byId.getValue(saved.recordId)
        // 首尾空白已去掉，内容原样保留
        assertEquals("随手记一句", record.rawText)
        // 剪贴板内容属于"从别处收进来的文字"，按分类器规则落在 SHARED_TEXT（不是 TEXT_NOTE）
        assertEquals(RecordType.SHARED_TEXT, record.type)
        assertEquals(CaptureSource.CLIPBOARD, record.captureSource)
        assertEquals(LocalStatus.SAVED, record.localStatus)
        assertEquals(now, record.createdAt)
    }

    @Test
    fun Invoke_previewIsTrimmedText() = runTest {
        clipboard.text = "  只有一行内容  "

        val result = useCase(now = now) as ClipboardCaptureResult.Saved

        assertEquals("只有一行内容", result.preview)
    }

    @Test
    fun Invoke_longText_previewIsTruncated() = runTest {
        clipboard.text = "字".repeat(200)

        val result = useCase(now = now) as ClipboardCaptureResult.Saved

        assertEquals(41, result.preview.length) // 40 + 省略号
        // 完整内容仍然全部入库，截断只发生在预览
        assertEquals(200, recordRepository.byId.getValue(result.recordId).rawText?.length)
    }

    @Test
    fun Invoke_textWithUrl_createsWebLinkRecord() = runTest {
        clipboard.text = "https://example.com/post 值得一看"

        val result = useCase(now = now) as ClipboardCaptureResult.Saved
        val record = recordRepository.byId.getValue(result.recordId)

        assertEquals(RecordType.WEB_LINK, record.type)
        assertEquals("https://example.com/post", record.sourceUrl)
        // 原文必须保留 URL 之后的说明文字，不能被"提取链接"吞掉
        assertTrue(record.rawText.orEmpty().endsWith("值得一看"))
    }

    /** 收录链路必须和分享/手写走同一套后台处理，否则剪贴板记录永远没有摘要。 */
    @Test
    fun Invoke_savedRecord_enqueuesProcessing() = runTest {
        clipboard.text = "一条普通文字"

        useCase(now = now)

        assertTrue("保存后必须排后台任务", scheduler.enqueued.isNotEmpty())
    }

    @Test
    fun Invoke_repositoryFails_propagatesException() = runTest {
        clipboard.text = "存不下的一条"
        recordRepository.saveFailure = IllegalStateException("磁盘已满")

        val failure = runCatching { useCase(now = now) }.exceptionOrNull()

        assertTrue(
            "保存失败必须抛给调用方（界面要区分'没内容'与'存不下'），实际：$failure",
            failure is IllegalStateException,
        )
    }

    // ------------------------------------------------------------ 去重（2026-10-24）

    /**
     * 用户的核心抱怨：剪贴板内容没变，连点三次就得到三条一样的记录。
     * 第二次起必须是 Duplicate，且不能落库、不能再排后台任务。
     */
    @Test
    fun Invoke_sameTextTwice_secondIsDuplicateAndNotSaved() = runTest {
        clipboard.text = "同一段内容"

        val first = useCase(now = now) as ClipboardCaptureResult.Saved
        // 一次保存会排多个任务（摘要 + 向量），因此比"增量"而不是比"绝对条数"
        val enqueuedAfterFirst = scheduler.enqueued.size
        val second = useCase(now = now + 1_000)

        val duplicate = second as ClipboardCaptureResult.Duplicate
        assertEquals("去重命中要指回已有那条记录", first.recordId, duplicate.recordId)
        assertEquals("同一段内容", duplicate.preview)
        assertEquals("库里只应有 1 条", 1, recordRepository.byId.size)
        assertEquals("重复收录不该再排后台任务", enqueuedAfterFirst, scheduler.enqueued.size)
    }

    @Test
    fun Invoke_sameTextThreeTimes_savesOnlyOnce() = runTest {
        clipboard.text = "连点三次"

        useCase(now = now)
        useCase(now = now + 1)
        useCase(now = now + 2)

        assertEquals(1, recordRepository.byId.size)
    }

    /** 判据是"完全不同"而不是前缀相同：改一个字就该是新记录。 */
    @Test
    fun Invoke_textDiffersByOneChar_savesNewRecord() = runTest {
        clipboard.text = "会议纪要 A"
        useCase(now = now)

        clipboard.text = "会议纪要 B"
        val result = useCase(now = now + 1_000)

        assertTrue("内容不同必须新建", result is ClipboardCaptureResult.Saved)
        assertEquals(2, recordRepository.byId.size)
    }

    /**
     * 去重跨来源（2026-10-27，PRD v1.2 §2.4）：手动/分享存过的同一段文字，
     * 再从剪贴板收一次同样是重复，不能出现第二条一样的记录。
     */
    @Test
    fun Invoke_sameTextFromOtherSource_isDuplicate() = runTest {
        recordRepository.save(
            record(
                id = "manual-1",
                rawText = "同一段内容",
                captureSource = CaptureSource.MANUAL,
            ),
        )

        clipboard.text = "同一段内容"
        val result = useCase(now = now)

        val duplicate = result as ClipboardCaptureResult.Duplicate
        assertEquals("manual-1", duplicate.recordId)
        assertEquals("跨来源命中也只能有 1 条", 1, recordRepository.byId.size)
    }

    /** 只统一换行符：CRLF 与 LF 视为同一段内容（PRD v1.2 §2.4 建议的有限标准化）。 */
    @Test
    fun Invoke_sameTextWithDifferentLineEndings_isDuplicate() = runTest {
        recordRepository.save(
            record(
                id = "old-1",
                rawText = "第一行\n第二行",
                captureSource = CaptureSource.MANUAL,
            ),
        )

        clipboard.text = "第一行\r\n第二行"

        assertTrue(useCase(now = now) is ClipboardCaptureResult.Duplicate)
        assertEquals(1, recordRepository.byId.size)
    }

    /** 不能为了去重删 URL 参数：参数不同就是不同内容。 */
    @Test
    fun Invoke_urlDiffersByParams_savesNewRecord() = runTest {
        recordRepository.save(
            record(
                id = "link-1",
                rawText = "https://example.com/item?id=1",
                captureSource = CaptureSource.CLIPBOARD,
            ),
        )

        clipboard.text = "https://example.com/item?id=2"
        val result = useCase(now = now)

        assertTrue("URL 参数不同必须算两条内容", result is ClipboardCaptureResult.Saved)
        assertEquals(2, recordRepository.byId.size)
    }

    /** 也不能压缩内部空格：`A B` 与 `A  B` 是不同内容。 */
    @Test
    fun Invoke_textDiffersByInternalSpaces_savesNewRecord() = runTest {
        recordRepository.save(
            record(
                id = "space-1",
                rawText = "会议 纪要",
                captureSource = CaptureSource.CLIPBOARD,
            ),
        )

        clipboard.text = "会议  纪要"
        val result = useCase(now = now)

        assertTrue("内部空格不同不应被判成重复", result is ClipboardCaptureResult.Saved)
        assertEquals(2, recordRepository.byId.size)
    }

    /** 删掉再复制一次应当能重新收藏：已删除的记录不算"已存在"。 */
    @Test
    fun Invoke_recordDeleted_savesAgain() = runTest {
        clipboard.text = "删掉再存"
        val first = useCase(now = now) as ClipboardCaptureResult.Saved
        recordRepository.softDelete(first.recordId, now + 1)

        val second = useCase(now = now + 2)

        assertTrue("已删除的记录不该参与去重", second is ClipboardCaptureResult.Saved)
    }

    private fun record(
        id: String,
        rawText: String,
        captureSource: CaptureSource,
        isDeleted: Boolean = false,
    ): Record = Record(
        id = id,
        type = RecordType.SHARED_TEXT,
        captureSource = captureSource,
        rawText = rawText,
        sourceUrl = null,
        sourceTitle = null,
        sourceApp = null,
        createdAt = now - 10_000,
        updatedAt = now - 10_000,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.DONE,
        sensitivityStatus = SensitivityStatus.CLEAR,
        recordRevision = 1,
        isDeleted = isDeleted,
    )
}
