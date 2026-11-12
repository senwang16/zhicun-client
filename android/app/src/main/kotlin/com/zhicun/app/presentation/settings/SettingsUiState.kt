package com.zhicun.app.presentation.settings

import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.ai.ProviderProtocol

/**
 * 设置页状态（M13 悬浮入口 / M14 云端 AI / M15 导出与清理）。
 *
 * ## 为什么是"可由真值推导"，而不是各存一份结果
 * 界面上要显示的东西很多：开关位置、权限状态、是否该提示"去授权"、转写可用性、
 * 导出进度、上一次操作的结果。如果每样各存一个字段，就一定会有不一致的时刻
 * （拨开关后忘了更新提示、授权回来后忘了清提示）。这里只存**真值**，其余全部现算。
 *
 * @param floatingEnabled 用户的开关意图（来自 prefs，与服务是否真的起来了无关）
 * @param hasOverlayPermission 系统是否已授予悬浮窗权限（来自 framework）
 * @param autoClipboardEnabled 点气泡是否自动收录剪贴板（B2，来自 prefs；悬浮关闭时此开关无意义）
 * @param autoRestoreOnBoot 重启手机 / 覆盖安装后是否自动恢复气泡（来自 prefs；同样依附于悬浮开关）
 * @param alwaysVisible 「悬浮窗常在」（2026-10-26）：开启后气泡不再因外部触摸收成贴边细条（来自 prefs）
 * @param longPressRecord 「长按录音」（2026-10-26）：在完整气泡上长按即录音、抬手保存（来自 prefs）
 * @param keepAlive 后台常驻的系统条件（电池优化 / 通知 / App Standby）。null = 尚未读取，
 *   界面据此**什么都不显示**，而不是显示"一切正常"—— 后者是假消息
 * @param cloudAiEnabled 是否允许把内容发送到云端做 AI 处理（来自 prefs）
 * @param versionName 版本名（`BuildConfig`，M14 验收要求"版本号正确显示"）
 * @param versionCode 版本号
 * @param exportRunning 导出进行中（界面禁止重复点击）
 * @param exportDone 当前阶段已处理数（跟随阶段含义：记录数或附件数）
 * @param exportTotal 当前阶段总数；0 表示"还不知道"，界面显示不确定进度
 * @param cleanupRunning 清理进行中
 * @param notice 上一次操作的结论；只用于展示，下一次操作时清空
 * @param canRetryShare 导出包还在缓存里、可以再试着打开一次分享面板（M16）
 *
 * ## 开源改造 P0：三组 AI Provider（见 `docs/开源技术落地方案.md` §六）
 * - `*BaseUrl` / `*Model` 是**可回填的**非机密字段，用于编辑；
 * - `*Configured` 是"此刻能不能真的发出去"，已把"勾官方 + 官方已配置"算进去；
 * - **API Key 与官方令牌一律不进状态**：状态会被测试快照、日志、截图带走，
 *   而它们只需要"是否已配置"这一位信息。
 *
 * @param incompatibleEmbeddingCount 使用旧向量模型/供应商、暂时进不了语义检索的记录数
 * @param vectorStatusLoaded 向量统计是否已读出来（false 时界面显示"统计中"，不显示 0）
 */
