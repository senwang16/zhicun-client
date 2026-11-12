package com.zhicun.app.domain.model

/**
 * 一条待保存记录的输入载荷（Domain 模型，不含任何 Android 类型）。
 *
 * 两个来源共用它：
 * - **系统分享**（M4）：`service/share/SharePayloadParser` 把 `Intent` 解析成它；
 * - **App 内手动新建**（M5）：`CreateRecordViewModel` 把输入框文字 + 用户选的 URI 组装成它。
 *
 * 名字叫 `RecordInput` 而不是 `SharedPayload`：M5 起它不再只承载"分享"这一条路径，
 * 继续叫 shared 会让"手动新建也在用 SharedPayload"看起来像 bug。
 *
 * 为什么不让 UseCase 直接吃 `Intent`：`Intent` 是 Android framework 类型，
 * domain 层一旦引用它，`SaveRecordUseCase` 就再也无法在纯 JVM 下测试（AGENTS.md §2.3）。
 * 解析 Intent 的职责留在 `service/share` 层，UseCase 只处理这份纯数据。
 *
 * @param text 正文纯文本（发送方只给 HTML 时由解析层转成纯文本）
 * @param subject 标题（分享场景多数来自 `EXTRA_SUBJECT`，手动新建暂不使用）
 * @param sourceApp 来源应用名（只对系统分享有意义，手动新建为 `null`）
 * @param supportedUris 受支持的附件；不受支持的已被剔除并计入 [unsupportedMimeTypes]
 * @param unsupportedMimeTypes 本次被拒收的附件类型（用于向用户如实提示，不静默丢弃）
 * @param excessUriCount 超出单次分享附件上限、因而没有处理的条数。
 *   与 [unsupportedMimeTypes] 分开记：一个是"类型不支持"，一个是"数量超限"，
 *   提示文案与用户该采取的动作不同（2026-11-01 审核 S-5，自海外轨移植）。
 */
data class RecordInput(
    val text: String? = null,
    val subject: String? = null,
    val sourceApp: String? = null,
    val supportedUris: List<InputUri> = emptyList(),
    val unsupportedMimeTypes: List<String> = emptyList(),
    val excessUriCount: Int = 0,
) {

    /** 是否有可落库的内容（空内容不得创建记录，PRD §11）。 */
    val hasContent: Boolean
        get() = !text.isNullOrBlank() || supportedUris.isNotEmpty()
}

/**
 * 待复制的附件来源。
 *
 * `uri` 保留字符串形式而不是 `android.net.Uri`：domain 层保持零 Android 依赖，
 * 真正的 URI 解析与流读取发生在 data 层的 `ExternalAssetImporter` 实现里。
 *
 * @param mimeType 附件的**具体类型**（`image/png`、`application/pdf` 等），不可能是通配符。
 *   解析层在发送方声明通配类型时会按 URI 回查真实类型再填入（见 `SharePayloadParser.judge`）；
 *   实在查不到才为 `null`，此时分类器与 Asset 保存都按"类型未知"处理，不得据此猜测类型。
 */
data class InputUri(
    val uri: String,
    val mimeType: String?,
    /**
     * 原始文件名，仅展示用；可能为 `null`（provider 不给，或分享方只给 URI）。
     *
     * 放在输入载荷里而不是等导入器回填：新建页要在**保存之前**把名字显示在附件条上，
     * 让用户确认自己选对了文件；导入器返回的名字在保存之后才拿得到。
     */
    val displayName: String? = null,
)
