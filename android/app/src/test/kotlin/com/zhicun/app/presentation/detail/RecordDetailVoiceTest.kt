package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页语音块（M9）：播放器、进度、转写文本、失败态。
 *
 * 关键验收点：**转写失败/音频缺失都不能让"音频仍可播放"这条信息丢失**
 * （M9 验收标准：转写失败时原音频仍可播放，显示失败状态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RecordDetailVoiceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        status: ProcessingStatus,
        derived: List<com.zhicun.app.domain.model.DerivedContent> = emptyList(),
        withAudio: Boolean = true,
        assets: List<com.zhicun.app.domain.model.Asset>? = null,
        playback: VoicePlaybackState = VoicePlaybackState(),
        onPlayPause: (String?) -> Unit = {},
        onRetry: () -> Unit = {},
        /** 2026-10-29 修复 P2：转写区失败标志改由 TRANSCRIPTION 任务状态下发。 */
        transcribeJobFailed: Boolean = false,
    ) {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.VOICE_NOTE,
                            rawText = null,
                            processingStatus = status,
                        ),
                        assets = assets ?: if (withAudio) {
                            listOf(testAsset(id = "a1", mimeType = "audio/mp4", fileSize = 51_200L))
                        } else {
                            emptyList()
                        },
                        derived = derived,
                        transcribeJobFailed = transcribeJobFailed,
                    ),
                    onBack = {},
                    onRetry = onRetry,
                    playback = playback,
                    onPlayPause = onPlayPause,
                )
            }
        }
    }

    @Test
    fun VoiceNoteWithTranscription_showsPlayButtonAndText() {
        render(
            status = ProcessingStatus.DONE,
            derived = listOf(
                testDerived(
                    id = "r1:TRANSCRIPTION",
                    recordId = "r1",
                    contentType = DerivedType.TRANSCRIPTION,
                    content = "今天下午三点和产品对齐一下范围",
                ),
            ),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_PLAY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_TEXT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("今天下午三点和产品对齐一下范围").assertExists()
    }

    @Test
    fun PlayButton_invokesCallback() {
        var clicked = 0
        render(status = ProcessingStatus.DONE, onPlayPause = { clicked++ })

        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_PLAY).performScrollTo().performClick()

        assertEquals(1, clicked)
    }

    @Test
    fun Playing_showsPauseLabelAndPosition() {
        render(
            status = ProcessingStatus.DONE,
            playback = VoicePlaybackState(
                isPlaying = true,
                positionMs = 12_000L,
                durationMs = 35_000L,
            ),
        )

        composeRule.onNodeWithText("暂停").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("00:12 / 00:35").assertExists()
    }

    @Test
    fun TranscriptionFailed_showsFailureAndRetryAlongsidePlayback() {
        var retried = 0
        render(
            status = ProcessingStatus.FAILED,
            onRetry = { retried++ },
            // 2026-10-29 修复 P2：失败标志现在精确来自 TRANSCRIPTION 任务状态
            transcribeJobFailed = true,
        )

        composeRule.onNodeWithText("转写失败，录音仍可播放").performScrollTo().assertExists()
        // 播放器必须仍在：转写失败不影响听
        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_PLAY).assertExists()

        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_RETRY).performScrollTo().performClick()
        assertEquals(1, retried)
    }

    /**
     * 2026-10-29 修复 P2 回归：记录级失败（比如随后的摘要"没有正文"终态）不得
     * 污染转写区 —— 转写任务本身成功（哪怕是空文本）时，不能显示"转写失败"。
     */
    @Test
    fun RecordFailedButTranscriptionSucceeded_showsTextNotFailure() {
        render(
            status = ProcessingStatus.FAILED,
            derived = listOf(
                testDerived(
                    id = "r1:TRANSCRIPTION",
                    recordId = "r1",
                    contentType = DerivedType.TRANSCRIPTION,
                    content = "转写其实成功了",
                ),
            ),
        )

        composeRule.onNodeWithText("转写失败，录音仍可播放").assertDoesNotExist()
        composeRule.onNodeWithText("转写其实成功了").performScrollTo().assertExists()
    }

    @Test
    fun TranscriptionEmpty_showsEmptyHint() {
        render(
            status = ProcessingStatus.DONE,
            derived = listOf(
                testDerived(
                    id = "r1:TRANSCRIPTION",
                    recordId = "r1",
                    contentType = DerivedType.TRANSCRIPTION,
                    content = "",
                ),
            ),
        )

        composeRule.onNodeWithText("这段录音里没有识别到语音内容")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun MissingAudioAsset_explainsUnavailable() {
        render(status = ProcessingStatus.DONE, withAudio = false)

        composeRule.onNodeWithText("音频文件不可用（可能已被清理）")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ------------------------------------------------- 多音频分段（2026-10-29）

    private fun twoAudios() = listOf(
        testAsset(id = "a1", mimeType = "audio/mp4", fileName = "会议上半场.m4a", orderIndex = 0),
        testAsset(id = "a2", mimeType = "audio/mp4", fileName = "会议下半场.m4a", orderIndex = 1),
    )

    private fun twoTranscripts() = listOf(
        testDerived(
            id = "r1:TRANSCRIPTION:a1",
            recordId = "r1",
            contentType = DerivedType.TRANSCRIPTION,
            content = "上半场的转写",
            assetId = "a1",
        ),
        testDerived(
            id = "r1:TRANSCRIPTION:a2",
            recordId = "r1",
            contentType = DerivedType.TRANSCRIPTION,
            content = "下半场的转写",
            assetId = "a2",
        ),
    )

    /** 两段录音各有标题与转写：用户要看得出哪段是哪个文件。 */
    @Test
    fun TwoAudios_showsPerAudioHeadingAndTranscript() {
        render(
            status = ProcessingStatus.DONE,
            assets = twoAudios(),
            derived = twoTranscripts(),
        )

        composeRule.onNodeWithText("音频 1 · 会议上半场.m4a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("音频 2 · 会议下半场.m4a").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上半场的转写").performScrollTo().assertExists()
        composeRule.onNodeWithText("下半场的转写").performScrollTo().assertExists()
    }

    /** 点第几段就播第几段：回调里带的是该附件的 id，不是"第一段"。 */
    @Test
    fun TwoAudios_playButtonReportsItsAssetId() {
        var played: String? = null
        render(
            status = ProcessingStatus.DONE,
            assets = twoAudios(),
            derived = twoTranscripts(),
            onPlayPause = { played = it },
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.voicePlayTag("a2"))
            .performScrollTo()
            .performClick()

        assertEquals("a2", played)
    }

    /** 进度只跟着正在播放的那一段走，另一段仍显示"播放"。 */
    @Test
    fun TwoAudios_progressOnlyOnActiveSegment() {
        render(
            status = ProcessingStatus.DONE,
            assets = twoAudios(),
            derived = twoTranscripts(),
            playback = VoicePlaybackState(
                isPlaying = true,
                positionMs = 5_000L,
                durationMs = 30_000L,
                activeAssetId = "a1",
            ),
        )

        composeRule.onNodeWithText("暂停").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("播放").assertCountEquals(1)
        composeRule.onNodeWithText("00:05 / 00:30").assertExists()
    }

    /**
     * 升级前的老记录只有一条 assetId 为空的转写：补跑之前保持单卡，
     * 不能出现"N 段正在转写 + 一段旧文字"。
     */
    @Test
    fun TwoAudiosWithLegacyTranscript_staysSingleCardUntilRerun() {
        render(
            status = ProcessingStatus.DONE,
            assets = twoAudios(),
            derived = listOf(
                testDerived(
                    id = "r1:TRANSCRIPTION",
                    recordId = "r1",
                    contentType = DerivedType.TRANSCRIPTION,
                    content = "旧的整段转写",
                ),
            ),
        )

        composeRule.onNodeWithTag(RecordDetailTestTags.VOICE_TEXT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("旧的整段转写").assertExists()
        composeRule.onAllNodesWithTag(RecordDetailTestTags.voiceTextTag("a1")).assertCountEquals(0)
    }

    @Test
    fun NonVoiceRecord_hidesVoiceSection() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r2", type = RecordType.TEXT_NOTE),
                        assets = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("语音").assertDoesNotExist()
    }
}
