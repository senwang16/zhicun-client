package com.zhicun.app.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 云端 AI 开关的持久化（M14）。
 *
 * 默认值单独钉一条：它决定新用户装完后"摘要在不在"，
 * 也决定这个 App 的第一印象是"能用"还是"什么都不会"。
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesAiSettingsTest {

    private fun newSettings() = PreferencesAiSettings(RuntimeEnvironment.getApplication())

    @Test
    fun Default_isEnabled() {
        assertTrue("键不存在时按产品默认值走（允许云端处理）", newSettings().cloudAiEnabled)
    }

    @Test
    fun Disable_persistsAcrossInstances() {
        newSettings().setCloudAiEnabled(false)

        // 换个实例读：调度侧与设置页是两个独立注入点，必须共享同一份值
        assertFalse(newSettings().cloudAiEnabled)
    }

    @Test
    fun ReEnable_persists() {
        val settings = newSettings()
        settings.setCloudAiEnabled(false)
        settings.setCloudAiEnabled(true)

        assertTrue(settings.cloudAiEnabled)
    }
}
