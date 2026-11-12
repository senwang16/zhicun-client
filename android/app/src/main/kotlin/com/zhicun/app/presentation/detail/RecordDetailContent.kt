package com.zhicun.app.presentation.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
/**
 * 详情页的纯展示壳（2026-10-29 从 `RecordDetailScreen.kt` 拆出）。
 *
 * 只负责把 [RecordDetailUiState] 画出来、把用户动作回调上去；订阅状态、导航返回、
 * 二次确认框与 Snackbar 都在有状态壳 [RecordDetailScreen] 里。
 * 拆分的直接原因是 AGENTS.md §2.1 的单文件 400 行上限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailContent(
    uiState: RecordDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    playback: VoicePlaybackState = VoicePlaybackState(),
    /** 参数是音频附件 id；null = 第一段（单音频记录的既有调用点不必改）。 */
    onPlayPause: (String?) -> Unit = {},
    onConfirmCloud: () -> Unit = {},
    /** 点"去设置里打开云端 AI"（F-4）；默认空实现，预览不需要导航。 */
    onOpenSettings: () -> Unit = {},
    /** 点"允许抓取此网页"（F-5）；默认空实现便于既有测试零改动。 */
    onConfirmFetch: () -> Unit = {},
    /** 点"查看快照"回调，参数是快照文件在私有目录里的绝对路径（C）。 */
    onOpenSnapshot: (String) -> Unit = {},
    /** 标签行编辑保存（U-7，2026-10-24）：传入编辑框解析出的全量标签名。 */
    onTagsEdited: (List<String>) -> Unit = {},
    /** 点附件条目（2026-10-24）；默认空实现，预览与不关心附件的测试零改动。 */
    onOpenAsset: (Asset) -> Unit = {},
    /** 分享附件（2026-10-28）；默认空实现，预览与不关心附件的测试零改动。 */
    onShareAsset: (Asset) -> Unit = {},
    /**
     * 附件行 ⋮ →「删除附件」（2026-10-29）。
     *
     * 这里只发起请求：确认框与真正的删除在有状态壳里（[RecordDetailScreen]），
     * 纯展示组件不持有"待删除附件"这种跨操作状态。
     */
    onRequestDeleteAsset: (Asset) -> Unit = {},
    /** 切换归档（2026-10-29 从整理区移到顶栏 ⋮）。 */
    onToggleArchived: () -> Unit = {},
    /** 顶栏 ⋮ →「删除记录」：同样只发起请求，确认后才执行。 */
    onRequestDeleteRecord: () -> Unit = {},
    /** 原文编辑状态与动作（2026-10-26）；默认不进入编辑，既有调用点零改动。 */
    contentEdit: DetailContentEditState = DetailContentEditState(),
    onStartRawTextEdit: () -> Unit = {},
    onRawTextDraftChange: (String) -> Unit = {},
    onCancelRawTextEdit: () -> Unit = {},
    onSaveRawText: () -> Unit = {},
    /** 复制某一块的文字（2026-10-26 用户裁决：每个模块各自复制）；null = 不画复制按钮。 */
    onCopyText: ((String) -> Unit)? = null,
    /** 点合集来源卡片时跳到那条原记录（M20）；默认空实现便于既有测试零改动。 */
    onRecordClick: (String) -> Unit = {},
    /**
     * 提示条宿主。由有状态壳传入，好让"附件打不开"这类组合之外的异步结果能显示出来；
     * 默认自建一个，`RecordDetailContent` 单独使用时也能工作。
     */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    organizeSection: @Composable () -> Unit = {},
    relatedSection: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 居中标题 + 箭头图标返回（2026-10-24 U-11）：参照微信/小红书等主流二级页顶栏，
            // 原来的文字「返回」TextButton 占位又不主流
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_back),
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    val content = uiState as? RecordDetailUiState.Content
                    // 已删除（等待清理）的记录不给动作：删/归档都没有意义
                    if (content != null && !content.record.isDeleted) {
                        DetailOverflowMenu(
                            contentDescription = stringResource(R.string.detail_more_actions),
                            testTag = RecordDetailTestTags.TOP_MORE,
                        ) { dismiss ->
                            DetailMenuItem(
                                iconRes = R.drawable.ic_nav_archive,
                                label = stringResource(
                                    if (content.record.isArchived) {
                                        R.string.detail_organize_unarchive
                                    } else {
                                        R.string.detail_organize_archive
                                    },
                                ),
                                onClick = {
                                    dismiss()
                                    onToggleArchived()
                                },
                                testTag = RecordDetailTestTags.TOP_MENU_ARCHIVE,
                            )
                            HorizontalDivider()
                            DetailMenuItem(
                                iconRes = R.drawable.ic_action_delete,
                                label = stringResource(R.string.detail_menu_delete_record),
                                onClick = {
                                    dismiss()
                                    onRequestDeleteRecord()
                                },
                                danger = true,
                                testTag = RecordDetailTestTags.TOP_MENU_DELETE,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                RecordDetailUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                RecordDetailUiState.NotFound -> CenteredHint(
                    title = stringResource(R.string.detail_not_found_title),
                    hint = stringResource(R.string.detail_not_found_hint),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(RecordDetailTestTags.NOT_FOUND),
                )

                is RecordDetailUiState.Error -> Text(
                    text = stringResource(uiState.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )

                is RecordDetailUiState.Content -> DetailBody(
                    record = uiState.record,
                    assets = uiState.assets,
                    derived = uiState.derived,
                    tags = uiState.tags,
                    onRetry = onRetry,
                    playback = playback,
                    onPlayPause = onPlayPause,
                    onConfirmCloud = onConfirmCloud,
                    onOpenSnapshot = onOpenSnapshot,
                    cloudBlockReason = uiState.cloudBlockReason,
                    cloudConfirmPending = uiState.cloudConfirmPending,
                    canRetry = uiState.canRetry,
                    canRetryEmptySummary = uiState.canRetryEmptySummary,
                    transcribeFailed = uiState.transcribeJobFailed,
                    webFetchConfirmVisible = uiState.webFetchConfirmVisible,
                    webFetchPending = uiState.webFetchPending,
                    onOpenSettings = onOpenSettings,
                    onConfirmFetch = onConfirmFetch,
                    onTagsEdited = onTagsEdited,
                    onOpenAsset = onOpenAsset,
                    onShareAsset = onShareAsset,
                    onRequestDeleteAsset = onRequestDeleteAsset,
                    contentEdit = contentEdit,
                    onStartRawTextEdit = onStartRawTextEdit,
                    onRawTextDraftChange = onRawTextDraftChange,
                    onCancelRawTextEdit = onCancelRawTextEdit,
                    onSaveRawText = onSaveRawText,
                    onCopyText = onCopyText,
                    collection = uiState.collection,
                    onRecordClick = onRecordClick,
                    organizeSection = organizeSection,
                    relatedSection = relatedSection,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(RecordDetailTestTags.CONTENT),
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
