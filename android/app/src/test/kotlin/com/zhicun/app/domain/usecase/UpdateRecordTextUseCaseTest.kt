package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.repository.RecordTag
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 原文编辑用例（2026-10-26）。
 *
 * 用户选定的方案是"保存后自动重新生成摘要/标签/向量"。这个文件钉住三件事：
 * 1. 只重算被原文推翻的产物（摘要/标签/向量/非网页标题），OCR/转写/网页正文不能动；
 * 2. 换内容 = 换版本号，旧确认必须一起失效；
 * 3. 云端被挡时状态要说真话（LOCAL_ONLY），不能让界面永远停在"正在生成"。
 */
class UpdateRecordTextUseCaseTest {

    private val recordId = "rec-1"

    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var diagnosticLog: FakeDiagnosticLog
    private val collectionRepository = FakeCollectionRepository()
    private lateinit var useCase: UpdateRecordTextUseCase

    @Before
    fun setUp() {
        recordRepository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = recordId,
                    rawText = "旧原文",
                    processingStatus = ProcessingStatus.DONE,
                ),
            ),
        )
        derivedRepository = FakeDerivedContentRepository()
        tagRepository = FakeTagRepository()
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
        aiSettings = FakeAiSettings()
        diagnosticLog = FakeDiagnosticLog()

        val retry = RetryProcessingUseCase(
            recordRepository,
            jobRepository,
            scheduler,
            aiSettings,
            FakeAiGatewaySettings(),
        )
        useCase = UpdateRecordTextUseCase(
            recordRepository = recordRepository,
            derivedContentRepository = derivedRepository,
            collectionRepository = collectionRepository,
            tagRepository = tagRepository,
            sensitiveDetector = SensitiveContentDetector(),
            retryProcessing = retry,
            diagnosticLog = diagnosticLog,
        )
    }

    private fun seedDerived(vararg types: DerivedType) {
        types.forEach { type ->
            derivedRepository.stored += testDerived(
                id = "$recordId:$type",
                recordId = recordId,
                contentType = type,
                content = "旧结果",
            )
        }
    }

    private fun seedTags() {
        tagRepository.associations.value = listOf(
            RecordTag(recordId, Tag("ai-1", "会议", TagSource.AI, null, 1L)),
            RecordTag(recordId, Tag("user-1", "待办", TagSource.USER, null, 2L)),
        )
    }

    @Test
    fun UpdateText_savesTrimmedText_andRerunsOnlySummaryAndEmbedding() = runTest {
        seedDerived(
            DerivedType.SUMMARY,
            DerivedType.TAGS,
            DerivedType.EMBEDDING,
            DerivedType.TITLE,
            DerivedType.OCR_TEXT,
        )
        seedTags()

        val result = useCase(recordId, "  新原文  ", now = 2_000L)

        assertEquals(UpdateRecordTextResult.Updated, result)
        val saved = recordRepository.byId.getValue(recordId)
        assertEquals("新原文", saved.rawText)
        assertEquals("内容换了，版本必须推进", 2, saved.recordRevision)
        assertEquals(2_000L, saved.updatedAt)
        assertEquals(ProcessingStatus.PENDING, saved.processingStatus)

        val left = derivedRepository.stored.map { it.contentType }.toSet()
        assertEquals(
            "只保留与用户改字无关的 OCR；标题是模型产物，必须重算",
            setOf(DerivedType.OCR_TEXT),
            left,
        )

        val tags = tagRepository.associations.value
        assertTrue("AI 标签已不代表新内容", tags.none { it.tag.source == TagSource.AI })
        assertEquals(
            "用户标签是用户自己的整理，不能跟着 AI 结果一起清掉",
            listOf("待办"),
            tags.filter { it.tag.source == TagSource.USER }.map { it.tag.name },
        )

        assertEquals(
            "只重跑摘要与向量，不重新识别图片",
            listOf(recordId to JobType.SUMMARIZE, recordId to JobType.EMBED),
            scheduler.enqueued,
        )
        assertEquals(JobStatus.QUEUED, jobRepository.getByRecordAndType(recordId, JobType.SUMMARIZE)!!.status)
    }

    @Test
    fun UpdateText_webLink_keepsPageTitleAndBody() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId)
            .copy(type = RecordType.WEB_LINK)
        seedDerived(DerivedType.TITLE, DerivedType.PAGE_TEXT, DerivedType.SUMMARY, DerivedType.EMBEDDING)

        useCase(recordId, "改过的分享文字", now = 2_000L)

        val left = derivedRepository.stored.map { it.contentType }.toSet()
        assertEquals(
            "网页标题来自站点自身，正文来自抓取，都与原文编辑无关",
            setOf(DerivedType.TITLE, DerivedType.PAGE_TEXT),
            left,
        )
    }

    @Test
    fun UpdateText_unchangedAfterTrim_doesNothing() = runTest {
        seedDerived(DerivedType.SUMMARY)
        seedTags()

        val result = useCase(recordId, "  旧原文  ", now = 2_000L)

        assertEquals(UpdateRecordTextResult.Unchanged, result)
        assertEquals(1, recordRepository.byId.getValue(recordId).recordRevision)
        assertEquals(1, derivedRepository.stored.size)
        assertEquals(2, tagRepository.associations.value.size)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun UpdateText_deletedRecord_returnsNotFound() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId).copy(isDeleted = true)

        assertEquals(UpdateRecordTextResult.NotFound, useCase(recordId, "新原文"))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun UpdateText_cloudDisabled_marksLocalOnly() = runTest {
        aiSettings.cloudEnabled = false
        seedDerived(DerivedType.SUMMARY)

        val result = useCase(recordId, "新原文", now = 2_000L)

        assertEquals(UpdateRecordTextResult.Updated, result)
        assertTrue("云端被挡时排不出摘要/向量", scheduler.enqueued.isEmpty())
        assertEquals(
            "旧摘要已删，状态不能继续挂着 DONE（界面会永远停在“正在生成”）",
            ProcessingStatus.LOCAL_ONLY,
            recordRepository.byId.getValue(recordId).processingStatus,
        )
        assertTrue(diagnosticLog.entries.isNotEmpty())
    }

    @Test
    fun UpdateText_sensitiveContent_upgradesStatusAndDropsConfirmations() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId).copy(
            sensitivityStatus = SensitivityStatus.CLEAR,
            cloudConfirmedRevision = 1,
            cloudConfirmedScope = setOf(com.zhicun.app.domain.model.ContentPart.RAW_TEXT),
            webFetchConfirmedRevision = 1,
        )

        useCase(recordId, "联系我 13800138000", now = 2_000L)

        val saved = recordRepository.byId.getValue(recordId)
        assertEquals(SensitivityStatus.SUSPECTED, saved.sensitivityStatus)
        assertNull("内容变了，旧的上传确认必须失效", saved.cloudConfirmedRevision)
        assertTrue(saved.cloudConfirmedScope.isEmpty())
        assertNull("抓取确认同样绑定内容版本", saved.webFetchConfirmedRevision)
        assertFalse(saved.rawText.isNullOrEmpty())
    }

    @Test
    fun UpdateText_existingSuspicion_isNotDowngradedByCleanText() = runTest {
        recordRepository.byId[recordId] = recordRepository.byId.getValue(recordId)
            .copy(sensitivityStatus = SensitivityStatus.SUSPECTED)

        useCase(recordId, "这段内容很干净", now = 2_000L)

        assertEquals(
            "删掉敏感内容不等于洗白；放行仍要用户逐条确认（PRD §6.4）",
            SensitivityStatus.SUSPECTED,
            recordRepository.byId.getValue(recordId).sensitivityStatus,
        )
    }

    @Test
    fun UpdateText_revisionAdvancedConcurrently_returnsConflictWithoutOverwrite() = runTest {
        recordRepository.beforeUpdateRawText = {
            // 模拟后台任务/另一次编辑在"读到版本"与"写入"之间推进了版本
            val changed = recordRepository.byId.getValue(recordId).copy(
                rawText = "别人刚改的内容",
                recordRevision = 2,
            )
            recordRepository.byId[recordId] = changed
            recordRepository.stored.value = listOf(changed)
        }

        val result = useCase(recordId, "我的修改", now = 2_000L)

        assertEquals(UpdateRecordTextResult.Conflict, result)
        assertEquals("不能覆盖并发写入", "别人刚改的内容", recordRepository.byId.getValue(recordId).rawText)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun UpdateText_notifiesCollectionsOfMemberChange() = runTest {
        val result = useCase(recordId, "新原文", now = 5_000L)

        assertEquals(UpdateRecordTextResult.Updated, result)
        assertEquals("成员内容变了，所属合集要重算并集并作废旧确认", listOf(recordId), collectionRepository.refreshed)
    }

    @Test
    fun UpdateText_unchanged_doesNotNotifyCollections() = runTest {
        val result = useCase(recordId, " 旧原文 ", now = 5_000L)

        assertEquals(UpdateRecordTextResult.Unchanged, result)
        assertTrue(collectionRepository.refreshed.isEmpty())
    }
}
