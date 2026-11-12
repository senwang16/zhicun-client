package com.zhicun.app.presentation.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.presentation.common.DeleteRecordsDialog
import com.zhicun.app.presentation.snapshot.SnapshotViewerActivity
import java.io.File
import kotlinx.coroutines.launch

/** 测试用 tag。 */
object RecordDetailTestTags {
    const val CONTENT = "detail_content"
    const val RAW_TEXT = "detail_raw_text"
    const val NOT_FOUND = "detail_not_found"
    const val OCR_TEXT = "detail_ocr_text"
    const val OCR_RETRY = "detail_ocr_retry"
    const val WEB_TEXT = "detail_web_text"
    const val WEB_RETRY = "detail_web_retry"
    const val WEB_SNAPSHOT = "detail_web_snapshot"
    const val WEB_OG_IMAGE = "detail_web_og_image"
    const val VOICE_PLAY = "detail_voice_play"
    const val VOICE_TEXT = "detail_voice_text"
    const val VOICE_RETRY = "detail_voice_retry"
    const val AI_SUMMARY = "detail_ai_summary"
    const val AI_TAGS = "detail_ai_tags"

    /** 标签编辑框与保存按钮（U-7，2026-10-24）：点击标签后出现。 */
    const val AI_TAGS_EDIT = "detail_ai_tags_edit"
    const val AI_TAGS_SAVE = "detail_ai_tags_save"

    /** 显式取消标签编辑（2026-10-26）：失焦与返回键也能取消，但按钮更直观。 */
    const val AI_TAGS_CANCEL = "detail_ai_tags_cancel"

    /** 原文编辑（2026-10-26）：编辑入口、输入框、保存/取消与错误提示。 */
    const val RAW_TEXT_EDIT_START = "detail_raw_text_edit_start"
    const val RAW_TEXT_EDIT = "detail_raw_text_edit"
    const val RAW_TEXT_SAVE = "detail_raw_text_save"
    const val RAW_TEXT_CANCEL = "detail_raw_text_cancel"
    const val RAW_TEXT_ERROR = "detail_raw_text_error"
    const val RAW_TEXT_COPY = "detail_raw_text_copy"

    /** 非完成状态的手动重新处理入口（2026-10-26）。 */
    const val MANUAL_RETRY = "detail_manual_retry"
    const val MANUAL_RETRY_SETTINGS = "detail_manual_retry_settings"
    const val AI_RETRY = "detail_ai_retry"
    const val AI_SENSITIVE = "detail_ai_sensitive"
    const val AI_CONFIRM = "detail_ai_confirm"

    /** 云端不可用时代替确认按钮出现的说明（点了不会发生任何事，所以不给按钮）。 */
    const val AI_CONFIRM_UNAVAILABLE = "detail_ai_confirm_unavailable"

    /**
     * 云端被挡、重试又是空动作时出现的"去设置里打开云端 AI"入口
     * （2026-10-23 整改 F-4）。
     */
    const val OPEN_SETTINGS = "detail_open_cloud_settings"

    /**
     * 横幅里的同一入口，单独一个 tag：待确认横幅与摘要块可能同时出现，
     * 共用 tag 会让 UI 测试拿到两个节点（2026-10-23 整改 F-4）。
     */
    const val OPEN_SETTINGS_BANNER = "detail_open_cloud_settings_banner"

    /** 敏感网页被拦时的"允许抓取此网页"（2026-10-23 复核 F-5）。 */
    const val WEB_CONFIRM_FETCH = "detail_web_confirm_fetch"

    /** 附件条目（2026-10-24）：一条附件一行，图片时另有缩略图 tag。 */
    const val ASSET_ITEM = "detail_asset_item"

    /** 页面级 ⋮（2026-10-29）：归档 / 删除记录。 */
    const val TOP_MORE = "detail_top_more"
    const val TOP_MENU_ARCHIVE = "detail_top_menu_archive"
    const val TOP_MENU_DELETE = "detail_top_menu_delete"

