import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // M9 起：AI 网关 DTO 用 kotlinx.serialization（Version Catalog 里早已声明，此处启用）
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * AI 网关默认值（联调用）。
 *
 * 取值优先级：命令行 `-PaiGatewayUrl/-PaiGatewayToken` > 根目录 `local.properties`
 * （gitignored，键同名，推荐——正式域名与令牌写在这里，不入库）> 空。
 * 空值 = 网关未配置 → 云端能力（转写/摘要/向量）如实降级为"未启用"。
 * 用户在设置页（M14）填写的值优先于这里的构建参数。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val aiGatewayUrl: String =
    (project.findProperty("aiGatewayUrl") as String?) ?: localProps.getProperty("aiGatewayUrl").orEmpty()
val aiGatewayToken: String =
    (project.findProperty("aiGatewayToken") as String?) ?: localProps.getProperty("aiGatewayToken").orEmpty()

/**
 * 发布签名配置（2026-11-13）。
 *
 * 读取 `android/keystore.properties`（gitignored，格式见 `keystore.properties.example`）。
 * 没有这个文件时 release 构建仍是**未签名**的 —— 这是贡献者/CI 的默认状态，
 * 公开仓不应该包含任何人的签名密钥。
 *
 * 密钥本身放在仓库外（当前是 `F:\project\zhicun-keys\zhicun-release.jks`），
 * `keystore.properties` 只存路径与口令，两者都不入库。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String? = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val hasReleaseKey: Boolean = releaseStoreFile != null

/** 转义成 Kotlin/Java 字符串字面量内容，避免属性里混入引号把 BuildConfig 写坏。 */
fun String.asBuildConfigLiteral(): String = replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.zhicun.app"
    // SDK 版本统一收在 Version Catalog（审核 #5 整改），此处只引用
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.zhicun.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AI 网关默认值（见文件顶部说明）。空串 = 未配置，云端能力降级。
        buildConfigField(
            "String",
            "AI_GATEWAY_BASE_URL",
            "\"${aiGatewayUrl.asBuildConfigLiteral()}\"",
        )
        buildConfigField(
            "String",
            "AI_GATEWAY_TOKEN",
            "\"${aiGatewayToken.asBuildConfigLiteral()}\"",
        )

        // 只保留 ARM 架构（M16 偏差 D-2 的裁决，2026-10-22 用户拍板）。
        // ML Kit 中文 OCR 自带四份 ABI 拷贝约 39MB，其中 x86/x86_64 只有模拟器用得上，
        // 真机全是 ARM。砍掉后 release 约 30MB；真机不受影响。
        // -PnoAbiFilter：模拟器（x86_64）测试包的正规出口。该参数**只允许 debug 变体**：
        // 检测到 release 类任务时直接失败（2026-10-22 审核 #2），防止本地临时参数
        // 混进发布产物变成 55MB 的全 ABI 包。
        if (project.hasProperty("noAbiFilter")) {
            val wantsRelease = gradle.startParameter.taskNames.any {
                it.lowercase().contains("release") || it.lowercase().contains("bundle")
            }
            if (wantsRelease) {
                throw GradleException(
                    "-PnoAbiFilter 仅限模拟器 debug 测试包（assembleDebug），禁止用于 release/bundle 构建"
                )
            }
        } else {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        debug {
            // 不加 applicationIdSuffix：M1 验收要求 applicationId 恒为 com.zhicun.app，
            // 且后续模块的 intent-filter（分享接收）需与正式包名一致以便真机验证。
            isMinifyEnabled = false
        }
        release {
            // 2026-10-24 全项目审查 L-3：刻意保持关闭，直到有人在真机上跑过一轮 R8 产物。
            // 打开压缩混淆会改变交付形态（类名、反射、ML Kit / Hilt / Room 的 keep 规则），
            // 而单元测试跑的都是未混淆的 class —— 混淆引入的问题只能在真机暴露。
            // 真要开，必须同时：① 补 proguard 规则 ② 真机跑一遍 OCR/转写/分享/悬浮球
            // ③ 更新 M7 偏差 D-2 与本文档。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有 keystore.properties 时签名；没有则保持未签名（贡献者/CI 的默认状态）
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * 运行时切语言（P11-02）要求两种语言资源都随包下发。
     *
     * App Bundle 默认按语言拆分（用户下载时只带系统语言），而本应用允许在设置页
     * 任意切换中/英 —— 被拆走的语言在离线切换后会直接回退成默认英文。APK 分发不受
     * 影响，但一旦改走 AAB/Play，这里必须关掉语言拆分。
     */
    bundle {
        language {
            enableSplit = false
        }
    }

    // 说明：源码目录为 src/main/kotlin 与 src/test/kotlin（Kotlin 插件默认支持这两个目录，
    // 无需额外声明 srcDirs；曾尝试 java.srcDir 兜底，反而导致单测运行时类路径异常）。

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric 需要访问合并后的资源与 Manifest
            isIncludeAndroidResources = true
        }
    }

    lint {
        // 版本天花板是刻意决策（见 gradle/libs.versions.toml 头部规则）：compileSdk 36 是硬约束，
        // 因此"存在更新版本"类提示在本项目恒为噪声，会掩盖真问题，统一关闭。
        disable += setOf("NewerVersionAvailable", "GradleDependency")
        // x86_64 支持被 ABI 裁剪主动放弃（release 瘦身，2026-10-22 拍板）；
        // 模拟器包走 -PnoAbiFilter，属已知取舍而非缺陷。
        disable += "ChromeOsAbiSupport"
    }
}

// Room 导出 schema，供后续版本做迁移校验与 diff（M2 起每次改表结构都会更新 schemas/）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric 运行时需要下载 android-all 镜像包（每个 SDK 约 100MB+），
// 默认从 Maven Central 拉取仅 ~300KB/s，这里指向阿里云镜像（实测 ~18MB/s）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://maven.aliyun.com/repository/central",
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // --- Compose（BOM 统一版本）---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Hilt（T-002 已决策）---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- 数据层（M2）---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- 后台任务（M6，T-002 Hilt + WorkManager 组合）---
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- OCR（M7，T-005 决策：捆绑中文模型，离线可用）---
    implementation(libs.mlkit.text.recognition.chinese)

    // --- 网页提取（M8）---
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // --- AI 网关（M9 转写 / M11 摘要 / M12 向量）---
    implementation(libs.kotlinx.serialization.json)

    // --- 日历视图（M19 收件箱按日期筛选弹层；仅 InboxCalendarSheet.kt 使用）---
    implementation(libs.calendar.compose)

    // --- 测试 ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI 测试（M3 起）：跑在 Robolectric 上，无需真机即可验证渲染与点击
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
