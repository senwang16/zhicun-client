package com.zhicun.app.service

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡"视口变化后重新贴边"的**结构守卫**（2026-10-24）。
 *
 * ## 为什么这条只能测结构
 * 几何计算是纯函数，`BubbleGeometryTest` 已经穷举了；本次 bug 不在算错，而在**没人触发重算**：
 * 横屏里贴边算一次 `x = 1920 - 144`，转回竖屏（宽 1080）后没有任何代码再算，气泡就永久留在屏幕外。
 * 这种"少了一次调用"的问题，只有在**删掉监听**时会红的测试才拦得住。
 *
 * `FloatingBubbleService` 是 `@AndroidEntryPoint`，Robolectric 里实例化它需要
 * `hilt-android-testing` + `HiltTestApplication`（本工程刻意没引，见 ShareIntakeCoordinatorTest），
 * 所以这里用反射钉住两个机制：配置变化回调、显示器监听字段。
 * 行为验证走模拟器转屏 + `logcat | grep "视口变为"` + 截图（见 DEVELOPMENT_PLAN 附录 O）。
 *
 * 这两个断言看着"很薄"，但它们正是把"再删掉监听"变成一次红测试。
 */
class FloatingBubbleServiceViewportTest {

    @Test
    fun Service_overridesOnConfigurationChanged() {
        val declared = FloatingBubbleService::class.java.declaredMethods
            .filter { it.name == "onConfigurationChanged" }

        assertEquals(
            "Service 必须自己覆盖 onConfigurationChanged：转屏后不重算贴边坐标，" +
                "气泡会留在横屏算出的 x 上，竖屏直接看不见（2026-10-24 用户实测）",
            1,
            declared.size,
        )
        assertEquals(Configuration::class.java, declared.single().parameterTypes.single())
    }

    @Test
    fun Service_holdsDisplayListener() {
        val hasListener = FloatingBubbleService::class.java.declaredFields.any {
            DisplayManager.DisplayListener::class.java.isAssignableFrom(it.type)
        }

        assertTrue(
            "Service 必须持有 DisplayManager.DisplayListener：远控/投屏类应用会改显示器分辨率" +
                "而未必触发配置变化，没有这个监听气泡就不会重新贴边",
            hasListener,
        )
    }
}