    /** 二次确认框（2026-10-29）：删除整条记录 / 删除附件 / 最后一项内容。 */
    const val RECORD_DELETE_CONFIRM = "detail_record_delete_confirm"
    const val RECORD_DELETE_CANCEL = "detail_record_delete_cancel"
    const val ASSET_DELETE_CONFIRM = "detail_asset_delete_confirm"
    const val ASSET_DELETE_CANCEL = "detail_asset_delete_cancel"
    const val ASSET_LAST_CONFIRM = "detail_asset_last_confirm"
    const val ASSET_LAST_CANCEL = "detail_asset_last_cancel"

    /** 附件行动作按附件 id 区分：一条记录有多个附件时测试才能点到"哪一个"。 */
    fun assetOpenTag(id: String): String = "detail_asset_open_$id"
    fun assetMoreTag(id: String): String = "detail_asset_more_$id"
    fun assetShareTag(id: String): String = "detail_asset_share_$id"
    fun assetDeleteTag(id: String): String = "detail_asset_delete_$id"

    /** 多附件分段（2026-10-29）：每个文件的 OCR / 转写各有一份 tag。 */
    fun ocrTextTag(assetId: String): String = "detail_ocr_text_$assetId"
    fun voicePlayTag(assetId: String): String = "detail_voice_play_$assetId"
    fun voiceTextTag(assetId: String): String = "detail_voice_text_$assetId"

    /** 图片缩略图本身；解不出来时这个节点不存在，回落成类型图标。 */
    const val ASSET_THUMBNAIL = "detail_asset_thumbnail"

    /** 合集（M20）：过期横幅、重新生成、来源卡片。 */
    const val COLLECTION_STALE = "detail_collection_stale"
    const val COLLECTION_REGENERATE = "detail_collection_regenerate"
    const val COLLECTION_OPEN_SETTINGS = "detail_collection_open_settings"
    const val COLLECTION_MEMBER_PREFIX = "detail_collection_member_"
    const val COLLECTION_MEMBER_TEXT_PREFIX = "detail_collection_member_text_"
}

/**
 * 详情页（有状态壳）。订阅 StateFlow 的姿势与收件箱一致：
 * `collectAsStateWithLifecycle()`，后台不被收集（AGENTS.md §2.2 明令禁止裸 `collectAsState`）。
 */
