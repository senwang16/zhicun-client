package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.aggregateJobsFrom
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 删除单个附件（2026-10-29）。
 *
 * 验证的核心是**顺序与收尾**：文件先删、行后删；派生结果按附件清理；
 * 摘要/向量失效并重排；最后一项内容不删；失败时一行都不动。
 */
class DeleteAssetUseCaseTest {

    private val recordId = "r1"
    private val image1 = testAsset(id = "a1", recordId = recordId, mimeType = "image/png")
    private val image2 = testAsset(id = "a2", recordId = recordId, mimeType = "image/png")

    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var collectionRepository: FakeCollectionRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var fileStore: FakeLocalFileStore
    private lateinit var diagnosticLog: FakeDiagnosticLog
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var useCase: DeleteAssetUseCase

    @Before
    fun setUp() {
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        fileStore = FakeLocalFileStore(setOf(image1.localPath, image2.localPath))
        diagnosticLog = FakeDiagnosticLog()
        aiSettings = FakeAiSettings()
        recordRepository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = recordId,
                    type = RecordType.IMAGE,
                    rawText = null,
                    processingStatus = ProcessingStatus.DONE,
                ),
            ),
        ).aggregateJobsFrom(jobRepository)
        assetRepository = FakeAssetRepository(listOf(image1, image2))
        derivedRepository = FakeDerivedContentRepository()
        tagRepository = FakeTagRepository(
            listOf(
                RecordTag(recordId, Tag("t-ai", "AI标签", TagSource.AI, 0.9, 1L)),
                RecordTag(recordId, Tag("t-user", "用户标签", TagSource.USER, null, 2L)),
            ),
        )
        collectionRepository = FakeCollectionRepository()

        useCase = DeleteAssetUseCase(
            recordRepository = recordRepository,
            assetRepository = assetRepository,
            derivedContentRepository = derivedRepository,
            collectionRepository = collectionRepository,
            tagRepository = tagRepository,
            retryProcessing = RetryProcessingUseCase(
                recordRepository = recordRepository,
                jobRepository = jobRepository,
                scheduler = scheduler,
                aiSettings = aiSettings,
                gatewaySettings = FakeAiGatewaySettings(),
            ),
            fileStore = fileStore,
            diagnosticLog = diagnosticLog,
        )
    }

    @Test
    fun Invoke_deletesFileRowAssetDerivedAndRequeues() = runTest {
        seedDerived()

        val result = useCase(recordId, "a1")

        assertEquals(DeleteAssetResult.Deleted, result)
        assertTrue("文件必须先删掉", image1.localPath in fileStore.deleted)
        assertEquals(listOf("a2"), assetRepository.stored.map { it.id })
        assertEquals("被删附件的 OCR 必须消失", listOf("a2"), ocrAssetIds())
        assertTrue(
            "摘要/标签/向量/标题都被内容变化推翻",
            derivedRepository.stored.none {
                it.contentType in setOf(
                    DerivedType.SUMMARY,
                    DerivedType.TAGS,
                    DerivedType.EMBEDDING,
                    DerivedType.TITLE,
                )
            },
        )
        assertEquals(
            "只摘 AI 标签，用户标签保留",
            listOf("t-user"),
            tagRepository.associations.value.map { it.tag.id },
        )
        assertEquals(
            listOf(recordId to JobType.SUMMARIZE, recordId to JobType.EMBED),
            scheduler.enqueued,
        )
        assertTrue("成员内容变了，合集要刷新", recordId in collectionRepository.refreshed)
        assertEquals(
            ProcessingStatus.PENDING,
            recordRepository.byId[recordId]!!.processingStatus,
        )
    }

    @Test
    fun Invoke_lastAttachmentWithoutText_refusesAndTouchesNothing() = runTest {
        assetRepository.stored.removeAt(1)
        seedDerived()

        val result = useCase(recordId, "a1")

        assertEquals(DeleteAssetResult.LastContent, result)
        assertTrue("没有动文件", fileStore.deleted.isEmpty())
        assertEquals(1, assetRepository.stored.size)
        assertTrue("没有动派生内容", derivedRepository.stored.any { it.contentType == DerivedType.SUMMARY })
        assertTrue("没有排任务", scheduler.enqueued.isEmpty())
    }

    @Test
    fun Invoke_fileDeleteFails_keepsRowsAndDerived() = runTest {
        fileStore.undeletable += image1.localPath
        seedDerived()

        val result = useCase(recordId, "a1")

        assertEquals(DeleteAssetResult.FileDeleteFailed, result)
        assertEquals("附件行必须留着，它是下次重试的唯一依据", 2, assetRepository.stored.size)
        assertEquals(listOf("a1", "a2"), ocrAssetIds())
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(diagnosticLog.entries.isNotEmpty())
    }

    @Test
    fun Invoke_lastImageWithText_recomputesTypeToTextNote() = runTest {
        assetRepository.stored.removeAt(1)
        recordRepository.update(recordRepository.byId[recordId]!!.copy(rawText = "图片的说明"))

        val result = useCase(recordId, "a1")

        assertEquals(DeleteAssetResult.Deleted, result)
        assertEquals(
            "只剩正文时记录应变成文本记录",
            RecordType.TEXT_NOTE,
            recordRepository.byId[recordId]!!.type,
        )
    }

    @Test
    fun Invoke_requeueBlockedByCloud_marksLocalOnlyAndLogs() = runTest {
        aiSettings.cloudEnabled = false

        val result = useCase(recordId, "a1")

        assertEquals(DeleteAssetResult.Deleted, result)
        assertEquals(
            "旧摘要已删但排不进去，不能说记录仍是完成的",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId[recordId]!!.processingStatus,
        )
        assertTrue(
            diagnosticLog.entries.any { it.message.contains("未能排队") },
        )
    }

    @Test
    fun Invoke_unknownAsset_returnsNotFound() = runTest {
        assertEquals(DeleteAssetResult.NotFound, useCase(recordId, "missing"))
        assertFalse(fileStore.deleted.isNotEmpty())
    }

    private fun seedDerived() {
        derivedRepository.stored += testDerived(
            id = "$recordId:OCR_TEXT:a1",
            recordId = recordId,
            contentType = DerivedType.OCR_TEXT,
            content = "第一张",
            assetId = "a1",
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:OCR_TEXT:a2",
            recordId = recordId,
            contentType = DerivedType.OCR_TEXT,
            content = "第二张",
            assetId = "a2",
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:SUMMARY",
            recordId = recordId,
            contentType = DerivedType.SUMMARY,
            content = "旧摘要",
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:TAGS",
            recordId = recordId,
            contentType = DerivedType.TAGS,
            content = "[\"旧标签\"]",
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:EMBEDDING",
            recordId = recordId,
            contentType = DerivedType.EMBEDDING,
            content = "[0.1,0.2]",
        )
        derivedRepository.stored += testDerived(
            id = "$recordId:TITLE",
            recordId = recordId,
            contentType = DerivedType.TITLE,
            content = "旧标题",
        )
    }

    private fun ocrAssetIds(): List<String> =
        derivedRepository.stored
            .filter { it.contentType == DerivedType.OCR_TEXT }
            .mapNotNull { it.assetId }
}
