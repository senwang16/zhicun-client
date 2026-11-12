package com.zhicun.app.presentation.create

import com.zhicun.app.R
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.BuildRecordInputUseCase
import com.zhicun.app.domain.usecase.CopyAssetToPrivateDirUseCase
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetNameResolver
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDraftRepository
import com.zhicun.app.fake.FakeMimeTypeResolver
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.util.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 新建记录页 ViewModel 测试（M5 验收：空内容校验、保存成功/失败路径）。
 *
 * 用**真实** [SaveRecordUseCase] + 假仓储，而不是 mock UseCase：
 * "保存失败要保留输入"这条断言的意义在于整条链路的行为，把 UseCase mock 掉就等于
 * 假设它已经正确，测不到真正的失败路径（AGENTS.md §5.2 禁止为了好测而跳过业务逻辑验证）。
 *
 * 跑在 Robolectric 上（而不是纯 JVM）：ViewModel 在失败分支里要 `Log.e`，
 * 纯 JVM 下 `android.util.Log` 是未实现的桩，会抛
 * `RuntimeException: Method e in android.util.Log not mocked` —— 而且它抛在 catch 块里，
 * 会静默吞掉"失败了要提示用户"这件事，非常难排查。
 */
@RunWith(RobolectricTestRunner::class)
class CreateRecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val recordRepository = FakeRecordRepository()
    private val assetRepository = FakeAssetRepository()
    private val draftRepository = FakeDraftRepository()
    private val jobRepository = FakeProcessingJobRepository()
    private val scheduler = FakeProcessingScheduler()
    private val mimeTypeResolver = FakeMimeTypeResolver(
        mapOf("content://media/1" to "image/png"),
    )
    private val nameResolver = FakeAssetNameResolver(
        mapOf("content://media/1" to "IMG_0001.png"),
    )

    private fun saveRecordUseCase() = SaveRecordUseCase(
        recordRepository = recordRepository,
        copyAsset = CopyAssetToPrivateDirUseCase(FakeAssetImporter(), assetRepository),
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

    private fun viewModel(): CreateRecordViewModel = CreateRecordViewModel(
        saveRecord = saveRecordUseCase(),
        buildInput = BuildRecordInputUseCase(mimeTypeResolver),
        draftRepository = draftRepository,
        assetNameResolver = nameResolver,
    )

    @Test
    fun Save_withBlankContent_doesNothing() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("   ")

        viewModel.save()
        advanceUntilIdle()

        assertFalse("空内容不得保存", viewModel.uiState.value.canSave)
        assertTrue("不应创建任何记录", recordRepository.stored.value.isEmpty())
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun Save_textOnly_createsManualTextNote() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("突然想到的一个点子")

        viewModel.save()
        advanceUntilIdle()

        val record = recordRepository.stored.value.single()
        assertEquals(CaptureSource.MANUAL, record.captureSource)
        assertEquals(RecordType.TEXT_NOTE, record.type)
        assertEquals("突然想到的一个点子", record.rawText)
        assertNull("手动输入没有来源应用", record.sourceApp)
    }

    @Test
    fun Save_textWithUrl_createsWebLinkRecord() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("https://example.com/a 这个值得存")

        viewModel.save()
        advanceUntilIdle()

        val record = recordRepository.stored.value.single()
        assertEquals(RecordType.WEB_LINK, record.type)
        assertEquals("https://example.com/a", record.sourceUrl)
    }

    @Test
    fun Save_withImage_createsImageRecordAndCopiesAsset() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)
        viewModel.onTextChange("配图说明")

        viewModel.save()
        advanceUntilIdle()

        val record = recordRepository.stored.value.single()
        assertEquals(RecordType.IMAGE, record.type)
        val asset = assetRepository.stored.single()
        assertEquals(record.id, asset.recordId)
        assertEquals("image/png", asset.mimeType)
    }

    @Test
    fun Save_failure_keepsInputAndShowsRetryHint() = runTest(mainDispatcherRule.dispatcher) {
        recordRepository.saveFailure = IllegalStateException("模拟磁盘已满")
        val viewModel = viewModel()
        viewModel.onTextChange("这段内容不能丢")

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("保存失败后正文必须保留", "这段内容不能丢", state.text)
        assertEquals(R.string.create_save_failed, state.errorMessageRes)
        assertFalse(state.isSaving)
        assertTrue("失败后仍可重试", state.canSave)
    }

    @Test
    fun Save_success_clearsDraft() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("写点东西")

        viewModel.save()
        advanceUntilIdle()

        assertNull("保存成功后草稿必须清掉，否则下次进来又冒出旧内容", draftRepository.draft)
        assertEquals(1, draftRepository.clearCount)
    }

    @Test
    fun OnBackRequested_withContent_asksForConfirmation() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("还没保存")

        viewModel.onBackRequested()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showDiscardDialog)
    }

    @Test
    fun OnBackRequested_withoutContent_navigatesBackDirectly() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }

        viewModel.onBackRequested()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf<CreateRecordEvent>(CreateRecordEvent.NavigateBack), events)
    }

    @Test
    fun OnDiscardConfirmed_clearsDraftAndNavigatesBack() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        viewModel.onTextChange("不要了")
        viewModel.onBackRequested()

        viewModel.onDiscardConfirmed()
        advanceUntilIdle()
        collector.cancel()

        assertNull(draftRepository.draft)
        assertEquals(listOf<CreateRecordEvent>(CreateRecordEvent.NavigateBack), events)
        assertFalse(viewModel.uiState.value.showDiscardDialog)
    }

    @Test
    fun Init_withExistingDraft_restoresTextAndFlagsIt() = runTest(mainDispatcherRule.dispatcher) {
        val restored = FakeDraftRepository(initialText = "上次写了一半")
        val viewModel = CreateRecordViewModel(
            saveRecord = saveRecordUseCase(),
            buildInput = BuildRecordInputUseCase(mimeTypeResolver),
            draftRepository = restored,
            assetNameResolver = nameResolver,
        )
        advanceUntilIdle()

        assertEquals("上次写了一半", viewModel.uiState.value.text)
        assertTrue("要让用户知道这段字不是自己刚打的", viewModel.uiState.value.restoredFromDraft)
    }

    @Test
    fun OnAttachmentPicked_duplicateUri_isIgnored() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)
        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)

        assertEquals(1, viewModel.uiState.value.attachments.size)
    }

    @Test
    fun OnAttachmentRemoved_dropsOnlyThatAttachment() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)
        viewModel.onAttachmentPicked("content://media/2", AttachmentKind.FILE)

        viewModel.onAttachmentRemoved("content://media/1")

        assertEquals(listOf("content://media/2"), viewModel.uiState.value.attachments.map { it.uri })
        assertNotNull(viewModel.uiState.value.attachments.single())
    }

    @Test
    fun OnAttachmentPicked_resolvesOriginalFileName() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)
        advanceUntilIdle()

        assertEquals("IMG_0001.png", viewModel.uiState.value.attachments.single().displayName)
    }

    @Test
    fun OnAttachmentPicked_noName_keepsNullForFallbackLabel() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAttachmentPicked("content://media/unknown", AttachmentKind.FILE)
        advanceUntilIdle()

        assertNull("查不到名字不该编一个", viewModel.uiState.value.attachments.single().displayName)
    }

    @Test
    fun OnAttachmentPicked_nameResolverThrows_stillKeepsAttachment() = runTest(mainDispatcherRule.dispatcher) {
        // 名字只用于展示：查询失败不能把附件弄丢
        nameResolver.throwOnResolve = true
        val viewModel = viewModel()

        viewModel.onAttachmentPicked("content://media/1", AttachmentKind.IMAGE)
        advanceUntilIdle()

        val attachment = viewModel.uiState.value.attachments.single()
        assertEquals("content://media/1", attachment.uri)
        assertNull(attachment.displayName)
    }

    // ------------------------------------------------------------ M21：语音入口

    @Test
    fun OnVoiceRequested_withoutContent_navigatesDirectly() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }

        viewModel.onVoiceRequested()
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf<CreateRecordEvent>(CreateRecordEvent.NavigateToVoice), events)
        assertFalse(viewModel.uiState.value.showVoiceConfirmDialog)
    }

    /** 有未保存内容时先问一句：录音会离开本页，附件也不会跟着走。 */
    @Test
    fun OnVoiceRequested_withText_asksForConfirmationFirst() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        viewModel.onTextChange("还没保存的内容")

        viewModel.onVoiceRequested()
        advanceUntilIdle()
        collector.cancel()

        assertTrue(viewModel.uiState.value.showVoiceConfirmDialog)
        assertTrue("确认前不得离开页面", events.isEmpty())
        assertEquals("还没保存的内容", viewModel.uiState.value.text)
    }

    /**
     * 确认去录音时必须在离开前落盘草稿（2026-10-28 修复）。
     *
     * 刻意**不推进虚拟时间**：防抖窗口是 400ms，未推进时只有“离开前显式保存”会写入。
     * 若退回“只发导航事件”的旧实现，这条断言会失败。
     */
    @Test
    fun OnVoiceConfirmed_persistsDraftBeforeNavigating() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onTextChange("还没保存的内容")
        viewModel.onVoiceRequested()

        viewModel.onVoiceConfirmed()

        assertEquals("还没保存的内容", draftRepository.draft)
    }

    @Test
    fun OnVoiceConfirmed_navigatesAndClosesDialog() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        viewModel.onTextChange("还没保存的内容")
        viewModel.onVoiceRequested()

        viewModel.onVoiceConfirmed()
        advanceUntilIdle()
        collector.cancel()

        assertFalse(viewModel.uiState.value.showVoiceConfirmDialog)
        assertEquals(listOf<CreateRecordEvent>(CreateRecordEvent.NavigateToVoice), events)
    }

    @Test
    fun OnVoiceDismissed_staysOnPageWithContentIntact() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val events = mutableListOf<CreateRecordEvent>()
        val collector = launch { viewModel.events.collect { events += it } }
        viewModel.onTextChange("继续写")
        viewModel.onVoiceRequested()

        viewModel.onVoiceDismissed()
        advanceUntilIdle()
        collector.cancel()

        assertFalse(viewModel.uiState.value.showVoiceConfirmDialog)
        assertTrue("取消去录音不应离开页面", events.isEmpty())
        assertEquals("继续写", viewModel.uiState.value.text)
    }
}
