package com.zhicun.app.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.presentation.common.formatDuration

/**
 * 语音块（M9）：播放器 + 转写文本。
 *
 * 播放器只做一个按钮（播放/暂停）与一行进度文字，不做拖动条：
 * 语音记录通常几十秒到几分钟，"跳着听"是少数场景，拖拽条会引入手势冲突
 * 与额外的状态同步（拖动中不能跟随后台进度）。需要时再补。
 *
 * **音频不可用不是失败**：文件可能被用户清理，这时只是"听不了"，
 * 转写文本仍然可看，所以两者分开呈现，不互相牵连。
 *
 * 多音频（2026-10-29）：音频 ≥ 2 且转写确实按附件落库时，逐段一张卡，
 * 进度只显示在正在播放的那一段。单音频记录的视觉与改版前完全一致。
 */
@Composable
internal fun VoiceBlock(
    record: Record,
    assets: List<Asset>,
    derived: List<DerivedContent>,
    playback: VoicePlaybackState,
    onPlayPause: (String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 云端为什么不可用（转写是云端任务）：非空且记录为 LOCAL_ONLY 时如实说明"没上传"。 */
    cloudBlockReason: CloudUploadGate.Reason? = null,
    /** 重试是否真的会做事（F-4）：语音的任务全是云端任务，云端被挡时为 false。 */
    canRetry: Boolean = true,
    /**
     * 转写任务自身是否失败（2026-10-29 修复 P2）。以前用记录级
     * `processingStatus.hasFailure`，摘要失败（PARTIAL）会把成功的转写误标成失败。
     */
    transcribeFailed: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    /** 复制转写文本（2026-10-26 用户裁决：每个模块各自复制）。 */
    onCopyText: ((String) -> Unit)? = null,
) {
    val audios = assets.filter { it.isAudio }
    val rows = derived.filter { it.contentType == DerivedType.TRANSCRIPTION }
    val audioIds = audios.map { it.id }.toSet()
    // 与 OCR 同一判据：只有确实按附件落过结果、且音频 ≥ 2 才分段。
    // 老数据（转写行 assetId 为空）在补跑前保持单卡，避免出现"N 段正在转写 + 一段旧文字"。
    val perAssetRows = rows.filter { it.assetId != null && it.assetId in audioIds }
    val multi = audios.size >= 2 && perAssetRows.isNotEmpty()

    val blockedText = if (record.processingStatus == ProcessingStatus.LOCAL_ONLY) {
        cloudBlockedText(cloudBlockReason)
    } else {
        null
    }

    // 转写被 TranscriptionPolicy 跳过时，Worker 会落一条"有说明、无正文"的行。
    // 不把它显示出来，界面会一直停在"正在转写…"（任务其实已成功结束）。
    val policyHint = rows.firstOrNull()
        ?.takeIf { it.content.isNullOrBlank() && !it.errorMessage.isNullOrBlank() && !transcribeFailed }
        ?.errorMessage

    if (!multi) {
        val audio = audios.firstOrNull()
        VoiceSingleLayout(
            audio = audio,
            transcript = rows.firstOrNull(),
            playback = playback,
            onPlayPause = { onPlayPause(audio?.id) },
            onRetry = onRetry,
            blockedText = blockedText ?: policyHint,
            canRetry = canRetry,
            transcribeFailed = transcribeFailed,
            onOpenSettings = onOpenSettings,
            onCopyText = onCopyText,
            modifier = modifier,
        )
        return
    }

    val legacyRow = rows.firstOrNull { it.assetId == null }
    val combined = audios.mapNotNull { audio ->
        perAssetRows.firstOrNull { it.assetId == audio.id }?.content?.trim()?.takeIf(String::isNotEmpty)
    } + listOfNotNull(legacyRow?.content?.trim()?.takeIf(String::isNotEmpty))
    val failed = transcribeFailed

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        audios.forEachIndexed { index, audio ->
            VoiceSegmentCard(
                index = index + 1,
                audio = audio,
                row = perAssetRows.firstOrNull { it.assetId == audio.id },
                playback = playback,
                failed = failed,
                onPlayPause = { onPlayPause(audio.id) },
            )
        }
        // 老格式转写（assetId 为空）单独成卡，不冒充某一段音频
        if (legacyRow != null) {
            SegmentSurface(title = stringResource(R.string.detail_voice_legacy_heading)) {
                SegmentedDerivedText(
                    text = legacyRow.content?.trim().orEmpty(),
                    errorMessage = legacyRow.errorMessage,
                    failed = failed,
                    pendingText = stringResource(R.string.detail_voice_transcript_pending),
                    emptyText = stringResource(R.string.detail_voice_transcript_empty),
                    failureText = stringResource(R.string.detail_voice_transcript_failed),
                    testTag = RecordDetailTestTags.VOICE_TEXT,
                )
            }
        }

        val showBlocked = blockedText != null && combined.isEmpty()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onCopyText != null && combined.isNotEmpty()) {
                CopyTextButton(
                    text = combined.joinToString("\n\n"),
                    onCopy = onCopyText,
                    testTag = "${RecordDetailTestTags.VOICE_TEXT}_copy",
                )
            }
            when {
                failed && canRetry -> DetailPillButton(
                    label = stringResource(R.string.detail_voice_transcript_retry),
                    iconRes = R.drawable.ic_action_refresh,
                    onClick = onRetry,
                    modifier = Modifier.testTag(RecordDetailTestTags.VOICE_RETRY),
                )

                // 转写走云端：被挡时"重试"不会真的做事，给设置入口（与 DerivedBlock 同一纪律）
                showBlocked && onOpenSettings != null -> DetailPillButton(
                    label = stringResource(R.string.detail_open_cloud_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag(RecordDetailTestTags.OPEN_SETTINGS),
                )
            }
        }
        if (showBlocked) {
            Text(
                text = blockedText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单音频布局：与多音频之前完全一致，单条录音的记录不会因为这次改版换样子。 */
@Composable
private fun VoiceSingleLayout(
    audio: Asset?,
    transcript: DerivedContent?,
    playback: VoicePlaybackState,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    blockedText: String?,
    canRetry: Boolean,
    transcribeFailed: Boolean,
    onOpenSettings: (() -> Unit)?,
    onCopyText: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (audio == null || audio.localPath.isBlank()) {
                    Text(
                        text = stringResource(R.string.detail_voice_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DetailPillButton(
                            label = stringResource(
                                if (playback.isPlaying) {
                                    R.string.detail_voice_pause
                                } else {
                                    R.string.detail_voice_play
                                },
                            ),
                            iconRes = if (playback.isPlaying) {
                                R.drawable.ic_voice_pause
                            } else {
                                R.drawable.ic_voice_play
                            },
                            onClick = onPlayPause,
                            modifier = Modifier.testTag(RecordDetailTestTags.VOICE_PLAY),
                        )
                        if (playback.durationMs > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.detail_voice_position,
                                    formatDuration(playback.positionMs),
                                    formatDuration(playback.durationMs),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (playback.unavailable) {
                        Text(
                            text = stringResource(R.string.detail_voice_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        DerivedBlock(
            heading = null,
            text = transcript?.content?.trim().orEmpty(),
            partialHint = null,
            failureText = stringResource(R.string.detail_voice_transcript_failed),
            pendingText = stringResource(R.string.detail_voice_transcript_pending),
            emptyText = stringResource(R.string.detail_voice_transcript_empty),
            retryText = stringResource(R.string.detail_voice_transcript_retry),
            retryTag = RecordDetailTestTags.VOICE_RETRY,
            textTag = RecordDetailTestTags.VOICE_TEXT,
            failed = transcribeFailed,
            hasResult = transcript != null,
            onRetry = onRetry,
            // 转写走云端：被闸门挡下时"正在转写…"会永远不结束，必须换成原因说明
            blockedText = blockedText,
            canRetry = canRetry,
            onOpenSettings = onOpenSettings,
            onCopyText = onCopyText,
        )
    }
}

/**
 * 单段音频的子卡（多音频时使用）：播放按钮 + 文件名 + 该段自己的转写。
 *
 * 进度与"不可用"只认领 [VoicePlaybackState.activeAssetId] 指向的那一段：
 * 否则点第一段播放，第二段也会显示进度，用户分不清在放哪一条。
 */
@Composable
private fun VoiceSegmentCard(
    index: Int,
    audio: Asset,
    row: DerivedContent?,
    playback: VoicePlaybackState,
    failed: Boolean,
    onPlayPause: () -> Unit,
) {
    val active = playback.activeAssetId == audio.id
    val name = assetDisplayName(audio)
    val title = if (name != null) {
        stringResource(R.string.detail_voice_segment_title, index, name)
    } else {
        stringResource(R.string.detail_voice_segment_title_untitled, index)
    }
    val playing = active && playback.isPlaying

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DetailPillButton(
                    label = stringResource(
                        if (playing) R.string.detail_voice_pause else R.string.detail_voice_play,
                    ),
                    iconRes = if (playing) R.drawable.ic_voice_pause else R.drawable.ic_voice_play,
                    onClick = onPlayPause,
                    enabled = audio.localPath.isNotBlank(),
                    modifier = Modifier.testTag(RecordDetailTestTags.voicePlayTag(audio.id)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (active && playback.durationMs > 0) {
                        Text(
                            text = stringResource(
                                R.string.detail_voice_position,
                                formatDuration(playback.positionMs),
                                formatDuration(playback.durationMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (active && playback.unavailable) {
                Text(
                    text = stringResource(R.string.detail_voice_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedDerivedText(
                text = row?.content?.trim().orEmpty(),
                errorMessage = row?.errorMessage,
                failed = failed,
                pendingText = stringResource(R.string.detail_voice_transcript_pending),
                emptyText = stringResource(R.string.detail_voice_transcript_empty),
                failureText = stringResource(R.string.detail_voice_transcript_failed),
                testTag = RecordDetailTestTags.voiceTextTag(audio.id),
            )
        }
    }
}
