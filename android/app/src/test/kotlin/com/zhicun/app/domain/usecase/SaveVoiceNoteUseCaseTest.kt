package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.audio.RecordedClip
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeRecordRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 录音保存用例（M9）。
 *
 * 重点不是"能存进去"（那是 M4/M5 已验过的 SaveRecordUseCase），而是 M9 / PRD v1.2 §4.2 特有的事：
 * 1. 音频附件必须被判成 `VOICE_NOTE`（判成 FILE 就不会有转写任务）；
 * 2. 临时文件在保存成功后删除，失败时保留；
 * 3. 空文件不允许创建记录；
 * 4. 录音开始/结束时间与实际时长随记录落库，附件失败时如实返回 `audioSaved = false`。
 */
class SaveVoiceNoteUseCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val recordRepository = FakeRecordRepository()
    private val assetRepository = FakeAssetRepository()
    private val importer = FakeAssetImporter()

    private fun useCase(): SaveVoiceNoteUseCase = SaveVoiceNoteUseCase(
        SaveRecordUseCase(
            recordRepository = recordRepository,
            copyAsset = CopyAssetToPrivateDirUseCase(importer, assetRepository),
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
    )

    private fun clipOf(
        bytes: Int = 4096,
        captureStartedAt: Long? = null,
        captureEndedAt: Long? = null,
    ): RecordedClip {
        val file = tempFolder.newFile("voice-${System.nanoTime()}.m4a")
        file.writeBytes(ByteArray(bytes) { 1 })
        return RecordedClip(
            file = file,
            durationMs = 3_000L,
            sizeBytes = bytes.toLong(),
            captureStartedAt = captureStartedAt,
            captureEndedAt = captureEndedAt,
        )
    }

    @Test
    fun Invoke_audioClip_createsVoiceNoteRecord() = runTest {
        val clip = clipOf()

        val result = useCase()(clip, now = 1_000L)

        val record = recordRepository.byId[result.recordId]!!
        assertEquals(RecordType.VOICE_NOTE, record.type)
        assertEquals("录音不应产生文字原文", null, record.rawText)
        assertEquals(1_000L, record.createdAt)
        assertTrue("附件复制成功", result.audioSaved)
    }

    @Test
    fun Invoke_audioClip_storesAssetWithAudioMime() = runTest {
        val clip = clipOf()

        val result = useCase()(clip)

        val asset = assetRepository.getByRecordId(result.recordId).single()
        assertEquals("audio/mp4", asset.mimeType)
        assertTrue("附件应标记为已落盘", asset.originalUri!!.startsWith("file://"))
    }

    /** PRD v1.2 §4.2：开始时间、结束时间、实际录音时长必须随记录保存。 */
    @Test
    fun Invoke_clipWithWallClock_persistsCaptureWindow() = runTest {
        val clip = clipOf(captureStartedAt = 1_000L, captureEndedAt = 4_000L)

        val result = useCase()(clip)

        val record = recordRepository.byId[result.recordId]!!
        assertEquals(1_000L, record.captureStartedAt)
        assertEquals(4_000L, record.captureEndedAt)
        assertEquals("实际录音时长来自 clip.durationMs", 3_000L, record.captureDurationMs)
    }

    @Test
    fun Invoke_success_deletesTemporaryFile() = runTest {
        val clip = clipOf()

        useCase()(clip)

        assertFalse("复制进私有目录后临时文件应删除", clip.file.exists())
    }

    @Test
    fun Invoke_copyFails_keepsTemporaryFileForRecovery() = runTest {
        val clip = clipOf()
        importer.failNext = true

        val result = useCase()(clip)

        // 记录仍然创建（PRD：附件失败也要保留记录），但必须如实告诉调用方音频没落盘
        assertTrue(recordRepository.byId.containsKey(result.recordId))
        assertFalse("音频没复制成功就不能报成功", result.audioSaved)
        assertTrue("复制失败时不应删除临时文件", clip.file.exists())
    }

    @Test
    fun Invoke_emptyFile_rejectsWithoutCreatingRecord() = runTest {
        val empty = tempFolder.newFile("empty.m4a")
        val clip = RecordedClip(file = empty, durationMs = 0L, sizeBytes = 0L)

        var thrown = false
        try {
            useCase()(clip)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }

        assertTrue("空录音必须被拒绝", thrown)
        assertTrue(recordRepository.stored.value.isEmpty())
    }

    @Test
    fun Invoke_missingFile_rejects() = runTest {
        val missing = File(tempFolder.root, "not-there.m4a")
        val clip = RecordedClip(file = missing, durationMs = 3_000L, sizeBytes = 1024L)

        var thrown = false
        try {
            useCase()(clip)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }

        assertTrue(thrown)
    }
}
