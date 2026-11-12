package com.zhicun.app.presentation.detail

import androidx.lifecycle.SavedStateHandle
import com.zhicun.app.R
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.AddUserTagUseCase
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.ConfirmCloudProcessingUseCase
import com.zhicun.app.domain.usecase.ConfirmWebFetchUseCase
import com.zhicun.app.domain.usecase.GetDerivedContentUseCase
import com.zhicun.app.domain.usecase.GetRecordByIdUseCase
import com.zhicun.app.domain.usecase.GetRelatedRecordsUseCase
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.usecase.UpdateRecordTextUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeAudioPlayer
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 详情页 ViewModel 测试：命中、未命中、附件与派生内容加载、重试。 */
@RunWith(RobolectricTestRunner::class)
class RecordDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val derivedRepository = FakeDerivedContentRepository()
    private val jobRepository = FakeProcessingJobRepository()
    private val scheduler = FakeProcessingScheduler()
    private val tagRepository = FakeTagRepository()

    private fun viewModel(
        recordId: String,
        records: FakeRecordRepository,
        assets: FakeAssetRepository = FakeAssetRepository(),
        derived: FakeDerivedContentRepository = derivedRepository,
        audioPlayer: FakeAudioPlayer = FakeAudioPlayer(),
        embeddings: FakeEmbeddingRepository = FakeEmbeddingRepository(),
        aiSettings: FakeAiSettings = FakeAiSettings(),
        gateway: FakeAiGatewaySettings = FakeAiGatewaySettings(),
        collections: FakeCollectionRepository = FakeCollectionRepository(),
    ): RecordDetailViewModel {
        // F-5：抓取确认不看云端通路，因此这里只需要记录仓储 + 任务表 + 调度。
        // 两处依赖共用同一个实例：确认上传时会顺带调用它（2026-10-24 一次点击合并）。
        val confirmFetch = ConfirmWebFetchUseCase(
            recordRepository = records,
            jobRepository = jobRepository,
            scheduler = scheduler,
        )
        val retry = RetryProcessingUseCase(
            records,
            jobRepository,
            scheduler,
            aiSettings,
            gateway,
        )
        return RecordDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("recordId" to recordId)),
        getRecordById = GetRecordByIdUseCase(records),
        getDerivedContent = GetDerivedContentUseCase(derived),
        assetRepository = assets,
        recordRepository = records,
        collectionRepository = collections,
        retryProcessingUseCase = retry,
        updateRecordText = UpdateRecordTextUseCase(
            recordRepository = records,
            derivedContentRepository = derived,
            collectionRepository = collections,
            tagRepository = tagRepository,
            sensitiveDetector = SensitiveContentDetector(),
            retryProcessing = retry,
            diagnosticLog = FakeDiagnosticLog(),
        ),
        confirmCloudProcessing = ConfirmCloudProcessingUseCase(
            recordRepository = records,
            collectionRepository = collections,
            jobRepository = jobRepository,
            derivedContentRepository = derived,
            assetRepository = assets,
            scheduler = scheduler,
            aiSettings = aiSettings,
            gatewaySettings = gateway,
            // 2026-10-24：敏感网页一次确认同时放行抓取
            confirmWebFetch = confirmFetch,
        ),
        // F-5：允许抓取网页与云端确认是两条独立路径（云端不可用时单独走这里）
        confirmWebFetch = confirmFetch,
        tagRepository = tagRepository,
        addUserTag = AddUserTagUseCase(tagRepository),
        archiveRecords = ArchiveRecordsUseCase(records),
        getRelatedRecords = GetRelatedRecordsUseCase(embeddings, records),
        // 2026-10-29：删除附件 / 删除记录（装配见 DetailTestUseCases.kt）
        deleteAsset = testDeleteAssetUseCase(
            recordRepository = records,
            retryProcessing = retry,
            assetRepository = assets,
            derivedContentRepository = derived,
            collectionRepository = collections,
            tagRepository = tagRepository,
        ),
        deleteRecords = testDeleteRecordsUseCase(
            recordRepository = records,
            assetRepository = assets,
            collectionRepository = collections,
            scheduler = scheduler,
        ),
        audioPlayer = audioPlayer,
        aiSettings = aiSettings,
        gatewaySettings = gateway,
        // 2026-10-29 修复 P2：转写区失败判据读任务表（多数用例用空表 = 未失败）
        processingJobRepository = jobRepository,
        )
    }

    @Test
    fun RecordDetailViewModel_existingRecord_emitsContentWithAssets() = runTest {
        val record = testRecord(id = "r1")
        val repository = FakeRecordRepository(listOf(record))
        val assetRepository = FakeAssetRepository(listOf(testAsset(id = "a1", recordId = "r1")))

        val state = viewModel("r1", repository, assetRepository).uiState.value

        assertTrue("应为 Content，实际 $state", state is RecordDetailUiState.Content)
        state as RecordDetailUiState.Content
        assertEquals("r1", state.record.id)
        assertEquals(1, state.assets.size)
    }

    @Test
    fun RecordDetailViewModel_unknownId_emitsNotFound() = runTest {
        val state = viewModel("missing", FakeRecordRepository()).uiState.value

        assertEquals(RecordDetailUiState.NotFound, state)
    }

    /** 2026-10-29 修复 P2：TRANSCRIPTION 任务失败时 transcribeJobFailed 为 true。 */
    @Test
    fun RecordDetailViewModel_failedTranscriptionJob_flagsTranscribeJobFailed() = runTest {
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.VOICE_NOTE)),
        )
        jobRepository.insert(
            ProcessingJob(
                id = "job-t1",
                recordId = "r1",
                jobType = JobType.TRANSCRIPTION,
                status = JobStatus.FAILED,
                attempts = 3,
            ),
        )

        val state = viewModel("r1", repository).uiState.value as RecordDetailUiState.Content

        assertTrue("转写任务失败应标记 transcribeJobFailed", state.transcribeJobFailed)
    }

    /** 2026-10-29 修复 P2：转写成功（空文本也算成功）后摘要失败打成 PARTIAL，不得误报转写失败。 */
    @Test
    fun RecordDetailViewModel_partialRecordWithDoneTranscription_notFlagged() = runTest {
        val repository = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.VOICE_NOTE,
                    processingStatus = ProcessingStatus.PARTIAL,
                ),
            ),
        )
        jobRepository.insert(
            ProcessingJob(
                id = "job-t1",
                recordId = "r1",
                jobType = JobType.TRANSCRIPTION,
                status = JobStatus.DONE,
                attempts = 1,
            ),
        )

        val state = viewModel("r1", repository).uiState.value as RecordDetailUiState.Content

        assertFalse("记录级 PARTIAL 不应污染转写区失败标志", state.transcribeJobFailed)
    }

    /** M18：有同模型向量且过了下限的记录，要出现在 relatedRecords 里。 */
    @Test
    fun RecordDetailViewModel_relatedRecords_areLoaded() = runTest {
        val embeddings = FakeEmbeddingRepository()
        val repository = FakeRecordRepository(
            listOf(
                testRecord(id = "r1", rawText = "主题 A"),
                testRecord(id = "r2", rawText = "主题 A 相似"),
            ),
        )
        embeddings.seed("r1", floatArrayOf(1f, 0f))
        embeddings.seed("r2", floatArrayOf(1f, 0f))

        val vm = viewModel("r1", repository, embeddings = embeddings)

        assertEquals(listOf("r2"), vm.relatedRecords.value.items.map { it.record.id })
    }

    /** M18：当前记录没有向量（本地模式 / 未处理完）时分区为空，不报错。 */
    @Test
    fun RecordDetailViewModel_relatedRecords_emptyWithoutVector() = runTest {
        val vm = viewModel(
            "r1",
            FakeRecordRepository(listOf(testRecord(id = "r1", rawText = "主题 A"))),
        )

        assertTrue(vm.relatedRecords.value.isEmpty)
    }

    @Test
    fun RecordDetailViewModel_blankId_emitsNotFound() = runTest {
        // 路由参数缺失（深链拼错）时不应去查库
        val state = viewModel("", FakeRecordRepository(listOf(testRecord(id = "")))).uiState.value

        assertEquals(RecordDetailUiState.NotFound, state)
    }

    @Test
    fun RecordDetailViewModel_ocrDerivedContent_isExposed() = runTest {
        derivedRepository.upsert(
            DerivedContent(
                id = "r1:OCR_TEXT",
                recordId = "r1",
                contentType = DerivedType.OCR_TEXT,
                content = "识别出的会议纪要",
                processorVersion = "test",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val state = viewModel("r1", FakeRecordRepository(listOf(testRecord(id = "r1"))))
            .uiState.value

        state as RecordDetailUiState.Content
        assertEquals(1, state.derived.size)
        assertEquals("识别出的会议纪要", state.derived.first().content)
    }

    @Test
    fun RecordDetailViewModel_derivedReadFails_emitsError() = runTest {
        derivedRepository.readFailure = IllegalStateException("数据库被锁")

        val state = viewModel("r1", FakeRecordRepository(listOf(testRecord(id = "r1"))))
            .uiState.value

        assertEquals(RecordDetailUiState.Error(R.string.detail_error_load), state)
    }

    @Test
    fun RecordDetailViewModel_onRetryClicked_resetsFailedJobAndRequeues() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.IMAGE,
                    processingStatus = ProcessingStatus.FAILED,
                ),
            ),
        )
        jobRepository.insert(
            ProcessingJob(
                id = "job-1",
                recordId = "r1",
                jobType = JobType.OCR,
                status = JobStatus.FAILED,
                attempts = 3,
            ),
        )
        val vm = viewModel("r1", records)

        vm.onRetryClicked()

        val job = jobRepository.getByRecordAndType("r1", JobType.OCR)!!
        assertEquals("重新识别要回到队列", JobStatus.QUEUED, job.status)
        assertEquals("并重新给满次数", 0, job.attempts)
        // M11/M12 起图片的完整链路是「识别 → 摘要 → 向量」，重试要把下游一起重排，
        // 否则只重跑 OCR 的话摘要与向量会永远停在上一轮的结果上。
        assertEquals(
            listOf(
                "r1" to JobType.OCR,
                "r1" to JobType.SUMMARIZE,
                "r1" to JobType.EMBED,
            ),
            scheduler.enqueued,
        )
        assertEquals(
            ProcessingStatus.PENDING,
            (vm.uiState.value as RecordDetailUiState.Content).record.processingStatus,
        )
    }

    /**
     * 云端被挡 + 纯云端任务的记录：重试是空动作，界面不该给这个按钮（2026-10-23 整改 F-4）。
     *
     * `TEXT_NOTE` 的任务只有 SUMMARIZE / EMBED，两个都在 `CLOUD_JOBS` 里；
     * `RetryProcessingUseCase` 一个都排不出来，直接返回 false 且不改状态。
     */
    @Test
    fun RecordDetailViewModel_cloudDisabled_textNote_cannotRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.TEXT_NOTE,
                    processingStatus = ProcessingStatus.LOCAL_ONLY,
                ),
            ),
        )
        val vm = viewModel("r1", records, aiSettings = FakeAiSettings(cloudAiEnabled = false))

        val state = vm.uiState.value as RecordDetailUiState.Content

        assertEquals(false, state.canRetry)
        // 原因说明仍要如实给出：界面靠它说"云端 AI 处理已关闭"
        assertEquals(CloudUploadGate.Reason.CLOUD_DISABLED, state.cloudBlockReason)
    }

    /** 网关未配置同理：这是默认安装包的形态，不是边角情况。 */
    @Test
    fun RecordDetailViewModel_gatewayMissing_voiceNote_cannotRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.VOICE_NOTE,
                    processingStatus = ProcessingStatus.LOCAL_ONLY,
                ),
            ),
        )
        val vm = viewModel(
            "r1",
            records,
            gateway = FakeAiGatewaySettings(baseUrl = null, token = null),
        )

        assertEquals(false, (vm.uiState.value as RecordDetailUiState.Content).canRetry)
    }

    /** 图片还有本地 OCR 可跑：重试是有意义的，按钮必须留着。 */
    @Test
    fun RecordDetailViewModel_cloudDisabled_image_canStillRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.IMAGE,
                    processingStatus = ProcessingStatus.FAILED,
                ),
            ),
        )
        val vm = viewModel("r1", records, aiSettings = FakeAiSettings(cloudAiEnabled = false))

        assertEquals(true, (vm.uiState.value as RecordDetailUiState.Content).canRetry)
    }

    /** 对照：云端可用时文本记录照常可重试（别把按钮一律关掉）。 */
    @Test
    fun RecordDetailViewModel_cloudEnabled_textNote_canRetry() = runTest {
        val records = FakeRecordRepository(listOf(testRecord(id = "r1", type = RecordType.TEXT_NOTE)))

        val vm = viewModel("r1", records)

        assertEquals(true, (vm.uiState.value as RecordDetailUiState.Content).canRetry)
    }

    /**
     * 2026-11-05 线上回归：上游没返回摘要却被记成成功 ⇒ 界面既说"没有可摘要的文字内容"
     * 又没有重试入口。这里把"该重试"补回来。
     *
     * 判据三件同时成立才给入口：摘要为空、记录确实有正文、重试真的会做事。
     */
    @Test
    fun RecordDetailViewModel_blankSummaryWithBody_canRetryEmptySummary() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.TEXT_NOTE,
                    rawText = "今天和产品对齐了范围：先做本地优先的收件箱，再补 AI 摘要。",
                ),
            ),
        )
        derivedRepository.upsert(blankSummary("r1"))

        val state = viewModel("r1", records).uiState.value as RecordDetailUiState.Content

        assertEquals("有正文却写成了空摘要，必须给重试入口", true, state.canRetryEmptySummary)
        // 记录本身仍是"成功"状态 —— 这正是需要兜底的原因
        assertEquals(ProcessingStatus.PENDING, state.record.processingStatus)
    }

    /**
     * 短正文（"买牛奶"）的空摘要是提示词允许的合法结果，不给重试入口。
     *
     * 与上一条的差别只在正文长度：`SummaryPolicy` 认为短到没有摘要可写，
     * 重试多少次都还是空，按钮只会让用户白点。
     */
    @Test
    fun RecordDetailViewModel_blankSummaryWithShortBody_doesNotOfferEmptyRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.TEXT_NOTE, rawText = "买牛奶")),
        )
        derivedRepository.upsert(blankSummary("r1"))

        val state = viewModel("r1", records).uiState.value as RecordDetailUiState.Content

        assertEquals("短正文不需要摘要，不该给重试入口", false, state.canRetryEmptySummary)
    }

    /** 对照：摘要真的有内容时不该冒出一个"重试"按钮。 */
    @Test
    fun RecordDetailViewModel_nonBlankSummary_doesNotOfferEmptyRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.TEXT_NOTE, rawText = "第一行标题\n第二行正文")),
        )
        derivedRepository.upsert(blankSummary("r1").copy(content = "这两行讲的是存档工具"))

        val state = viewModel("r1", records).uiState.value as RecordDetailUiState.Content

        assertEquals(false, state.canRetryEmptySummary)
    }

    /**
     * 本来就没有正文（图片没识别出文字）时，重试多少次还是空摘要 —— 不给按钮。
     *
     * 用 IMAGE 而不是 FILE：图片还有本地 OCR 可言，`canRetry` 为真，
     * 所以这条用例真正卡住的是"有正文"这一条判据，而不是被 canRetry 顺带挡掉。
     */
    @Test
    fun RecordDetailViewModel_blankSummaryWithoutBody_doesNotOfferEmptyRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.IMAGE, rawText = null)),
        )
        derivedRepository.upsert(blankSummary("r1"))

        val state = viewModel("r1", records).uiState.value as RecordDetailUiState.Content

        assertEquals(true, state.canRetry)
        assertEquals("没有正文可摘要，不算异常", false, state.canRetryEmptySummary)
    }

    /** 云端被挡时点重试是空动作：不能给一个按了没反应的按钮（与 canRetry 同口径）。 */
    @Test
    fun RecordDetailViewModel_blankSummaryCloudDisabled_doesNotOfferEmptyRetry() = runTest {
        val records = FakeRecordRepository(
            listOf(
                testRecord(
                    id = "r1",
                    type = RecordType.TEXT_NOTE,
                    rawText = "第一行标题\n第二行正文",
                    processingStatus = ProcessingStatus.LOCAL_ONLY,
                ),
            ),
        )
        derivedRepository.upsert(blankSummary("r1"))

        val state = viewModel("r1", records, aiSettings = FakeAiSettings(cloudAiEnabled = false))
            .uiState.value as RecordDetailUiState.Content

        assertEquals(false, state.canRetry)
        assertEquals(false, state.canRetryEmptySummary)
    }

    /** 空摘要派生内容的构造：只写这一条，避免各用例重复 8 个字段。 */
    private fun blankSummary(recordId: String) = DerivedContent(
        id = "$recordId:${DerivedType.SUMMARY}",
        recordId = recordId,
        contentType = DerivedType.SUMMARY,
        content = "",
        processorVersion = "test",
        processedAt = 1L,
        errorMessage = null,
    )

    @Test
    fun RecordDetailViewModel_onRetryClicked_beforeLoaded_doesNothing() = runTest {
        // 页面还停在 Loading / NotFound 时点不到按钮，但防止将来误接
        val vm = viewModel("missing", FakeRecordRepository())

        vm.onRetryClicked()

        assertTrue(jobRepository.jobs.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    /** U-7（2026-10-24）：改名 AI 标签 → 删旧 AI 标签 + 建同名用户标签（下次摘要重算不会盖掉）。 */
    @Test
    fun RecordDetailViewModel_onTagsEdited_renamedAiTag_becomesUserTag() = runTest {
        val records = FakeRecordRepository(listOf(testRecord(id = "r1")))
        val vm = viewModel("r1", records)
        tagRepository.replaceAiTags("r1", listOf("会议"))

        vm.onTagsEdited(listOf("重要会议"))

        val tags = tagRepository.getByRecordId("r1")
        assertEquals(1, tags.size)
        assertEquals("重要会议", tags.single().name)
        assertEquals(TagSource.USER, tags.single().source)
    }

    /** 没改名的 AI 标签原样保留（保持 AI 来源，下次摘要还能被模型修正）。 */
    @Test
    fun RecordDetailViewModel_onTagsEdited_unchangedName_keepsAiSource() = runTest {
        val records = FakeRecordRepository(listOf(testRecord(id = "r1")))
        val vm = viewModel("r1", records)
        tagRepository.replaceAiTags("r1", listOf("会议"))

        vm.onTagsEdited(listOf("会议"))

        val tags = tagRepository.getByRecordId("r1")
        assertEquals(1, tags.size)
        assertEquals(TagSource.AI, tags.single().source)
    }

    /** 混合差量：增、删、规范化（全角空格不算改名）、空白片段跳过。 */
    @Test
    fun RecordDetailViewModel_onTagsEdited_mixedDiff_appliesRemovalsAndAdds() = runTest {
        val records = FakeRecordRepository(listOf(testRecord(id = "r1")))
        val vm = viewModel("r1", records)
        tagRepository.replaceAiTags("r1", listOf("会议", "过时的"))
        tagRepository.addUserTag("r1", "待办")

        // "会议　"（带全角空格）normalize 后与现有 AI 标签同名 → 必须判为"没改"；
        // "过时的"不在新名单里被删；"新标签"落成用户标签；"   " 全空白跳过
        vm.onTagsEdited(listOf("会议　", "待办", "新标签", "   "))

        val tags = tagRepository.getByRecordId("r1").sortedBy { it.name }
        assertEquals(listOf("会议", "待办", "新标签"), tags.map { it.name })
        assertEquals(TagSource.AI, tags.first { it.name == "会议" }.source)
        assertEquals(TagSource.USER, tags.first { it.name == "新标签" }.source)
    }

    /** 空名单不做事（界面上空输入是"取消"，这里防误传一键清光）。 */
    @Test
    fun RecordDetailViewModel_onTagsEdited_emptyNames_doesNothing() = runTest {
        val records = FakeRecordRepository(listOf(testRecord(id = "r1")))
        val vm = viewModel("r1", records)
        tagRepository.replaceAiTags("r1", listOf("会议"))

        vm.onTagsEdited(emptyList())

        assertEquals(1, tagRepository.getByRecordId("r1").size)
    }

    /**
     * r4 审查 O-1：播放器是单例，从本页跳到相关记录详情再返回时，`onCompleted`
     * 回调可能已被另一页的 `onCleared` 置空 —— 音频自然播完后没有回调通知，
     * ticker 必须兜底把界面从"播放中"复位，而不是永远停在冻结进度。
     */
    @Test
    fun RecordDetailViewModel_playbackEndsWithoutCallback_resetsPlayingState() = runTest {
        val audioPlayer = FakeAudioPlayer()
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.VOICE_NOTE, rawText = null)),
        )
        val assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "a1", recordId = "r1", mimeType = "audio/mp4")),
        )
        val vm = viewModel("r1", repository, assetRepository, audioPlayer = audioPlayer)

        vm.onPlayPauseClicked()
        assertTrue("点击播放后应为播放中", vm.playback.value.isPlaying)

        // 模拟回调丢失：叠栈的另一详情页 onCleared 已置空回调，随后音频播完
        audioPlayer.onCompleted = null
        audioPlayer.playing = false

        // 走 Main 调度器的虚拟时钟推进一个 ticker 周期（PLAYBACK_TICK_MS = 300ms）
        mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(301)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        assertEquals("ticker 兜底应把播放状态复位", false, vm.playback.value.isPlaying)
        assertEquals("进度应归零（从头播的语义）", 0L, vm.playback.value.positionMs)
    }

    /**
     * 2026-10-24 deepseek 审查 P3：播放器是单例，详情页叠栈时另一页接管后，
     * 本页 ticker 不得把别人的进度镜像过来，也不得在别人播完时替自己复位。
     */
    @Test
    fun RecordDetailViewModel_anotherPageTakesPlayer_doesNotMirrorItsProgress() = runTest {
        val audioPlayer = FakeAudioPlayer()
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.VOICE_NOTE, rawText = null)),
        )
        val assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "a1", recordId = "r1", mimeType = "audio/mp4")),
        )
        val vm = viewModel("r1", repository, assetRepository, audioPlayer = audioPlayer)

        vm.onPlayPauseClicked()
        assertTrue("先让本页进入播放中", vm.playback.value.isPlaying)

        // 另一详情页调用同一个单例播放器（play 会先停掉本页文件）
        audioPlayer.play("/data/other-page.m4a")
        audioPlayer.position = 9_000L

        mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(301)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        assertEquals("本页不能镜像另一页的播放状态", false, vm.playback.value.isPlaying)
        assertEquals("进度不能被另一页带跑", 0L, vm.playback.value.positionMs)
        assertEquals("不能干扰另一页的播放", true, audioPlayer.playing)
        assertEquals("/data/other-page.m4a", audioPlayer.currentPath)
    }

    /**
     * 另一页接管后、ticker 还没跑的这一小段窗口里点本页的"暂停"，
     * 不能把别人的音频暂停掉。
     */
    @Test
    fun RecordDetailViewModel_pauseAfterAnotherPageTookPlayer_doesNotPauseIt() = runTest {
        val audioPlayer = FakeAudioPlayer()
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.VOICE_NOTE, rawText = null)),
        )
        val assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "a1", recordId = "r1", mimeType = "audio/mp4")),
        )
        val vm = viewModel("r1", repository, assetRepository, audioPlayer = audioPlayer)

        vm.onPlayPauseClicked() // 本页播放中
        audioPlayer.play("/data/other-page.m4a") // 另一页接管

        vm.onPlayPauseClicked() // 本页 UI 仍显示播放中，用户点了暂停

        assertEquals("不能暂停另一页的音频", true, audioPlayer.playing)
        assertEquals("本页应回到未播放", false, vm.playback.value.isPlaying)
        assertEquals("不能显示另一页的进度", 0L, vm.playback.value.positionMs)
    }

    /**
     * 暂停中的本页在别页接管播放器后，再次点播放必须重开自己的文件，
     * 而不是 resume 另一页的文件（同一个 bug 的另一条路径）。
     */
    @Test
    fun RecordDetailViewModel_playerTakenByAnotherPage_restartsOwnAudio() = runTest {
        val audioPlayer = FakeAudioPlayer()
        val repository = FakeRecordRepository(
            listOf(testRecord(id = "r1", type = RecordType.VOICE_NOTE, rawText = null)),
        )
        val assetRepository = FakeAssetRepository(
            listOf(testAsset(id = "a1", recordId = "r1", mimeType = "audio/mp4")),
        )
        val vm = viewModel("r1", repository, assetRepository, audioPlayer = audioPlayer)

        vm.onPlayPauseClicked() // 播放本页
        vm.onPlayPauseClicked() // 暂停
        audioPlayer.play("/data/other-page.m4a") // 另一页接管

        vm.onPlayPauseClicked() // 再点本页播放

        assertEquals("不能 resume 另一页的文件", 0, audioPlayer.resumeCalls)
        assertTrue(
            "必须重新打开本页音频",
            audioPlayer.playedPaths.last().endsWith("/assets/r1/a1"),
        )

        // 收尾：暂停以取消 ticker。否则无限 delay 会让 runTest 永远等不到虚拟时钟空闲
        // （PLAYBOOK 陷阱 12）。
        vm.onPlayPauseClicked()
    }

    // ------------------------------------------------- 原文编辑（2026-10-26）

    /** 编辑并保存：写入新原文、关闭编辑态，并触发 AI 结果重算。 */
    @Test
    fun RecordDetailViewModel_saveRawText_persistsTrimmedTextAndClosesEditor() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "r1", rawText = "旧内容")))
        val vm = viewModel("r1", repository)

        vm.onStartRawTextEdit()
        assertEquals("编辑应预填当前原文", "旧内容", vm.contentEdit.value.draft)
        vm.onRawTextDraftChange("  新内容  ")
        vm.onSaveRawText()

        assertEquals("新内容", repository.byId.getValue("r1").rawText)
        assertTrue("保存成功应关闭编辑态", !vm.contentEdit.value.editing)
    }

    @Test
    fun RecordDetailViewModel_saveBlankRawText_keepsEditorWithEmptyError() = runTest {
        val repository = FakeRecordRepository(listOf(testRecord(id = "r1", rawText = "旧内容")))
        val vm = viewModel("r1", repository)

        vm.onStartRawTextEdit()
        vm.onRawTextDraftChange("   ")
        vm.onSaveRawText()

        assertEquals(DetailContentEditError.Empty, vm.contentEdit.value.error)
        assertTrue("内容为空时不该关闭编辑", vm.contentEdit.value.editing)
        assertEquals("旧内容", repository.byId.getValue("r1").rawText)
    }

    @Test
    fun RecordDetailViewModel_cancelRawTextEdit_discardsDraft() = runTest {
        val vm = viewModel("r1", FakeRecordRepository(listOf(testRecord(id = "r1"))))

        vm.onStartRawTextEdit()
        vm.onRawTextDraftChange("写了一半")
        vm.onCancelRawTextEdit()

        assertEquals(DetailContentEditState(), vm.contentEdit.value)
    }

    // ------------------------------------------------------------ M20：合集详情

    private suspend fun collectionViewModel(
        collection: Record,
        members: List<Record>,
        stale: Boolean = false,
        collections: FakeCollectionRepository = FakeCollectionRepository(),
    ): RecordDetailViewModel {
        val repository = FakeRecordRepository(listOf(collection) + members)
        collections.recordLookup = { id -> repository.byId[id] }
        collections.stale = stale
        collections.create(collection, members.map { it.id }, now = 1_000L)
        return viewModel(collection.id, repository, collections = collections)
    }

    /** 成员是实时读取的原记录：卡片上看到的就是成员当前的正文。 */
    @Test
    fun RecordDetailViewModel_collection_loadsLiveMembersAndStaleFlag() = runTest {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = "合集说明")
        val older = testRecord(id = "a", rawText = "成员 A 的内容", createdAt = 1_000L)
        val newer = testRecord(id = "b", rawText = "成员 B 的内容", createdAt = 2_000L)

        val vm = collectionViewModel(collection, listOf(older, newer), stale = true)

        val content = vm.uiState.value as RecordDetailUiState.Content
        assertEquals(
            listOf("a", "b"),
            content.collection.members.map { it.member.recordId },
        )
        assertEquals("成员 A 的内容", content.collection.members.first().record.rawText)
        assertTrue("成员内容与生成时不一致时应提示可重新生成", content.collection.stale)
    }

    /** 门禁用实时并集：成员里有一条敏感，合集本身 CLEAR 也要再确认一次。 */
    @Test
    fun RecordDetailViewModel_collection_gateUnionsMemberSensitivity() = runTest {
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            rawText = "合集说明",
            sensitivityStatus = SensitivityStatus.CLEAR,
        )
        val sensitive = testRecord(id = "a", sensitivityStatus = SensitivityStatus.CONFIRMED_SENSITIVE)
        val clean = testRecord(id = "b", sensitivityStatus = SensitivityStatus.CLEAR)

        val vm = collectionViewModel(collection, listOf(sensitive, clean))

        val content = vm.uiState.value as RecordDetailUiState.Content
        assertTrue("敏感成员未确认时，合集必须要求再确认", content.cloudConfirmPending)
        assertFalse("敏感未确认的合集不该显示能跑通的云重试", content.canRetry)
    }

    /** 已删除来源仍展示（用户要知道合集里原来有什么），但不参与门禁。 */
    @Test
    fun RecordDetailViewModel_collection_deletedMemberListedButExcludedFromGate() = runTest {
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            rawText = "合集说明",
            sensitivityStatus = SensitivityStatus.CLEAR,
        )
        val deletedSensitive = testRecord(
            id = "a",
            sensitivityStatus = SensitivityStatus.CONFIRMED_SENSITIVE,
            isDeleted = true,
        )
        val clean = testRecord(id = "b", sensitivityStatus = SensitivityStatus.CLEAR)

        val vm = collectionViewModel(collection, listOf(deletedSensitive, clean))

        val content = vm.uiState.value as RecordDetailUiState.Content
        assertEquals("已删除来源应保留展示", 2, content.collection.members.size)
        assertFalse("已删除成员的内容不再外发，不该继续拦合集", content.cloudConfirmPending)
    }
}
