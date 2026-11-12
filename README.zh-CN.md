# 智存 · ZhiCun

**随手一存，AI 替你整理。**

智存是一款 Android 优先的个人信息收集与 AI 知识管理应用：把任何 App 里的文字、链接、图片、
截图、文件和语音存进来，由 AI 生成摘要、标签、OCR 文字、转写与语义索引。
原始内容只存在手机上，云端处理默认关闭，可以接官方服务，也可以接你自己的模型。

> **当前状态：pre-1.0（v0.1.0）。** 已在日常使用中跑通全部核心链路，但接口、数据库结构与
> 配置格式仍可能变化；升级前请先导出数据。英文版 README 见 [README.md](README.md)。

---

## 它解决什么问题

多数笔记工具要求你在读东西的那一刻就决定"放哪个文件夹"。智存反过来：
**先存下来，之后靠搜索找回来**。

- **收录只要一个动作**：系统分享、剪贴板、悬浮气泡、桌面小组件、语音录制。
- **本地优先**：记录原文、图片、录音与派生内容都在应用私有目录；不登录、不依赖云也能用。
- **AI 可替换**：摘要、向量、转写是三组独立配置，可用官方网关、自己的 API Key，或自建服务。
- **关键词永远在**：本机 FTS 全文检索是底座，语义检索只是叠加，不是替代。

## 功能

| 模块 | 内容 |
|---|---|
| 收录 | 系统分享面板、悬浮气泡（剪贴板 / 记一笔 / 录音 / 截图）、桌面小组件、手动新建、应用内录音 |
| 内容 | 文字、链接、图片、截图、音视频文件、PDF 等；一条记录可带多个附件 |
| 本机能力 | ML Kit 中文 OCR、网页正文提取与快照、SQLite FTS4 全文检索 |
| AI（需开启） | 摘要、短标题、标签、语音转写、向量（语义搜索与相关推荐） |
| 整理 | 按日期分组的收件箱、收藏、置顶、归档、用户/AI 标签、合集（多条记录合成一份摘要） |
| 检索 | 关键词 + 语义融合排序、最近搜索、标签筛选、归档箱搜索 |
| 隐私控制 | 云端总开关、本机敏感内容检测（API Key / 手机号 / 证件号 / 银行卡号）、逐条确认后才上传、网页抓取需单独确认 |
| 数据自主 | ZIP 导出（记录 + 附件 + 可选 AI 派生内容）、彻底删除与空间清理 |

## 工作方式

```
┌────────────────────────── Android 客户端 ──────────────────────────┐
│ 收录 → Room（records / assets / derived_contents / embedding_meta）│
│          │                                                         │
│          ├─ OCR（ML Kit，本机）                                     │
│          ├─ 网页正文 + 快照（OkHttp + jsoup）                        │
│          ├─ 语音转写  ─┐                                            │
│          ├─ 摘要/标签 ─┼─► AI Provider（官方网关 或 自定义直连）      │
│          └─ 向量化    ─┘                                            │
│                                                                     │
│ 搜索 = FTS4 关键词 ∪ 库内向量余弦（同模型 + 同来源）                 │
└─────────────────────────────────────────────────────────────────────┘
```

记录与 AI 派生内容是分表的：派生内容可重建、可覆盖，原文永远不会被 AI 改写。
每条向量都记录模型与来源，换 embedding 模型不会静默把两套不可比的向量混在一起。

## 两种使用模式

1. **官方服务**：设置页填官方网关地址与访问令牌，开箱即用。供应商密钥只在服务端；
   官方服务由维护者运营，**服务端代码不在本仓库内**。
2. **自己的模型**：摘要、向量、转写分别填 API 地址、Key 与模型名，任何 OpenAI 兼容接口都行
   （OpenAI、硅基流动、商汤、DeepSeek、Ollama、vLLM……）。见 [docs/ai-provider.md](docs/ai-provider.md)。

云端处理默认关闭。不开启也能收录、OCR、抓网页、关键词搜索与导出。
只配自己的三方模型即可完整体验 AI 功能，不需要部署任何服务端。

## 编译 Android 客户端

需要 JDK 17+（JDK 21 实测）、Android SDK（platform 36、build-tools 36）。

```bash
cd android
# 指定 SDK 路径（或设置 ANDROID_HOME）
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

./gradlew :app:assembleDebug          # 产物在 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # JVM + Robolectric 单测
```

Windows 下用 `gradlew.bat`。debug 包用 debug 签名，可直接 `adb install -r` 安装。
正式包目前**未签名**（见 `app/build.gradle.kts` 的说明），发布前必须先建独立发布密钥。

联调时可把网关烧进构建参数（**不要提交真实令牌**）：

```bash
./gradlew :app:assembleDebug -PaiGatewayUrl=https://你的网关 -PaiGatewayToken=...
```

## 文档

- [docs/architecture.md](docs/architecture.md) — 模块划分、数据模型、AI 路由
- [docs/development.md](docs/development.md) — 编译、测试、约定、真机验收清单
- [docs/ai-provider.md](docs/ai-provider.md) — 三组 Provider 配置与已验证供应商
- [docs/faq.md](docs/faq.md) — 常见问题与失败原因
- [LICENSE](LICENSE) / [NOTICE](NOTICE) — 许可证与第三方声明
- [README.md](README.md) — English README

## 参与贡献

欢迎 Issue 与 Pull Request。开始之前请先读 [CONTRIBUTING.md](CONTRIBUTING.md) 与
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。安全问题走 [SECURITY.md](SECURITY.md)，不要开公开 Issue。

## 隐私

无账号、无统计 SDK、无广告。内容默认不出设备，开启云端 AI 后的数据流向见 [PRIVACY.md](PRIVACY.md)。

## 许可证

智存使用 **Apache License 2.0**，全文见 [LICENSE](LICENSE)。它允许商用、修改与再分发，
附带明确的专利授权；再分发时保留许可证与署名声明即可。

第三方署名与唯一的例外——随包内置的 Google ML Kit OCR 模型（受 Google ML Kit 条款约束，
**不属于** Apache-2.0 的授权范围）——都写在 [NOTICE](NOTICE) 里。
「智存 / ZhiCun」名称与应用图标是项目标识。

贡献按同一许可证接收，无需签署 CLA；见 [CONTRIBUTING.md](CONTRIBUTING.md)。