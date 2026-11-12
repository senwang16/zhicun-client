package com.zhicun.app.presentation.voice

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.zhicun.app.R
import com.zhicun.app.presentation.common.formatDuration

@Composable
internal fun InterruptedDialog(
    elapsedMs: Long,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        modifier = Modifier.testTag(VoiceRecordTestTags.INTERRUPT_DIALOG),
        title = { Text(text = stringResource(R.string.voice_interrupted_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.voice_interrupted_message,
                    formatDuration(elapsedMs),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onKeep,
                modifier = Modifier.testTag(VoiceRecordTestTags.INTERRUPT_KEEP),
            ) {
                Text(text = stringResource(R.string.voice_interrupted_keep))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.testTag(VoiceRecordTestTags.INTERRUPT_DISCARD),
            ) {
                Text(text = stringResource(R.string.voice_interrupted_discard))
            }
        },
    )
}
