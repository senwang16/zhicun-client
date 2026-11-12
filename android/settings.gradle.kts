pluginManagement {
    // GitHub Actions 会设置 CI=true。
    // 阿里云镜像对部分海外 runner 返回 502，而 Gradle 把 5xx 视为该构件的**致命错误**，
    // 不会继续尝试后面的 Maven Central —— 表现为"KSP 插件解析不到"，而 curl 明明是 200。
    // 因此 CI 上跳过国内镜像，直接走官方源；本地开发仍优先用镜像（速度差 50 倍）。
    val isCi = System.getenv("CI").toBoolean()

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 国内镜像：Maven Central 直连实测约 300 KB/s，阿里云镜像约 18 MB/s。
        // 官方源保留在后面作为兜底，镜像缺包时仍能解析。
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    val isCi = System.getenv("CI").toBoolean()
    repositories {
        google()
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
    }
}

rootProject.name = "ZhiCun"
include(":app")