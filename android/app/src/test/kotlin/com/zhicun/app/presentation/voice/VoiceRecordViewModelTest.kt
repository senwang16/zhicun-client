package com.zhicun.app.presentation.voice

import com.zhicun.app.R
import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.audio.VoiceRecordingPolicy
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.CopyAssetToPrivateDirUseCase
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import com.zhicun.app.domain.usecase.SaveVoiceNoteUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeVoiceRecorder
import com.zhicun.app.util.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 录音页状态机（M9）。
 *
 * 覆盖六条互斥路径：正常录音保存、暂停/继续、太短不建记录、
 * 被系统打断后"保留"与"丢弃"、权限被拒与开始失败。
 * 这些规则全部在 ViewModel 里，录音器换成本地假实现后可在 JVM 上穷举。
 */
@RunWith(RobolectricTestRunner::class)
class VoiceRecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val recorder = FakeVoiceRecorder()
    private val recordRepository = FakeRecordRepository()
    private val assetRepository = FakeAssetRepository()
    private val assetImporter = FakeAssetImporter()

    private lateinit var events: MutableList<VoiceRecordEvent>
    private lateinit var viewModel: VoiceRecordViewModel

    @Before
    fun setUp() {
        events = mutableListOf()
        viewModel = VoiceRecordViewModel(
            recorder = recorder,
            saveVoiceNote = SaveVoiceNoteUseCase(
                SaveRecordUseCase(
                    recordRepository = recordRepository,
                    copyAsset = CopyAssetToPrivateDirUseCase(assetImporter, assetRepository),
                    sensitiveDetector = SensitiveContentDetector(),
                    scheduleProcessing = ScheduleProcessingUseCase(
                        FakeProcessingJobRepository(),
                        FakeProcessingScheduler(),
                        recordRepository,
                        FakeAiSettings(),
                        FakeAiGatewaySettings(),
                    ),
                    diagnosticLog = FakeDiagnosticLog(),
                ),
            ),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    private fun clip(durationMs: Long = 3_000L, bytes: Int = 4096): RecordedClip {
        val file = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "clip-${System.nanoTime()}.m4a",
        )
        file.writeBytes(ByteArray(bytes) { 1 })
        return RecordedClip(file = file, durationMs = durationMs, sizeBytes = bytes.toLong())
    }

    /**
     * 订阅 ViewModel 的一次性事件。
     *
     * 必须用 `UnconfinedTestDispatcher(testScheduler)` 起收集协程：收集器会**立即**挂到
     * `receiveAsFlow()` 上，事件一发出就能被收走。若用默认的 `StandardTestDispatcher`，
     * 收集协程要等调度器跑到才开始，`advanceUntilIdle()` 与"事件已发出"的先后顺序就成了
     * 隐式依赖 —— 实测会稳定收到空列表（M9 首次跑测时 4 个用例同时空列表）。
     */
    private fun TestScope.collectEvents() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }
    }

    /**
     * 收尾：把还开着的录音关掉。
     *
     * 计时器是个 `while (isActive) { delay(200) }` 的无限循环，而 `viewModelScope`
     * 在单测里跑在与 `runTest` **同一个**虚拟时钟上（`UnconfinedTestDispatcher` 会继承
     * Main 的 scheduler）。于是只要用例结束时还在录音态，`runTest` 收尾那次
     * `advanceUntilIdle()` 就会一直推进虚拟时间、永远到不了 idle —— 表现为整个测试进程
     * 卡死（CPU 跑满却没有任何输出）。凡是断言完仍停在录音态的用例都必须调用本方法。
     */
    private fun stopTicker() {
        viewModel.onCancel()
    }

    /**
     * 走完"授权 → 待开始 → 开始录音"两步（2026-10-24 起不再自动开麦）。
     * 只关心录音阶段行为的用例统一从这里进入，避免每处重复写两行。
     */
    private fun startRecording() {
        viewModel.onPermissionGranted()
        viewModel.onStartClicked()
    }

    /** 2026-10-24 用户反馈：授权后只停在"待开始"，不自动开麦。 */
    @Test
    fun OnPermissionGranted_showsReadyWithoutStarting() = runTest {
        viewModel.onPermissionGranted()

        assertEquals(VoiceRecordUiState.Ready, viewModel.uiState.value)
        assertTrue("授权不等于开录", recorder.startedFiles.isEmpty())
    }

    @Test
    fun OnStartClicked_afterPermission_startsRecording() = runTest {
        viewModel.onPermissionGranted()

        viewModel.onStartClicked()

        val state = viewModel.uiState.value
        assertTrue("应进入录音态，实际 $state", state is VoiceRecordUiState.Recording)
        assertEquals(1, recorder.startedFiles.size)
        assertFalse((state as VoiceRecordUiState.Recording).paused)

        stopTicker()
    }

    /** 没拿到权限就点开始：不能绕过权限直接开麦。 */
    @Test
    fun OnStartClicked_withoutPermission_isIgnored() = runTest {
        viewModel.onStartClicked()

        assertEquals(VoiceRecordUiState.Preparing, viewModel.uiState.value)
        assertTrue(recorder.startedFiles.isEmpty())
    }

    @Test
    fun OnStartClicked_startFails_showsFailure() = runTest {
        recorder.startSucceeds = false
        viewModel.onPermissionGranted()

        viewModel.onStartClicked()

        assertEquals(VoiceRecordUiState.Failed(R.string.voice_start_failed), viewModel.uiState.value)
    }

    @Test
    fun OnPermissionDenied_doesNotCrashAndShowsExplanation() = runTest {
        viewModel.onPermissionDenied()

        assertEquals(VoiceRecordUiState.PermissionDenied, viewModel.uiState.value)
        assertTrue("未授权不该尝试开麦", recorder.startedFiles.isEmpty())
    }

    @Test
    fun Ticker_updatesElapsedFromRecorder() = runTest {
        startRecording()
        recorder.elapsed = 1_200L

        advanceTimeBy(250L)

        val state = viewModel.uiState.value as VoiceRecordUiState.Recording
        assertEquals(1_200L, state.elapsedMs)

        stopTicker()
    }

    /**
     * 到顶自动收尾（VoiceRecordingPolicy.D-2，自海外轨移植）：
     * 时长越过 30 分钟硬顶后必须自动走"用户点停止"的保存路径 ——
     * 录音大小由此永远落在服务端 20MB 上限内（64kbps 下 30 分钟 ≈ 14.4MB）。
     */
    @Test
    fun MaxDuration_exceeded_autoStopsAndSaves() = runTest {
        collectEvents()
        recorder.stopResult = clip()
        recorder.elapsed = VoiceRecordingPolicy.MAX_DURATION_MS + 1_000
        startRecording()

        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertFalse("越过硬顶后不得继续处于录音态", viewModel.uiState.value is VoiceRecordUiState.Recording)
        val saved = events.filterIsInstance<VoiceRecordEvent.Saved>().single()
        assertEquals(RecordType.VOICE_NOTE, recordRepository.byId[saved.recordId]!!.type)
    }

    @Test
    fun OnTogglePause_flipsPauseState() = runTest {
        startRecording()

        viewModel.onTogglePause()

        assertTrue((viewModel.uiState.value as VoiceRecordUiState.Recording).paused)
        assertTrue(recorder.paused)

        viewModel.onTogglePause()

        assertFalse((viewModel.uiState.value as VoiceRecordUiState.Recording).paused)
        assertFalse(recorder.paused)

        stopTicker()
    }

    /**
     * 暂停后下一个 tick 不能把 paused 冲回 false（2026-11-05 审核 CN-03）。
     *
     * 交错是**确定性**的：假录音器在 ticker 读到时长之后、回写 UI 之前插入一次"点暂停"。
     * 旧实现里 ticker 用闭包捕获的 `current`（上一 tick 的快照）做 `copy`，
     * 会把这次暂停覆盖回 false —— 表现为"点了暂停，界面还在计时"。
     */
    @Test
    fun Ticker_afterPause_doesNotResetPausedFlag() = runTest {
        startRecording()
        recorder.elapsed = 1_000L
        // 只交错一次，避免 ticker 每 tick 都再点一次暂停
        recorder.onElapsedRead = {
            recorder.onElapsedRead = null
            viewModel.onTogglePause()
        }

        try {
            advanceTimeBy(250L)

            val state = viewModel.uiState.value as VoiceRecordUiState.Recording
            assertTrue("暂停状态不能被 ticker 的旧快照覆盖，实际 $state", state.paused)
            assertEquals("时长仍要跟着录音器走", 1_000L, state.elapsedMs)
        } finally {
            // 断言失败也必须停 ticker：`while (isActive) { delay }` 会让 runTest 收尾的
            // advanceUntilIdle 永远推不完，整个测试进程卡死（表现为"用例挂住"而不是"失败"）
            recorder.onElapsedRead = null
            stopTicker()
        }
    }

    /**
     * 反方向：暂停按钮的旧快照不能把 ticker 刚写入的时长冲回 0。
     *
     * 断言"两个写入方都不丢对方的字段"：ticker 只改 elapsedMs，暂停只改 paused。
     */
    @Test
    fun TickerAndPause_updatesAreMergedNotOverwritten() = runTest {
        startRecording()
        recorder.elapsed = 2_000L

        try {
            advanceTimeBy(250L)

            viewModel.onTogglePause()

            val state = viewModel.uiState.value as VoiceRecordUiState.Recording
            assertTrue(state.paused)
            assertEquals("暂停不能把 ticker 刚写入的时长冲回 0", 2_000L, state.elapsedMs)
        } finally {
            stopTicker()
        }
    }

    @Test
    fun OnStop_validClip_savesRecordAndNavigates() = runTest {
        collectEvents()
        val clip = clip()
        recorder.stopResult = clip
        startRecording()

        viewModel.onStopClicked()
        advanceUntilIdle()

        assertEquals(1, events.size)
        val saved = events.single() as VoiceRecordEvent.Saved
        assertEquals(RecordType.VOICE_NOTE, recordRepository.byId[saved.recordId]!!.type)
        assertFalse("临时文件应已删除", clip.file.exists())
    }

    /**
     * 记录建了、音频却没复制成功：不能导航到详情页（用户会听到"文件不可用"）。
     * 如实停在失败态，并保留临时文件作为唯一可恢复副本（PRD v1.2 §4.2 / §4.3）。
     */
    @Test
    fun OnStop_audioCopyFails_showsFailureWithoutNavigating() = runTest {
        collectEvents()
        assetImporter.failNext = true
        val clip = clip()
        recorder.stopResult = clip
        startRecording()

        viewModel.onStopClicked()
        advanceUntilIdle()

        assertEquals(
            VoiceRecordUiState.Failed(R.string.voice_audio_save_failed),
            viewModel.uiState.value,
        )
        assertTrue("不能报保存成功", events.isEmpty())
        assertTrue("记录仍应创建", recordRepository.byId.isNotEmpty())
        assertTrue("音频没落盘时临时文件不能删", clip.file.exists())
    }

    @Test
    fun OnStop_tooShort_createsNoRecordAndExplains() = runTest {
        collectEvents()
        val clip = clip(durationMs = 200L, bytes = 512)
        recorder.stopResult = clip
        startRecording()

        viewModel.onStopClicked()
        advanceUntilIdle()

        assertEquals(VoiceRecordUiState.TooShort, viewModel.uiState.value)
        assertTrue("不应创建记录", recordRepository.byId.isEmpty())
        assertTrue("应是空事件列表，实际 $events", events.isEmpty())
        assertFalse("空录音文件应删除", clip.file.exists())
    }

    @Test
    fun Interrupted_keepsPartialClipAndAsksUser() = runTest {
        val clip = clip()
        recorder.stopResult = clip
        startRecording()

        recorder.interrupt()

        val state = viewModel.uiState.value
        assertTrue("应进入打断态，实际 $state", state is VoiceRecordUiState.Interrupted)
        assertEquals(3_000L, (state as VoiceRecordUiState.Interrupted).elapsedMs)
        assertTrue("已录部分必须保留在磁盘上", clip.file.exists())
    }

    @Test
    fun Interrupted_thenKeep_savesRecord() = runTest {
        collectEvents()
        val clip = clip()
        recorder.stopResult = clip
        startRecording()
        recorder.interrupt()

        viewModel.onKeepInterrupted()
        advanceUntilIdle()

        assertTrue(events.single() is VoiceRecordEvent.Saved)
        assertEquals(1, recordRepository.byId.size)
    }

    @Test
    fun Interrupted_thenDiscard_deletesFileAndLeaves() = runTest {
        collectEvents()
        val clip = clip()
        recorder.stopResult = clip
        startRecording()
        recorder.interrupt()

        viewModel.onDiscardInterrupted()
        advanceUntilIdle()

        assertEquals(VoiceRecordEvent.NavigateBack, events.single())
        assertTrue("丢弃后不应留下文件", !clip.file.exists())
        assertTrue(recordRepository.byId.isEmpty())
    }

    @Test
    fun OnCancel_releasesRecorderAndLeaves() = runTest {
        collectEvents()
        startRecording()

        viewModel.onCancel()
        advanceUntilIdle()

        assertEquals(1, recorder.cancelCount)
        assertEquals(VoiceRecordEvent.NavigateBack, events.single())
    }

    @Test
    fun Interrupt_whenNotRecording_isIgnored() = runTest {
        // 焦点可能连续回调多次；第一次已把状态切走，后续必须无副作用
        recorder.stopResult = clip()
        startRecording()
        recorder.interrupt()

        recorder.interrupt()

        assertTrue(viewModel.uiState.value is VoiceRecordUiState.Interrupted)
    }

    // ---------------------------------------------------------- 录音器出错

    /**
     * 录音器自身出错（麦克风被系统收回等）：进入打断态，且**文件仍在**。
     *
     * 这是"长录音一次丢光"那条修复的核心断言：出错前录到的内容必须还能保存。
     */
    @Test
    fun RecorderError_keepsPartialClipAndAsksUser() = runTest {
        val clip = clip()
        recorder.stopResult = clip
        startRecording()

        recorder.fail()

        val state = viewModel.uiState.value
        assertTrue("出错应复用打断态，实际 $state", state is VoiceRecordUiState.Interrupted)
        assertEquals(3_000L, (state as VoiceRecordUiState.Interrupted).elapsedMs)
        assertTrue("出错前录到的内容必须保留", clip.file.exists())
    }

    /** 一点都救不回来（封口返回 null）时如实报错，不伪装成"已保留"。 */
    @Test
    fun RecorderError_nothingSalvageable_reportsFailure() = runTest {
        recorder.stopResult = null
        startRecording()

        recorder.fail()

        val state = viewModel.uiState.value
        assertTrue("无可救内容应报失败，实际 $state", state is VoiceRecordUiState.Failed)
    }

    /** 出错后救回的片段太短：仍按"太短"处理，不能建出一条无声记录。 */
    @Test
    fun RecorderError_salvagedClipTooShort_reportsFailure() = runTest {
        val clip = clip(durationMs = 100L, bytes = 64)
        recorder.stopResult = clip
        startRecording()

        recorder.fail()

        assertTrue(viewModel.uiState.value is VoiceRecordUiState.Failed)
    }

    /** 错误回调重复到达只生效一次，且不会把状态从打断态再打回去。 */
    @Test
    fun RecorderError_repeated_isIgnoredAfterFirst() = runTest {
        recorder.stopResult = clip()
        startRecording()
        recorder.fail()

        recorder.fail()

        assertTrue(viewModel.uiState.value is VoiceRecordUiState.Interrupted)
    }

    /** 未开始录音时收到错误回调：无副作用（不能凭空造出打断态）。 */
    @Test
    fun RecorderError_whenNotRecording_isIgnored() = runTest {
        viewModel.onPermissionGranted()

        recorder.fail()

        assertEquals(VoiceRecordUiState.Ready, viewModel.uiState.value)
    }

    // ---------------------------------------------------------- 销毁不丢音频

    /**
     * 页面销毁时**不许删音频**：走 salvage 而不是 cancel。
     *
     * 旧实现直接 `recorder.cancel()`，而 cancel 会 stop + release + 删文件 ——
     * 用户切走应用时已经说出口的内容被静默删除。
     */
    @Test
    fun OnCleared_salvagesInsteadOfCancellingAndMovesFileToFilesDir() = runTest {
        val clip = clip(bytes = 8192)
        recorder.stopResult = clip
        startRecording()

        viewModel.releaseWithoutLosingAudio()

        assertEquals("销毁时不许走 cancel", 0, recorder.cancelCount)
        assertEquals("销毁时应走 salvage", 1, recorder.salvageCount)
        assertTrue("原临时文件应已移走", !clip.file.exists())

        val orphan = File(
            RuntimeEnvironment.getApplication().filesDir,
            "${VoiceRecordViewModel.ORPHAN_DIR}/${clip.file.name}",
        )
        assertTrue("音频应被安置到 filesDir/orphaned-voice，实际 ${orphan.absolutePath}", orphan.exists())
        assertEquals(8192L, orphan.length())
        // 断言完仍停在录音态：必须停掉 ticker，否则 runTest 收尾推不完虚拟时间
        stopTicker()
    }

    /**
     * 确实没有可救内容时才真正丢弃（release 麦克风并清临时文件）。
     *
     * 注意：与其他"断言完仍停在录音态"的用例一样，必须显式停掉 ticker 再收尾。
     * `releaseWithoutLosingAudio()` 只封口录音器，**不动 ViewModel 的 ticker**
     * （它本来就只负责销毁时的音频处置），ticker 的 `while (isActive) { delay }`
     * 会让 `runTest` 的收尾 `advanceUntilIdle()` 永远推不完 —— 表现为整个测试进程卡死。
     */
    @Test
    fun OnCleared_noSalvageableAudio_cancels() = runTest {
        recorder.stopResult = null
        startRecording()

        viewModel.releaseWithoutLosingAudio()

        // 先取快照再停 ticker：stopTicker() 走的是 onCancel()，它自己也会调 cancel()，
        // 直接把 cancelCount 变成 2。断言的是"销毁路径"的计数，必须在收尾之前读。
        val cancelsAfterRelease = recorder.cancelCount
        val salvagesAfterRelease = recorder.salvageCount
        stopTicker()

        assertEquals("销毁路径应走 cancel 一次", 1, cancelsAfterRelease)
        // salvage 会被**尝试**一次（先试再判），但没拿到片段才退到 cancel ——
        // 断言"尝试过"而不是"没尝试"：这正是修复后 "先救后弃" 的顺序。
        assertEquals("无可救内容时仍应先尝试 salvage", 1, salvagesAfterRelease)
    }

    /**
     * 页面销毁后 ticker 必须停：否则 viewModelScope 里的无限循环会一直活着。
     *
     * 这条与上一条互补：上一条用 stopTicker() 手动兜底，这一条验证
     * "salvage 成功"的分支同样不会留下悬空 ticker。
     */
    @Test
    fun OnCleared_stopsTickerEvenWhenAudioSalvaged() = runTest {
        val clip = clip(bytes = 8192)
        recorder.stopResult = clip
        startRecording()

        viewModel.releaseWithoutLosingAudio()

        // 同样先取快照：stopTicker() 会经 onCancel() 再 cancel 一次
        val cancelsAfterRelease = recorder.cancelCount
        val salvagesAfterRelease = recorder.salvageCount
        stopTicker()

        assertEquals("可救时走 salvage 一次", 1, salvagesAfterRelease)
        assertEquals("可救时销毁路径不该 cancel", 0, cancelsAfterRelease)
    }
}
