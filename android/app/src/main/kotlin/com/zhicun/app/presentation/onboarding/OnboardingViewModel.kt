package com.zhicun.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.KeepAliveGateway
import com.zhicun.app.domain.onboarding.OnboardingSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 导航起点的判定结果（附录 T）。`null` = 尚未判定（不会超过一帧：prefs 是同步读）。 */
sealed interface OnboardingStart {

    /** 首启（或引导版本升级后）→ 从引导屏起步。 */
    data object FirstRun : OnboardingStart

    /** 已看过当前版本的引导 → 直接进收件箱。 */
    data object Completed : OnboardingStart
}

/**
 * 引导页第 4 屏（开启 + 保护）需要的系统侧状态。
 *
 * 刻意只放两个布尔：悬浮窗权限与电池白名单。自启动权限没有任何公开的
 * 查询 API（各厂商私有），界面只能"如实引导"，不假装能检测它。
 */
data class OnboardingUiState(
    val overlayGranted: Boolean = false,
    val batteryWhitelisted: Boolean = false,
)

/**
 * 首启引导的 ViewModel（附录 T）。
 *
 * 一个 VM 服务三个消费者（各自的 hiltViewModel 实例互不共享状态，但都从
 * 同一份 prefs / 同一批 gateway 读数——**判据只有一套**）：
 * 1. `AppNavGraph`：读 [start] 决定导航起点（首启进引导，否则进收件箱）；
 * 2. `OnboardingScreen`：第 4 屏的权限/白名单状态与两个跳转动作，ON_RESUME 复查；
 * 3. 收件箱横幅：悬浮窗未授权且未被关闭时显示"去开启"，判据与自检同源。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingSettings: OnboardingSettings,
    private val bubbleGateway: FloatingBubbleGateway,
    private val keepAliveGateway: KeepAliveGateway,
) : ViewModel() {

    private val _start = MutableStateFlow<OnboardingStart?>(null)

    /** 导航起点判定。init 里同步完成（SharedPreferences 无 IO 等待），不会真的一直为 null。 */
    val start: StateFlow<OnboardingStart?> = _start.asStateFlow()

    private val _uiState = MutableStateFlow(OnboardingUiState())

    /** 引导第 4 屏的系统侧状态。UI 在 ON_RESUME 时调 [refresh] 复查。 */
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _bubbleGuideBannerVisible = MutableStateFlow(false)

    /** 收件箱引导横幅：悬浮窗未授权 && 用户没手动关掉过。 */
    val bubbleGuideBanner: StateFlow<Boolean> = _bubbleGuideBannerVisible.asStateFlow()

    init {
        // completedVersion > CURRENT_VERSION 同样视为已完成：版本号只升不减，
        // 回退版本号时不把老用户重新拽进引导（见 OnboardingSettings.CURRENT_VERSION）。
        _start.value =
            if (onboardingSettings.completedVersion >= OnboardingSettings.CURRENT_VERSION) {
                OnboardingStart.Completed
            } else {
                OnboardingStart.FirstRun
            }
        refresh()
    }

    /**
     * 复查系统侧状态（悬浮窗权限 + 电池白名单 + 横幅可见性）。
     *
     * 引导第 4 屏与收件箱横幅都在 ON_RESUME 调用：用户从系统设置授权回来，
     * 界面不用手动刷新就能看到"已完成"/横幅消失。
     */
    fun refresh() {
        val hasOverlay = bubbleGateway.hasPermission()
        _uiState.update {
            it.copy(
                overlayGranted = hasOverlay,
                batteryWhitelisted = keepAliveGateway.status().batteryOptimizationIgnored,
            )
        }
        _bubbleGuideBannerVisible.value =
            !hasOverlay && !onboardingSettings.bubbleGuideBannerDismissed
    }

    /**
     * 引导终点（走完最后一屏或任意一屏点"跳过"）：写入完成版本。
     * 导航由 UI 回调负责，这里只管持久化。
     */
    fun complete() {
        onboardingSettings.complete(OnboardingSettings.CURRENT_VERSION)
    }

    /** 第 4 屏卡片①：跳系统悬浮窗授权页（带 package URI，直接落在本应用那一项）。 */
    fun openOverlaySettings() {
        bubbleGateway.openPermissionSettings()
    }

    /** 第 4 屏卡片②：跳系统电池优化列表（沿用附录 M 的"列表页"决策，不申请白名单权限）。 */
    fun openBatterySettings() {
        keepAliveGateway.openBatteryOptimizationSettings()
    }

    /** 收件箱横幅的关闭（附录 T：横幅只此一条、可关闭）。写 prefs，重启后不再出现。 */
    fun dismissBubbleGuideBanner() {
        onboardingSettings.dismissBubbleGuideBanner()
        _bubbleGuideBannerVisible.value = false
    }
}
