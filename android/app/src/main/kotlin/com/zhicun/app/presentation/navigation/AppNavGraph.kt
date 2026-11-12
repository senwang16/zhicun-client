package com.zhicun.app.presentation.navigation

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zhicun.app.presentation.archive.ArchiveScreen
import com.zhicun.app.presentation.create.CreateRecordScreen
import com.zhicun.app.presentation.detail.RecordDetailScreen
import com.zhicun.app.presentation.inbox.InboxScreen
import com.zhicun.app.presentation.onboarding.OnboardingScreen
import com.zhicun.app.presentation.onboarding.OnboardingStart
import com.zhicun.app.presentation.onboarding.OnboardingViewModel
import com.zhicun.app.presentation.search.SearchScreen
import com.zhicun.app.presentation.settings.SettingsScreen
import com.zhicun.app.presentation.voice.VoiceRecordScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private const val TAG = "AppNavGraph"

/**
 * 应用导航图（单 Activity，M3 起取代 M1 的占位路由）。
 *
 * 约定：
 * - 屏幕级 Composable **不接收 ViewModel 实例**，各自用 `hiltViewModel()` 获取，
 *   这样返回栈上的同一路由（不同参数）会各自持有独立 ViewModel。
 * - 页面之间的跳转由路由字符串承载（[Routes.recordDetail]），
 *   屏幕本身只暴露语义化回调（`onRecordClick` / `onCreateClick` / `onBack`），
 *   便于 UI 测试脱离导航图单独跑。
 */
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    navigateRequests: Flow<String> = emptyFlow(),
) {
    // 悬浮面板等"外部入口"要跳到某个页面时，把路由塞进这个流。
    // 用 popUpTo(起始页) 让返回键回到收件箱，而不是逐层退掉用户之前开过的页面。
    LaunchedEffect(navigateRequests, navController) {
        navigateRequests.collect { route ->
            navController.navigate(route) {
                popUpTo(Routes.INBOX) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 首启引导的起点判定（附录 T）：prefs 同步读，init 即出结果；null 只会撑过
    // 首帧前的极短间隙，渲染空白兜底 —— 开屏退场动画正好盖住这一帧。
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingStart by onboardingViewModel.start.collectAsStateWithLifecycle()

    // 底部导航（2026-10 改版）：搜索/归档/设置入口从收件箱顶栏移到底导，
    // 详情/新建/录音等沉浸页自动隐藏。切换 tab 与旧入口同语义：回到收件箱层级再前进。
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentRoute in bottomBarRoutes) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.INBOX) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
    ) { bottomPadding ->
        when (onboardingStart) {
            null -> Box(modifier = Modifier.padding(bottomPadding).fillMaxSize())

            else -> NavHost(
                navController = navController,
                // 首启从引导屏起步；已看过（或版本号更高）直接进收件箱
                startDestination = if (onboardingStart is OnboardingStart.FirstRun) {
                    Routes.ONBOARDING
                } else {
                    Routes.INBOX
                },
                modifier = Modifier.padding(bottomPadding),
            ) {
            composable(route = Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        // 引导走完/跳过都落到收件箱；popUpTo 把引导从返回栈摘掉，
                        // 返回键在收件箱就是退出 App，不会退回引导
                        navController.navigate(Routes.INBOX) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                    onOpenAutostartGuide = {
                        // 自启动没有公开 API，跳应用内「设置-后台常驻」看按 ROM 的指引
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    },
                )
            }

            composable(route = Routes.INBOX) {
            // 搜索/归档/设置由底部导航直达；收件箱只剩卡片与新建入口
            InboxScreen(
                onRecordClick = { recordId ->
                    navController.navigate(Routes.recordDetail(recordId))
                },
                onCreateClick = { navController.navigate(Routes.CREATE) },
                // 合集创建成功（M20）：直接进详情页看合并后的摘要与来源。
                // 返回键回到收件箱，此时列表里已经有这条新记录了。
                onCollectionCreated = { collectionId ->
                    navController.navigate(Routes.recordDetail(collectionId))
                },
            )
        }

        // 归档箱（M16）：与收件箱并列的底导 tab，没有返回语义（2026-10-24 U-11 删 onBack）
        composable(route = Routes.ARCHIVE) {
            ArchiveScreen(
                onRecordClick = { recordId -> navController.navigate(Routes.recordDetail(recordId)) },
            )
        }

        composable(route = Routes.SEARCH) {
            SearchScreen(
                onRecordClick = { recordId -> navController.navigate(Routes.recordDetail(recordId)) },
            )
        }

        composable(route = Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // 新建页（M5；M13 剪贴板读不到时带 pasteHint=true 跳来，普通入口不带参数同样匹配）
        composable(
            route = "${Routes.CREATE}?${Routes.CREATE_PASTE_HINT_ARG}={${Routes.CREATE_PASTE_HINT_ARG}}",
            arguments = listOf(
                navArgument(Routes.CREATE_PASTE_HINT_ARG) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            CreateRecordScreen(
                // 保存成功回列表：M5 不自动跳详情，用户刚写完东西时更可能想继续写或看列表
                onSaved = { navController.popBackStack() },
                onBack = {
                    if (!navController.popBackStack()) {
                        // 理论上不会发生（新建页总是从收件箱进入），但返回失败必须留痕
                        Log.w(TAG, "新建页返回时栈已空，忽略")
                    }
                },
                // M9：录音是独立页面（需要全屏计时/打断对话框），从新建页进入
                onVoiceClick = { navController.navigate(Routes.VOICE) },
                pasteHint = entry.arguments?.getBoolean(Routes.CREATE_PASTE_HINT_ARG) ?: false,
            )
        }

        composable(route = Routes.VOICE) {
            VoiceRecordScreen(
                // 语音保存后直接进详情：用户录完最想确认的是"能不能听"，而详情页有播放器
                onSaved = { recordId ->
                    navController.navigate(Routes.recordDetail(recordId)) {
                        // 把录音页与新建页一起从栈里摘掉，返回时回到收件箱而不是空的新建页
                        popUpTo(Routes.CREATE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RECORD_DETAIL,
            arguments = listOf(
                navArgument(Routes.RECORD_ID_ARG) { type = NavType.StringType },
            ),
        ) {
            RecordDetailScreen(
                onBack = { navController.navigateUp() },
                // 相关记录（M18）：点卡片跳那条记录的详情，返回键自然回到当前页
                onRecordClick = { recordId -> navController.navigate(Routes.recordDetail(recordId)) },
                // 云端被挡时的出路（F-4）：压栈进设置，返回键回到这条记录，
                // 配合详情页 ON_RESUME 刷新，开关一改回来就能看到确认按钮（F-6）
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }
        }
        }
    }
}
