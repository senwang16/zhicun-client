package com.zhicun.app.presentation.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R

/** 测试与无障碍定位用的 tag。 */
object VoiceRecordTestTags {
    const val TIME = "voice_time"
    const val START = "voice_start"
    const val STOP = "voice_stop"
    const val PAUSE = "voice_pause"
    const val DISCARD = "voice_discard"
    const val CANCEL = "voice_cancel"
    const val INTERRUPT_DIALOG = "voice_interrupt_dialog"
    const val INTERRUPT_KEEP = "voice_interrupt_keep"
    const val INTERRUPT_DISCARD = "voice_interrupt_discard"
    const val TOO_SHORT = "voice_too_short"
    const val PERMISSION = "voice_permission"
}

/**
 * 录音页（有状态壳，M9）。
 *
 * ## 权限与开录时机（2026-10-24 用户反馈）
 * 进入只检查权限，**不自动开麦**。授权后进入 [VoiceRecordUiState.Ready]，
 * 由用户按下「开始录音」才真正开始 —— 之前点进来看一眼也会录起来，退出还留临时文件。
 * 权限被拒时**不崩溃也不循环弹窗**，只给说明与「去设置」入口。
 *
 * ## 打断
 * 打断对话框由状态驱动（[VoiceRecordUiState.Interrupted]），而不是这里自己维护布尔值 ——
 * 状态机在 ViewModel 里，界面只负责把状态画出来。
 */
@Composable
fun VoiceRecordScreen(
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceRecordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
    }

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VoiceRecordEvent.Saved -> onSaved(event.recordId)
                VoiceRecordEvent.NavigateBack -> onBack()
            }
        }
    }

    // 录音中按返回：直接丢弃（ViewModel 会 release 麦克风并删临时文件）。
    // 页面里的「放弃本次录音」是同一个语义的显式入口。
    BackHandler(onBack = viewModel::onCancel)

    VoiceRecordContent(
        uiState = uiState,
        onStart = viewModel::onStartClicked,
        onTogglePause = viewModel::onTogglePause,
        onStop = viewModel::onStopClicked,
        onCancel = viewModel::onCancel,
        onKeepInterrupted = viewModel::onKeepInterrupted,
        onDiscardInterrupted = viewModel::onDiscardInterrupted,
        onTooShortAcknowledged = viewModel::onTooShortAcknowledged,
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        modifier = modifier,
    )
}

/**
 * 录音页（无状态）。
 *
 * 所有分支都在这里画出来，UI 测试可以直接喂状态，不必搭 Hilt + 真麦克风。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecordContent(
    uiState: VoiceRecordUiState,
    onStart: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onKeepInterrupted: () -> Unit,
    onDiscardInterrupted: () -> Unit,
    onTooShortAcknowledged: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.voice_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag(VoiceRecordTestTags.CANCEL),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.voice_cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (uiState) {
                VoiceRecordUiState.Preparing -> LoadingBody(
                    text = stringResource(R.string.voice_preparing),
                )

                VoiceRecordUiState.Ready -> ReadyBody(onStart = onStart)

                is VoiceRecordUiState.Recording -> RecordingBody(
                    uiState = uiState,
                    onTogglePause = onTogglePause,
                    onStop = onStop,
                    onDiscard = onCancel,
                )

                is VoiceRecordUiState.Interrupted -> Text(
                    // 状态被对话框接管，这里留一句兜底文案，避免"对话框消失后空白页"
                    text = stringResource(R.string.voice_interrupted_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                VoiceRecordUiState.Saving -> LoadingBody(
                    text = stringResource(R.string.voice_saving),
                )

                VoiceRecordUiState.TooShort -> Text(
                    text = stringResource(R.string.voice_too_short_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(VoiceRecordTestTags.TOO_SHORT),
                )

                VoiceRecordUiState.PermissionDenied -> PermissionDeniedBody(
                    onOpenSettings = onOpenSettings,
                    onCancel = onCancel,
                )

                is VoiceRecordUiState.Failed -> FailedBody(
                    messageRes = uiState.messageRes,
                    onCancel = onCancel,
                )
            }
        }
    }

    if (uiState is VoiceRecordUiState.Interrupted) {
        InterruptedDialog(
            elapsedMs = uiState.elapsedMs,
            onKeep = onKeepInterrupted,
            onDiscard = onDiscardInterrupted,
        )
    }

    if (uiState is VoiceRecordUiState.TooShort) {
        AlertDialog(
            onDismissRequest = onTooShortAcknowledged,
            modifier = Modifier.testTag(VoiceRecordTestTags.TOO_SHORT),
            title = { Text(text = stringResource(R.string.voice_too_short_title)) },
            text = { Text(text = stringResource(R.string.voice_too_short_message)) },
            confirmButton = {
                TextButton(onClick = onTooShortAcknowledged) {
                    Text(text = stringResource(R.string.voice_dialog_ok))
                }
            },
        )
    }
}
