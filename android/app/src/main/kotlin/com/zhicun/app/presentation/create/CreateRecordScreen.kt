package com.zhicun.app.presentation.create

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.presentation.theme.TypeFile
import com.zhicun.app.presentation.theme.TypeImage

/**
 * 新建页一次可选图片数上限（2026-10-29 修复 P1：原来用单选 [ActivityResultContracts.PickVisualMedia]，
 * 系统相册里长按多选了 N 张也只回传第 1 张，其余静默丢失）。
 * 20 与采集链路"单记录图片上限 20 张"（W-8）对齐；ViewModel 按 uri 去重追加，逐个回调即可。
 */
private const val MAX_IMAGE_PICK = 20

/** 测试与无障碍定位用的 tag。 */
object CreateRecordTestTags {
    const val INPUT = "create_input"
    const val SAVE = "create_save"
    const val CANCEL = "create_cancel"
    const val PICK_IMAGE = "create_pick_image"
    const val PICK_FILE = "create_pick_file"
    const val VOICE = "create_voice"
    const val VOICE_CONFIRM_DIALOG = "create_voice_confirm_dialog"
    const val VOICE_CONFIRM_GO = "create_voice_confirm_go"
    const val VOICE_CONFIRM_CANCEL = "create_voice_confirm_cancel"
    const val ERROR = "create_error"
    const val PASTE_HINT = "create_paste_hint"
    const val DRAFT_NOTE = "create_draft_note"
    const val DISCARD_DIALOG = "create_discard_dialog"
    const val DISCARD_CONFIRM = "create_discard_confirm"
    const val DISCARD_CANCEL = "create_discard_cancel"
}

/**
 * 新建记录页（有状态壳）。
 *
 * 与收件箱/详情页同一模式：壳只负责拿 ViewModel + 订阅状态，布局放在 [CreateRecordContent]，
 * UI 测试可以直接喂状态，不必为了测界面去搭 Hilt。
 *
 * 系统返回键走 [BackHandler] 而不是让系统直接 pop：有未保存内容时先确认，
 * 否则用户随手一划就把正在写的东西弄丢了。
 */
@Composable
fun CreateRecordScreen(
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // M9：语音入口跳独立录音页；默认空实现便于只关心文字/附件的测试
    onVoiceClick: () -> Unit = {},
    /** M13 D-1：剪贴板读不到跳来时为 true，页面顶部给"长按可粘贴"提示。 */
    pasteHint: Boolean = false,
    viewModel: CreateRecordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 图片用系统 Photo Picker（API 33+ 原生；低版本由 ActivityResultContracts 内部回退到
    // ACTION_OPEN_DOCUMENT，无需 GMS，符合 AGENTS.md §2.4 对无 GMS 国产 ROM 的要求）。
    // 多选 contract：回调逐个交给 ViewModel（内部按 uri 去重），上限见 [MAX_IMAGE_PICK]
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGE_PICK),
    ) { uris ->
        uris.forEach { uri ->
            viewModel.onAttachmentPicked(uri.toString(), AttachmentKind.IMAGE)
        }
    }
    // 文件入口（2026-10-24 修）：原来只允许 `application/pdf`，用户在系统选择器里
    // 看不到 PDF 之外的文件，"选文件"实际等于"只能选 PDF"。放开成任意类型之后
    // 真实类型由 MIME 判定（保存时 `SharedContentClassifier` 按 image/audio 前缀分流），
    // 图片仍建议走上面的 Photo Picker（能拿到缩略图与云端原图）。
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onAttachmentPicked(it.toString(), AttachmentKind.FILE) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateRecordEvent.Saved -> onSaved(event.recordId)
                CreateRecordEvent.NavigateBack -> onBack()
                // M21：录音入口先经 ViewModel 判断有没有未保存内容，
                // 确认后仍然走既有的跳转回调，录音保存流程完全不变
                CreateRecordEvent.NavigateToVoice -> onVoiceClick()
            }
        }
    }

    BackHandler(onBack = viewModel::onBackRequested)

    CreateRecordContent(
        uiState = uiState,
        pasteHint = pasteHint,
        onTextChange = viewModel::onTextChange,
        onSave = viewModel::save,
        onCancel = viewModel::onBackRequested,
        onPickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onPickFile = { filePicker.launch(arrayOf("*/*")) },
        onVoiceClick = viewModel::onVoiceRequested,
        onVoiceConfirm = viewModel::onVoiceConfirmed,
        onVoiceDismiss = viewModel::onVoiceDismissed,
        onRemoveAttachment = viewModel::onAttachmentRemoved,
        onDiscardConfirm = viewModel::onDiscardConfirmed,
        onDiscardDismiss = viewModel::onDiscardDismissed,
        modifier = modifier,
    )
}

