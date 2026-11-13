package com.zhicun.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 构建契约测试。
 *
 * 锁定 DEVELOPMENT_PLAN.md M1 验收标准中「构建配置不可被误改」的部分：
 * 包名与构建变体标识。任何人在后续模块中误改 app/build.gradle.kts，这里会第一时间失败，
 * 而不是等到系统分享接收（M4）或权限适配时才发现包名漂移。
 *
 * 说明：minSdk / targetSdk 不在 BuildConfig 中生成（AGP 8.13 的 BuildConfig 只有
 * DEBUG / APPLICATION_ID / BUILD_TYPE / VERSION_CODE / VERSION_NAME），
 * 因此这两项在「合并后的 APK Manifest」上核验（见 DEVELOPMENT_PLAN.md M1 完成说明），
 * 不在本测试中断言，避免锁定一个并不存在的字段。
 */
class BuildConfigContractTest {

    @Test
    fun BuildConfig_applicationId_isStablePackage() {
        assertEquals("com.zhicun.app", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun BuildConfig_debugVariant_isDebugBuild() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("debug", BuildConfig.BUILD_TYPE)
    }

    @Test
    fun BuildConfig_versionName_matchesScaffoldVersion() {
        assertEquals("0.1.2", BuildConfig.VERSION_NAME)
        assertEquals(3L, BuildConfig.VERSION_CODE.toLong())
    }
}
