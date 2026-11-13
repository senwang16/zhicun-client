package com.zhicun.app.presentation.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhicun.app.R
import kotlinx.coroutines.launch

object OnboardingTestTags {
    const val SKIP = "onboarding_skip"
    const val NEXT = "onboarding_next"
    const val OVERLAY_CARD = "onboarding_overlay_card"
    const val KEEPALIVE_CARD = "onboarding_keepalive_card"
    const val AUTOSTART_GUIDE = "onboarding_autostart_guide"
    const val PAGE_INDICATOR = "onboarding_page_indicator"
}

/** 引导总屏数（附录 T：卖点 / 分享进来 / 气泡用法 / 开启+保护）。 */
private const val PAGE_COUNT = 4

/**
 * 首启引导（附录 T，4 屏全可跳过）。
 *
 * 只做壳：拿 ViewModel、ON_RESUME 复查系统状态（用户从系统设置授权回来，
 * "已完成"不用手动刷新）、把完成动作转成 `complete() + onFinished()` 两步。
 * 布局在 [OnboardingContent]（无状态），UI 测试直接喂状态即可。
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAutostartGuide: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    OnboardingContent(
        uiState = uiState,
        onSkip = {
            viewModel.complete()
            onFinished()
        },
        onFinish = {
            viewModel.complete()
            onFinished()
        },
        onOpenOverlaySettings = viewModel::openOverlaySettings,
        onOpenBatterySettings = viewModel::openBatterySettings,
        onOpenAutostartGuide = onOpenAutostartGuide,
        modifier = modifier,
    )
}

/**
 * 首启引导（无状态）。
 *
 * 顺序刻意为"先讲价值（屏 3 气泡用法）再要权限（屏 4 开启）"；每屏都可跳过，
 * 跳过也写完成版本 —— 引导不是任务，不欠着用户。
 *
 * 各屏内容按屏拆在同包的 `OnboardingValuePages.kt`（屏 1/2）、
 * `OnboardingBubblePage.kt`（屏 3）与 `OnboardingEnablePage.kt`（屏 4），
 * 本文件只留壳与分页骨架 —— AGENTS.md §2.1 单文件 400 行上限。
 */
@Composable
fun OnboardingContent(
    uiState: OnboardingUiState,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAutostartGuide: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    // 视觉上是 "1 / 4"，读屏要念出"第 1 页，共 4 页"，所以单独给 contentDescription。
    val pageIndicatorCd = stringResource(
        R.string.onboarding_page_indicator_cd,
        pagerState.currentPage + 1,
        PAGE_COUNT,
    )

    Column(modifier = modifier.fillMaxSize()) {
        // 顶行：页码 + 跳过。跳过永远可达 —— 首启流失的最大来源就是强推。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.onboarding_page_indicator,
                    pagerState.currentPage + 1,
                    PAGE_COUNT,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(OnboardingTestTags.PAGE_INDICATOR)
                    .semantics { contentDescription = pageIndicatorCd },
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.onboarding_skip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(OnboardingTestTags.SKIP),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (page) {
                0 -> IntroPage()
                1 -> SharePage()
                2 -> BubblePage()
                else -> EnablePage(
                    uiState = uiState,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAutostartGuide = onOpenAutostartGuide,
                )
            }
        }

        // 页指示点：当前页实心，其余描边
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(PAGE_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage == PAGE_COUNT - 1) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .testTag(OnboardingTestTags.NEXT),
        ) {
            Text(
                text = if (pagerState.currentPage == PAGE_COUNT - 1) {
                    stringResource(R.string.onboarding_start)
                } else {
                    stringResource(R.string.onboarding_next)
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** 各屏公共骨架：左右留白 + 可滚动（小屏 / 大字号下内容不被截断）。 */
@Composable
internal fun PageScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