/**
 * 新建记录页（无状态）。
 *
 * 所有交互都是回调，页面不认识 ViewModel、也不认识路由，便于单独渲染与测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRecordContent(
    uiState: CreateRecordUiState,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoiceClick: () -> Unit,
    onVoiceConfirm: () -> Unit,
    onVoiceDismiss: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDiscardConfirm: () -> Unit,
    onDiscardDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** M13 D-1：剪贴板读不到跳来时显示"长按可粘贴"提示条。放最后，默认 false 不打扰既有调用。 */
    pasteHint: Boolean = false,
) {
    // 功能清单 §1.2：进入编辑页默认聚焦正文，不先问类型/分类/标签
    val textFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        textFocusRequester.requestFocus()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.create_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag(CreateRecordTestTags.CANCEL),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.create_cancel),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = onSave,
                        enabled = uiState.canSave,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .height(40.dp)
                            .testTag(CreateRecordTestTags.SAVE),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.isSaving) R.string.create_saving else R.string.create_save,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 剪贴板读不到时（M13 D-1 折中）：内容还在系统剪贴板里，告诉用户"长按可粘贴"。
            // 用常驻提示条而不是 Snackbar——用户还要切输入法再长按，Snackbar 几秒就没了。
            // 改版配色：品牌青绿软底（与详情页 AI 区块同一观感）
            if (pasteHint) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CreateRecordTestTags.PASTE_HINT),
                ) {
                    Text(
                        text = stringResource(R.string.create_paste_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = stringResource(R.string.create_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))
            // 输入区做成一张卡片：旧版是一个贴着屏幕边的空输入框，看起来像调试控件
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = uiState.text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CreateRecordTestTags.INPUT)
                        .focusRequester(textFocusRequester)
                        .padding(4.dp),
                    placeholder = { Text(text = stringResource(R.string.create_text_hint)) },
                    minLines = 7,
                    maxLines = 20,
                    shape = RoundedCornerShape(20.dp),
                    // 卡片已经承担了边框与底色，输入框本身不再画第二层轮廓
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            }

            if (uiState.restoredFromDraft) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.create_restored_draft),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag(CreateRecordTestTags.DRAFT_NOTE),
                )
            }

            uiState.errorMessageRes?.let { messageRes ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag(CreateRecordTestTags.ERROR),
                )
            }

            if (uiState.attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                AttachmentList(
                    attachments = uiState.attachments,
                    onRemove = onRemoveAttachment,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.create_add_section),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentButton(
                    label = stringResource(R.string.create_pick_image),
                    iconRes = R.drawable.ic_type_image,
                    iconTint = TypeImage,
                    onClick = onPickImage,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CreateRecordTestTags.PICK_IMAGE),
                )
                AttachmentButton(
                    label = stringResource(R.string.create_pick_file),
                    iconRes = R.drawable.ic_type_file,
                    iconTint = TypeFile,
                    onClick = onPickFile,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CreateRecordTestTags.PICK_FILE),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 文件选择器本身就能选音频/视频，把这一点说出来，用户不必去找"语音附件"
            Text(
                text = stringResource(R.string.create_pick_file_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // M21：语音是独立记录，不是附件 —— 与上面的附件区用标题隔开
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.create_voice_section),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            VoiceEntryCard(
                onClick = onVoiceClick,
                modifier = Modifier.testTag(CreateRecordTestTags.VOICE),
            )
        }
    }

    if (uiState.showDiscardDialog) {
        DiscardDialog(onConfirm = onDiscardConfirm, onDismiss = onDiscardDismiss)
    }

    if (uiState.showVoiceConfirmDialog) {
        VoiceNavigationDialog(onConfirm = onVoiceConfirm, onDismiss = onVoiceDismiss)
    }
}