data class SettingsUiState(
    val floatingEnabled: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val autoClipboardEnabled: Boolean = false,
    /**
     * 默认 true 与 prefs 的默认值保持一致：界面初值为 false 会让首帧闪一下"已关闭"，
     * 而实际状态是开着的 —— 那一眼足以让用户以为设置被改了。
     */
    val autoRestoreOnBoot: Boolean = true,
    /** 「悬浮窗常在」：与 prefs 默认值一致（false）。 */
    val alwaysVisible: Boolean = false,
    /** 「长按录音」：与 prefs 默认值一致（true）。 */
    val longPressRecord: Boolean = true,
    val keepAlive: KeepAliveStatus? = null,
    val cloudAiEnabled: Boolean = true,

    // ------------------------------------------------------------ AI Provider（P0）
    /** 用户是否选择官方服务（来自 prefs）。 */
    val useOfficialService: Boolean = true,
    /** 官方网关地址（不是机密，可回填到输入框）。 */
    val officialBaseUrl: String = "",
    val officialConfigured: Boolean = false,
    val chatBaseUrl: String = "",
    val chatModel: String = "",
    val chatConfigured: Boolean = false,
    /** Chat 的协议方言（OpenAI 兼容 / Anthropic）；Embedding 与 ASR 只有 OpenAI 方言。 */
    val chatProtocol: ProviderProtocol = ProviderProtocol.OPENAI,
    val embedBaseUrl: String = "",
    val embedModel: String = "",
    val embedConfigured: Boolean = false,
    val asrBaseUrl: String = "",
    val asrModel: String = "",
    val asrConfigured: Boolean = false,
    /** 使用旧向量模型/供应商、需要重建的记录数（语义检索暂时覆盖不到）。 */
    val incompatibleEmbeddingCount: Int = 0,
    /** 向量统计是否已读出：false = 还没算完，界面不能显示"0 条"，那会被读成"全都好"。 */
    val vectorStatusLoaded: Boolean = false,
    /** 重建任务提交中（防重复点击：一次提交会排一批后台任务）。 */
    val reEmbedRunning: Boolean = false,
    /** AI 配置区的操作结论；与 [notice]（数据区）分开，见 [AiNotice]。 */
    val aiNotice: AiNotice? = null,

    val versionName: String = "",
    val versionCode: Long = 0,
    val exportRunning: Boolean = false,
    val exportDone: Int = 0,
    val exportTotal: Int = 0,
    val cleanupRunning: Boolean = false,
    val notice: SettingsNotice? = null,
    val canRetryShare: Boolean = false,
) {
    /**
     * 用户想要气泡、但系统还不允许。
     *
     * 此时界面必须给出"去系统设置授权"的明确出口：只把开关拨到开却不解释
     * 为什么什么都没有发生，是这类功能最常见的挫败点。
     */
    val needsPermission: Boolean
        get() = floatingEnabled && !hasOverlayPermission

    /**
     * 是否展示"后台常驻"自检区块。
     *
     * 只在悬浮开关开着时展示：气泡关掉时这些限制没有任何后果，
     * 摆一屏"未加入白名单/受限"只会让用户以为自己弄坏了什么。
     * 同时要求 [keepAlive] 已读取 —— 没读到就整块不显示，不猜。
     */
    val showKeepAlive: Boolean
        get() = floatingEnabled && keepAlive != null

    /**
     * 语音转写是否可用。
     *
     * 转写没有独立开关：它和摘要、向量一样是把内容发到云端，跟随同一个开关。
     * 做成两个开关只会产生"转写开着、云端关着"这种无法执行的矛盾态。
     *
     * 开源改造后还要看**转写 Provider 配没配**：以前只有官方一条路，
     * 现在自定义模式下没填 ASR 就不该显示"当前可用"——那会让用户以为录音能转写，
     * 实际任务会以"未配置"终态失败。
     */
    val transcriptionAvailable: Boolean
        get() = cloudAiEnabled && asrConfigured

    /** 摘要与标签是否可用（自定义模式下只配了向量时，不该显示"AI 会自动整理"）。 */
    val chatAvailable: Boolean
        get() = cloudAiEnabled && chatConfigured

    /** 导出、清理或向量重建进行中：都是"正在动数据"的长任务，同时只该跑一个。 */
    val busy: Boolean
        get() = exportRunning || cleanupRunning || reEmbedRunning

    /** "版本 0.1.0（1）"这类展示串由界面拼，这里只判断是否有可显示的值。 */
    val hasVersion: Boolean
        get() = versionName.isNotBlank()

    /** 进度是否已知（阶段总数可能是 0，此时只显示"正在导出…"）。 */
    val hasDeterminateProgress: Boolean
        get() = exportRunning && exportTotal > 0
}

/**
 * 一次操作的结论（M15）。
 *
 * 界面据此选文案并带上数字，而不是让 ViewModel 拼字符串 ——
 * 这样状态里不出现 Android 资源 id，纯 JVM 也能断言。
 */
sealed interface SettingsNotice {

    /** 导出成功。[missingAssets] > 0 时要如实告诉用户包是不完整的。 */
    data class ExportDone(
        val records: Int,
        val assets: Int,
        val missingAssets: Int,
    ) : SettingsNotice

    data object ExportFailed : SettingsNotice

    /**
     * 包生成了，但分享面板没打开（M16）。
     *
     * 不能与 [ExportDone] 合并：用户拿到"导出完成"却找不到文件，而这个包只在应用私有缓存里，
     * 他自己是翻不出来的。分开成两种结论，界面才能给出"重新打开分享面板"的出口。
     */
    data class ExportNotShared(
        val records: Int,
        val assets: Int,
        val missingAssets: Int,
    ) : SettingsNotice

    /** @param failedFiles 文件删不掉的数量（磁盘空间没释放干净） */
    data class CleanupDone(
        val records: Int,
        val assets: Int,
        val failedFiles: Int,
    ) : SettingsNotice

    /** 没有可清理的记录：这不是错误，是"你已经是干净的"。 */
    data object CleanupNothing : SettingsNotice

    data object CleanupFailed : SettingsNotice
}

/**
 * AI 服务配置区的操作结论（开源改造 P1）。
 *
 * 与 [SettingsNotice] 分开而不是塞进同一个 sealed 类型：两者的展示位置不同
 * （数据卡片 vs AI 卡片），合在一起会让 `noticeText` 必须为一个不会出现在那里的
 * 状态写文案，也让"数据操作的结论"与"配置操作的结论"混成一条时间线。
 */
sealed interface AiNotice {

    /**
     * 向量重建已提交。
     *
     * @param count 实际重新排队的记录数
     *
     * 文案只说"已提交"而不说"已完成"：重建在后台逐条跑，完成时间取决于条数与网络。
     */
    data class ReEmbedSubmitted(val count: Int) : AiNotice

    /** 没有需要重建的向量（点按钮时恰好都已完成）。 */
    data object ReEmbedNothing : AiNotice

    data object ReEmbedFailed : AiNotice
}
