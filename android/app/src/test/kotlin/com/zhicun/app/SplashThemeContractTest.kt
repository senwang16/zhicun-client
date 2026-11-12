package com.zhicun.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 开屏页契约（附录 T，系统级 SplashScreen）。
 *
 * 守卫三条"只能有一处真源"的引用关系（PLAYBOOK 第 1 条：同一事实不能有两套判据）：
 * 1. 开屏背景 = 启动图标底色 `@color/ic_launcher_background` —— 开屏视觉就是
 *    "放大铺满的启动图标"，换品牌色时自动跟变，不允许把色值复制到第二个资源里；
 * 2. 开屏图标 = 启动图标前景 `@drawable/ic_launcher_foreground` —— 同一份 Logo；
 * 3. MainActivity 的主题必须是 `Theme.ZhiCun.Starting`，且该主题的
 *    `postSplashScreenTheme` 指回正式主题 `Theme.ZhiCun` ——
 *    `installSplashScreen()` 靠它完成主题切换，指错表现为"开屏后主题不切换"。
 */
@RunWith(RobolectricTestRunner::class)
class SplashThemeContractTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun Starting_theme_derivesSplashVisualFromLauncherIconResources() {
        val attrs = context.obtainStyledAttributes(
            null,
            intArrayOf(
                androidx.core.splashscreen.R.attr.windowSplashScreenBackground,
                androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon,
                androidx.core.splashscreen.R.attr.postSplashScreenTheme,
            ),
            0,
            com.zhicun.app.R.style.Theme_ZhiCun_Starting,
        )
        try {
            assertEquals(
                "开屏背景必须引用启动图标底色（同一份品牌色）",
                com.zhicun.app.R.color.ic_launcher_background,
                attrs.getResourceId(0, 0),
            )
            assertEquals(
                "开屏图标必须引用启动图标前景（同一份 Logo）",
                com.zhicun.app.R.drawable.ic_launcher_foreground,
                attrs.getResourceId(1, 0),
            )
            assertEquals(
                "开屏结束必须切回正式主题",
                com.zhicun.app.R.style.Theme_ZhiCun,
                attrs.getResourceId(2, 0),
            )
        } finally {
            attrs.recycle()
        }
    }

    @Test
    fun MainActivity_manifestTheme_isStartingTheme() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        // Robolectric 的 PackageParser 会把 themeResource 填成"资源 ID 的十进制字符串"
        // （真机上才是 "Theme.ZhiCun.Starting" 这种名字），两种形态都归一到资源名再比。
        val themeResId = info.theme.takeIf { it != 0 }
            ?: info.themeResource?.toString()
                ?.let { raw -> runCatching { Integer.parseInt(raw.trim()) }.getOrNull() }
            ?: 0
        assertEquals(
            "MainActivity 主题必须是开屏主题，否则 installSplashScreen() 读不到开屏配置",
            "Theme.ZhiCun.Starting",
            context.resources.getResourceEntryName(themeResId),
        )
    }
}
