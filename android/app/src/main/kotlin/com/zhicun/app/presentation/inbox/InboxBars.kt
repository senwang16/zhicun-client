package com.zhicun.app.presentation.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.usecase.CreateCollectionResult

/* 收件箱的顶栏与横幅（M19 从 InboxScreen.kt 拆出：原文件加日历/折叠菜单后会超 400 行上限）。 */

/**
 * 普通模式顶栏（M19 增加右侧动作）。
 *
 * 大标题排版（2026-10 改版）：入口收进底导后，顶栏只留标题与统计；
 * M19 再补两个列表动作：日历（按日期筛选）与更多菜单（全部折叠/展开）。
 * [showListActions] 为 false（空状态/加载中）时动作整体不占位 —— 空列表没有可折叠的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxTopBar(
    uiState: InboxUiState,
    showListActions: Boolean,
    onOpenCalendar: () -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.inbox_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (uiState is InboxUiState.Content) {
                    Text(
                        text = stringResource(
                            R.string.inbox_content_description,
                            uiState.records.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (showListActions) {
                IconButton(
                    onClick = onOpenCalendar,
                    modifier = Modifier.testTag(InboxTestTags.OPEN_CALENDAR),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = stringResource(R.string.inbox_open_calendar),
                    )
                }
                // 折叠/展开放进溢出菜单而不是做成两个图标：它们是低频整理动作，
                // 与日历（高频导航）的视觉重量不该一样
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(InboxTestTags.MENU_MORE),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.inbox_menu_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.inbox_menu_collapse_all)) },
                            onClick = {
                                menuOpen = false
                                onCollapseAll()
                            },
                            modifier = Modifier.testTag(InboxTestTags.MENU_COLLAPSE_ALL),
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.inbox_menu_expand_all)) },
                            onClick = {
                                menuOpen = false
                                onExpandAll()
                            },
                            modifier = Modifier.testTag(InboxTestTags.MENU_EXPAND_ALL),
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/**
 * 选择模式下的顶栏（M16，2026-10-24 增加批量收藏）。
 *
 * 标题直接写"已选 N 条"而不是加一个进度条式的计数：用户此刻只关心三件事
 * —— 选了几条、收藏/归档能不能点。
 *
 * 收藏与归档**并列**而不是合成一个"整理"下拉：两者都是对选中项的直接动作，
 * 收进菜单会多一次点击，而整理模式的整个意义就是"一次搞定一批"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selection: InboxSelection,
    favoriteLabel: String,
    onCancel: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    // X 图标 + 标题居中（2026-10-24 U-11）：参照系统相册/微信的选择模式顶栏；
    // 收藏与归档保持文字按钮——两个动作的语义靠文字最清楚，图标反而要新画两张
    CenterAlignedTopAppBar(
        title = {
            Text(text = stringResource(R.string.inbox_selection_count, selection.count))
        },
        modifier = Modifier.testTag(InboxTestTags.SELECTION_BAR),
        navigationIcon = {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.testTag(InboxTestTags.SELECTION_CANCEL),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.inbox_selection_cancel),
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = {
            // 两个可逆动作用实底（FilledTonal）：整理模式下它们是主要操作，
            // 纯文字按钮看起来像“还没设计完”（2026-10-24 用户反馈）。
            // 紧凑 padding：顶栏要同时容下标题与三个动作，窄屏不挤。
            FilledTonalButton(
                onClick = onFavorite,
                enabled = selection.canAct,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag(InboxTestTags.SELECTION_FAVORITE),
            ) {
                Text(text = favoriteLabel)
            }
            FilledTonalButton(
                onClick = onArchive,
                enabled = selection.canAct,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.testTag(InboxTestTags.SELECTION_ARCHIVE),
            ) {
                Text(text = stringResource(R.string.inbox_selection_archive))
            }
            // 删除保持文字按钮 + 错误色：它是不可恢复的那一个，
            // 与“收藏/归档”的视觉重量要拉开 —— 重按钮会给破坏性动作煽风。
            TextButton(
                onClick = onDelete,
                enabled = selection.canAct,
                modifier = Modifier.testTag(InboxTestTags.SELECTION_DELETE),
            ) {
                Text(
                    text = stringResource(R.string.inbox_selection_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * 悬浮窗引导横幅（附录 T）。
 *
 * 跳过引导的人从这里找回入口：常驻（每次进收件箱都在）、可关闭（关一次就不再打扰，
 * 关闭标记持久化）。判据在 [com.zhicun.app.presentation.onboarding.OnboardingViewModel]：
 * 悬浮窗未授权 && 未手动关闭 —— 用户刻意关掉气泡开关但权限还在时不出
 * （那是有意的选择，不该被当成"没配置好"）。
 */
@Composable
internal fun BubbleGuideBanner(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InboxTestTags.BUBBLE_GUIDE_BANNER),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.inbox_bubble_guide_text),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.inbox_bubble_guide_cta))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.inbox_bubble_guide_dismiss),
                    modifier = Modifier.testTag(InboxTestTags.BUBBLE_GUIDE_DISMISS),
                )
            }
        }
    }
}

/** 一次性反馈 → 文案。放在这里而不是 ViewModel：文案属于表现层，ViewModel 只带数据。 */
@Composable
internal fun inboxFeedbackMessage(feedback: InboxFeedback): String = when (feedback) {
    is InboxFeedback.Archived -> stringResource(R.string.inbox_archived_toast, feedback.count)
    InboxFeedback.ArchiveFailed -> stringResource(R.string.inbox_archive_failed)
    is InboxFeedback.Favorited -> stringResource(R.string.inbox_favorited_toast, feedback.count)
    is InboxFeedback.Unfavorited -> stringResource(R.string.inbox_unfavorited_toast, feedback.count)
    InboxFeedback.FavoriteFailed -> stringResource(R.string.inbox_favorite_failed)
    // 三种删除结果分开说：用户需要知道"删干净了""删了但空间没释放""什么都没删成"，
    // 混成一句"操作完成"会让后两种看起来像成功
    is InboxFeedback.Deleted -> when {
        feedback.incomplete == 0 -> stringResource(R.string.inbox_delete_done, feedback.count)
        feedback.count == 0 ->
            stringResource(R.string.inbox_delete_none_done, feedback.incomplete)

        else -> stringResource(
            R.string.inbox_delete_partial,
            feedback.count,
            feedback.incomplete,
        )
    }

    InboxFeedback.DeleteFailed -> stringResource(R.string.inbox_delete_failed)
    is InboxFeedback.CollectionCreated ->
        stringResource(R.string.inbox_collection_created, feedback.memberCount)

    // 拒绝原因逐条说清：用户需要知道是"选少了""记录不在了"还是"不能嵌套合集"，
    // 混成一句"无法创建"会让人只能靠猜
    is InboxFeedback.CollectionRejected -> when (feedback.reason) {
        CreateCollectionResult.Reason.TOO_FEW -> stringResource(R.string.inbox_collection_too_few)
        CreateCollectionResult.Reason.MISSING_RECORD ->
            stringResource(R.string.inbox_collection_missing_record)

        CreateCollectionResult.Reason.DELETED_MEMBER ->
            stringResource(R.string.inbox_collection_deleted_member)

        CreateCollectionResult.Reason.NESTED_COLLECTION ->
            stringResource(R.string.inbox_collection_nested)
    }

    InboxFeedback.CollectionCreateFailed -> stringResource(R.string.inbox_collection_failed)
}