@Composable
fun RecordDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 点"相关记录"卡片时跳到那条记录的详情（M18）。默认空实现便于既有测试零改动。 */
    onRecordClick: (String) -> Unit = {},
    /**
     * 跳设置页（2026-10-23 整改 F-4）。
     *
     * 云端被挡时，重试按钮是空动作，真正的出路是去设置里打开云端 AI / 配置网关。
     * 默认空实现：本页在预览与部分测试里不接导航图。
     */
    onOpenSettings: () -> Unit = {},
    viewModel: RecordDetailViewModel = hiltViewModel(),
) {
    // 2026-10-23 整改 F-6：从设置页返回时重新判一次门禁。
    // 云端开关与网关配置是同步属性、没有 Flow，不主动刷新的话横幅会一直用着离开前的判断，
    // 用户刚在设置里打开的云端 AI 在这条记录上"不生效"（必须退出重进）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val organize by viewModel.organize.collectAsStateWithLifecycle()
    val related by viewModel.relatedRecords.collectAsStateWithLifecycle()
    val contentEdit by viewModel.contentEdit.collectAsStateWithLifecycle()
    // 快照查看页的启动放这里：分区组件保持纯展示，不认识 Activity 与 Intent
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 文案在组合期取好：openAsset 是普通 lambda，拿不到 stringResource
    val openFailedText = stringResource(R.string.detail_asset_open_failed)
    val fileMissingText = stringResource(R.string.detail_asset_file_missing)
    val shareFailedText = stringResource(R.string.detail_asset_share_failed)
    val copiedText = stringResource(R.string.detail_copied)
    val assetDeletedText = stringResource(R.string.detail_asset_delete_done)
    val assetDeleteFailedText = stringResource(R.string.detail_asset_delete_failed)
    val recordDeleteFailedText = stringResource(R.string.detail_record_delete_failed)

    // 二次确认与一次性提示（2026-10-29）：删除是终态动作，点之前必须说清删什么。
    // 状态留在页面层而不是 ViewModel：对话框关掉就该消失，重建页面时不该复活。
    var pendingAssetDelete by remember { mutableStateOf<Asset?>(null) }
    var showRecordDelete by remember { mutableStateOf(false) }
    var showLastContent by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RecordDetailEvent.AssetDeleted -> snackbarHostState.showSnackbar(assetDeletedText)
                RecordDetailEvent.AssetDeleteFailed -> snackbarHostState.showSnackbar(assetDeleteFailedText)
                RecordDetailEvent.LastContent -> showLastContent = true
                // 删除成功即返回列表：继续留在已不存在的记录上没有意义。
                // 不在这里弹 Snackbar —— 页面马上离开，提示会跟着一起消失。
                RecordDetailEvent.RecordDeleted -> onBack()

                RecordDetailEvent.RecordDeleteFailed ->
                    snackbarHostState.showSnackbar(recordDeleteFailedText)
            }
        }
    }
    // 复制（2026-10-26 用户裁决：每个模块各自复制）。剪贴板只在用户点击时写，
    // 不在后台读取（AGENTS.md §2.4）；Android 13 起系统自带"已复制"浮层，不再叠一条提示。
    val detector = remember { SensitiveContentDetector() }
    val copySensitiveWarningText = stringResource(R.string.detail_copy_sensitive_warning)
    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        // 外发复查：复制前对实际外发文本跑一次敏感检测，命中则提示风险 —— 只提示不阻断
        // （复制是用户显式动作，拦截反而把内容扣在唯一可带走它的通道上）。
        if (detector.scan(text).isSuspected) {
            scope.launch { snackbarHostState.showSnackbar(copySensitiveWarningText) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbarHostState.showSnackbar(copiedText) }
        }
    }
    RecordDetailContent(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::onRetryClicked,
        playback = playback,
        onPlayPause = viewModel::onPlayPauseClicked,
        onConfirmCloud = viewModel::onConfirmCloudClicked,
        onOpenSettings = onOpenSettings,
        onConfirmFetch = { viewModel.onConfirmWebFetchClicked() },
        onOpenSnapshot = { path ->
            context.startActivity(
                Intent(context, SnapshotViewerActivity::class.java)
                    .putExtra(SnapshotViewerActivity.EXTRA_SNAPSHOT_PATH, path),
            )
        },
        // 标签行编辑（U-7，2026-10-24）：差量保存见 RecordDetailViewModel.onTagsEdited
        onTagsEdited = viewModel::onTagsEdited,
        // 原文编辑（2026-10-26）：保存后由 UpdateRecordTextUseCase 重算 AI 摘要/标签/向量
        contentEdit = contentEdit,
        onStartRawTextEdit = viewModel::onStartRawTextEdit,
        onRawTextDraftChange = viewModel::onRawTextDraftChange,
        onCancelRawTextEdit = viewModel::onCancelRawTextEdit,
        onSaveRawText = viewModel::onSaveRawText,
        onCopyText = copyToClipboard,
        // 附件点击（2026-10-24）：交给系统应用打开。打不开要说清是"没有应用"还是"文件没了"——
        // 只说"打开失败"用户没法判断该装应用还是该重新导入
        onOpenAsset = { asset ->
            if (!openAssetWithSystemViewer(context, asset)) {
                scope.launch {
                    val missing = asset.localPath.isBlank() || !File(asset.localPath).isFile
                    snackbarHostState.showSnackbar(if (missing) fileMissingText else openFailedText)
                }
            }
        },
        // 分享附件（2026-10-28 用户反馈）：不走系统查看器，直接 ACTION_SEND。
        // 部分 ROM 的相册打开图片后只有编辑、没有分享，这条路径让各版本一致。
        onShareAsset = { asset ->
            if (!shareAssetWithSystemChooser(context, asset)) {
                scope.launch {
                    val missing = asset.localPath.isBlank() || !File(asset.localPath).isFile
                    snackbarHostState.showSnackbar(if (missing) fileMissingText else shareFailedText)
                }
            }
        },
        // 页面级动作（2026-10-29）：归档移到顶栏 ⋮；删除先确认再执行
        onToggleArchived = viewModel::onToggleArchived,
        onRequestDeleteRecord = { showRecordDelete = true },
        onRequestDeleteAsset = { pendingAssetDelete = it },
        snackbarHostState = snackbarHostState,
        // 整理分区用槽位传入而不是往下透传十几个回调：DetailBody 只关心"在这个位置放一块东西"，
        // 不该知道收藏/置顶/标签各自怎么调
        organizeSection = {
            val content = uiState as? RecordDetailUiState.Content
            if (content != null) {
                DetailOrganizeBlock(
                    record = content.record,
                    tags = content.tags,
                    tagInput = organize.tagInput,
                    tagError = organize.tagError,
                    onToggleFavorite = viewModel::onToggleFavorite,
                    onTogglePinned = viewModel::onTogglePinned,
                    onTagInputChange = viewModel::onTagInputChange,
                    onAddTag = viewModel::onAddTag,
                    onRemoveTag = viewModel::onRemoveTag,
                )
            }
        },
        // 相关记录（M18）：只在确实有命中时渲染，避免"相关记录"下面空着
        relatedSection = {
            if (!related.isEmpty) {
                RelatedRecordsBlock(related = related, onRecordClick = onRecordClick)
            }
        },
        // 合集来源卡片跳回原记录（M20）
        onRecordClick = onRecordClick,
        modifier = modifier,
    )

    // 删除附件确认（2026-10-29）：点的是行内 ⋮ → 「删除附件」，这里才真正落库。
    // 文件名优先，拿不到名字就说"这个附件" —— 不能让用户对着一个说不清对象的确认框点"删除"。
    pendingAssetDelete?.let { asset ->
        val name = assetDisplayName(asset)
        DetailConfirmDialog(
            title = if (name != null) {
                stringResource(R.string.detail_asset_delete_confirm_title, name)
            } else {
                stringResource(R.string.detail_asset_delete_confirm_title_untitled)
            },
            message = stringResource(R.string.detail_asset_delete_confirm_message),
            confirmLabel = stringResource(R.string.detail_asset_delete),
            onConfirm = {
                pendingAssetDelete = null
                viewModel.onDeleteAsset(asset.id)
            },
            onDismiss = { pendingAssetDelete = null },
            confirmTestTag = RecordDetailTestTags.ASSET_DELETE_CONFIRM,
            cancelTestTag = RecordDetailTestTags.ASSET_DELETE_CANCEL,
        )
    }

    // 删除整条记录：复用收件箱的确认框（同一句后果说明，两个入口不能有两种说法）
    if (showRecordDelete) {
        DeleteRecordsDialog(
            count = 1,
            confirmTestTag = RecordDetailTestTags.RECORD_DELETE_CONFIRM,
            cancelTestTag = RecordDetailTestTags.RECORD_DELETE_CANCEL,
            onConfirm = {
                showRecordDelete = false
                viewModel.onDeleteRecord()
            },
            onDismiss = { showRecordDelete = false },
        )
    }

    // 最后一个内容不能单删（否则会留下空记录）：引导用户删整条
    if (showLastContent) {
        DetailConfirmDialog(
            title = stringResource(R.string.detail_asset_last_title),
            message = stringResource(R.string.detail_asset_last_message),
            confirmLabel = stringResource(R.string.detail_asset_last_confirm),
            onConfirm = {
                showLastContent = false
                viewModel.onDeleteRecord()
            },
            onDismiss = { showLastContent = false },
            confirmTestTag = RecordDetailTestTags.ASSET_LAST_CONFIRM,
            cancelTestTag = RecordDetailTestTags.ASSET_LAST_CANCEL,
        )
    }
}

