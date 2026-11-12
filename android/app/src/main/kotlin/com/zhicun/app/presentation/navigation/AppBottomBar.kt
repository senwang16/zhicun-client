package com.zhicun.app.presentation.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R

/** 底部导航项的测试与无障碍定位 tag。 */
object AppBottomBarTestTags {
    const val INBOX = "nav_inbox"
    const val SEARCH = "nav_search"
    const val ARCHIVE = "nav_archive"
    const val SETTINGS = "nav_settings"
}

private enum class AppBottomDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val testTag: String,
) {
    INBOX(Routes.INBOX, R.string.inbox_title, R.drawable.ic_nav_inbox, AppBottomBarTestTags.INBOX),
    SEARCH(Routes.SEARCH, R.string.inbox_search, R.drawable.ic_nav_search, AppBottomBarTestTags.SEARCH),
    ARCHIVE(Routes.ARCHIVE, R.string.inbox_archive_entry, R.drawable.ic_nav_archive, AppBottomBarTestTags.ARCHIVE),
    SETTINGS(Routes.SETTINGS, R.string.settings_open, R.drawable.ic_nav_settings, AppBottomBarTestTags.SETTINGS),
}

/** 底部导航的四个 tab 路由；命中其一才显示底导，详情/新建/录音等沉浸页隐藏。 */
val bottomBarRoutes: Set<String> = AppBottomDestination.entries.mapTo(mutableSetOf()) { it.route }

/**
 * 底部导航（2026-10 UI 改版）：收件箱 / 搜索 / 归档箱 / 设置。
 *
 * ## 为什么不再给 FAB 留空位
 * 旧版在第二项之后插了一个 64dp 的死占位，让 FAB 悬在底导中央 —— 代价是四个 tab
 * 不再等距（左右各两项挤在两侧，视觉重心压在边上，且在不同屏宽下间距不一致）。
 * 现在四项平分宽度，收件箱的 FAB 仍由 `Scaffold` 的 `FabPosition.Center` 悬在
 * 底导**上方**（不在栏内），因此不需要让位。
 *
 * 选中态用品牌青绿，未选中为次要灰；不引入 material-icons，图标为自绘 vector。
 */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        // `NavigationBar` 内部是等权 Row：只要不插入定宽占位，四项天然等距。
        AppBottomDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                    )
                },
                label = { Text(text = stringResource(destination.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.testTag(destination.testTag),
            )
        }
    }
}
