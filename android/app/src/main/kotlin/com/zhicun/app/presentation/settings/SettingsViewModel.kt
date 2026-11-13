package com.zhicun.app.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.BuildConfig
import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.ProviderProtocol
import com.zhicun.app.domain.export.ArchiveShareLauncher
import com.zhicun.app.domain.export.ExportOptions
import com.zhicun.app.domain.export.ExportRecordsUseCase
import com.zhicun.app.domain.floating.FloatingBubbleGateway
import com.zhicun.app.domain.floating.FloatingBubbleSettings
import com.zhicun.app.domain.floating.KeepAliveGateway
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.EmbeddingSpace
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.FeedbackLauncher
import com.zhicun.app.domain.settings.UiLanguageSettings
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.domain.usecase.ReEmbedAllUseCase
import com.zhicun.app.domain.usecase.ResumeCloudJobsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel（M13 悬浮入口 / M14 云端 AI / M15 导出与清理）。
 *
 * 用 `MutableStateFlow` 而不是像收件箱那样从 Room 的 Flow 合成：设置项是**用户操作驱动**的
 * 一次性读取，没有持续变化的数据源。导出进度是例外，但它是"操作的副产品"，
 * 生命周期与那次点击绑定，放同一个状态里最直观。
 *
 * ## 为什么每次操作后都 `refresh()` 回读
 * 状态的唯一来源是 prefs 与 framework，不是本次操作的入参。回读的代价是几次同步读取，
 * 换来的是"界面显示的值 = 实际生效的值"这条不变量。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: FloatingBubbleSettings,
    private val gateway: FloatingBubbleGateway,
    private val keepAliveGateway: KeepAliveGateway,
    private val aiSettings: AiSettings,
    private val aiProviders: AiProviderSettings,
    private val embeddings: EmbeddingRepository,
    private val reEmbedAll: ReEmbedAllUseCase,
    private val feedbackLauncher: FeedbackLauncher,
    private val exportRecords: ExportRecordsUseCase,
    private val cleanupDeletedRecords: CleanupDeletedRecordsUseCase,
    private val shareLauncher: ArchiveShareLauncher,
    private val resumeCloudJobs: ResumeCloudJobsUseCase,
    private val uiLanguage: UiLanguageSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 向量统计要查库，不能放进 readState（那是同步的、每次拨开关都会调的路径）
        refreshVectorStatus()
    }

    /**
     * 上一次导出生成、但没能交出去的包（M16）。
     *
     * 只为了"重新打开分享面板"这一个动作存在：分享失败时把它留着，
     * 用户点重试就不用再写一遍几十 MB 的 ZIP。分享成功后立刻置空 ——
     * 留着会让重试按钮指向一个已经被接收方读走的文件。
     */
    private var pendingShare: PendingShare? = null

    /**
     * 重新对齐真实状态：先让服务与"开关 + 权限"一致，再如实读回。
     *
     * 设置页 `ON_RESUME` 时调用。**必须先 sync 再读**：用户可能刚在系统设置页里授权完返回，
     * 也可能从通知栏回来把权限撤了。只读不 sync 的后果是"授权成功后气泡依然不出现，
     * 得退出去再重进"—— 这条路径上没有别的时机会拉起服务。
     *
     * sync 是幂等的（已在运行则跳过启动、未开则停止），因此每次进页面都调一次是安全的。
     */
    fun refresh() {
        gateway.sync()
        _uiState.update { readState(it) }
    }

    /**
     * 用户拨动悬浮气泡开关。
     *
     * 顺序不能反：**先落偏好、再 [FloatingBubbleGateway.sync]**，因为 sync 读的是 prefs
     * 里的开关值。反过来的话这次同步用的还是旧意图，表现为"拨了开关没反应，再拨一次才生效"。
     *
     * 无权限时**不**把开关回退成关：用户表达的意图是"我要用"，拨到关会让他以为什么都没发生。
     * 保留"开"、同时由 [SettingsUiState.needsPermission] 亮出授权入口，授权回来后
     * 下一次 `ON_RESUME` 的 [refresh] 会顺带把服务拉起来。
     */
    fun onFloatingToggled(enabled: Boolean) {
        settings.setEnabled(enabled)
        refresh()
    }

    /**
     * 用户拨动"点气泡自动收录剪贴板"开关（B2）。
     *
     * 只写偏好，不调 [refresh]：这个开关不影响服务的启停（悬浮服务照常运行，
     * 只是点气泡的行为从"展开面板"变成"直接收录"），与服务对齐无关。
     * 悬浮开关关闭时该设置仍可保留 —— 重新打开悬浮时沿用。
     */
    fun onAutoClipboardToggled(enabled: Boolean) {
        settings.setAutoClipboard(enabled)
        _uiState.update { readState(it) }
    }

    /**
     * 用户拨动"开机自动恢复气泡"开关（2026-10-24）。
     *
     * 只写偏好、不 [refresh]：它**不影响当前这次运行**（气泡已经在那儿了），
     * 只决定下一次开机 / 覆盖安装时要不要恢复。顺手 sync 一下只会让"拨了开关气泡闪一下"。
     */
    fun onAutoRestoreOnBootToggled(enabled: Boolean) {
        settings.setAutoRestoreOnBoot(enabled)
        _uiState.update { readState(it) }
    }

    /**
     * 用户拨动「悬浮窗常在」开关（2026-10-26）。
     *
     * 只写偏好、不 [refresh]：它改变的是**运行中服务的形态策略**（服务在收到外部触摸时
     * 重新读该偏好），不涉及服务启停。即时生效由服务侧读取保证，无需重启。
     */
    fun onAlwaysVisibleToggled(enabled: Boolean) {
        settings.setAlwaysVisible(enabled)
        // 先落偏好再让服务即时生效：服务读的是偏好（见 FloatingBubbleController.applyAlwaysVisible）
        gateway.applyAlwaysVisible()
        _uiState.update { readState(it) }
    }

    /**
     * 用户拨动「长按录音」开关（2026-10-26）。
     *
     * 同样只写偏好：不涉及服务启停，服务在长按判定时实时读该偏好。
     */
    fun onLongPressRecordToggled(enabled: Boolean) {
        settings.setLongPressRecord(enabled)
        _uiState.update { readState(it) }
    }

    /** 打开系统的悬浮窗授权页。 */
    fun onGrantPermissionClick() {
        gateway.openPermissionSettings()
    }

    /**
     * 打开系统电池优化列表（2026-10-24 保活自检）。
     *
     * 用户加白后按返回回到本页，`ON_RESUME` 的 [refresh] 会重新读一次状态，
     * 那一行随之从"未加入白名单"变成"已加入" —— 不需要用户手动刷新页面。
     */
    fun onOpenBatterySettingsClick() {
        keepAliveGateway.openBatteryOptimizationSettings()
    }

    /** 打开本应用的通知设置页（通知被关时前台服务的可见性会消失）。 */
    fun onOpenNotificationSettingsClick() {
        keepAliveGateway.openNotificationSettings()
    }

    /**
     * 用户拨动"云端 AI 处理"开关（M14）。
     *
     * 关：只写偏好、不碰已经在队列里的任务 —— 这些任务由各 Worker 在执行时用
     * `CloudUploadGate` 自查，一关就把开关读到，效果等价于"立即停止上传"。
     *
     * 开：先写偏好，再**补投**停在 `LOCAL_ONLY` 的记录（2026-10-23 审查 R-3）。
     * 整改前只写偏好，于是"关掉存的东西 → 再打开开关"之后，那些记录既没有云端任务、
     * 也没有任何重算入口，属于单向门。补投放在写偏好之后：`ResumeCloudJobsUseCase`
     * 读的是偏好，顺序反了会用旧值判闸。
     *
     * 这里**不调 [refresh]**：那是"让服务与开关对齐"的动作，属于悬浮气泡的事。
     * 拨云端开关却顺手去启停前台服务，既多余，也让"两个分组互不影响"这条变得不成立。
     */
    fun onCloudAiToggled(enabled: Boolean) {
        aiSettings.setCloudAiEnabled(enabled)
        // 保留瞬时字段（导出进度/结果提示），只重读真值
        _uiState.update { readState(it) }
        if (enabled) resumeBlockedCloudJobs()
    }

    /**
     * 把 `LOCAL_ONLY` 的记录重新送上云端。
     *
     * 失败只记日志：这是开关的**附带动作**，开关本身已经生效，
     * 不该因为补投失败弹一个错误把用户吓回去（记录页仍有重试按钮兜底）。
     */
    private fun resumeBlockedCloudJobs() {
        viewModelScope.launch {
            try {
                val resumed = resumeCloudJobs()
                if (resumed > 0) {
                    Log.i(TAG, "云端开关重开，补投 $resumed 条记录的云端任务")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "补投云端任务失败", e)
            }
        }
    }

    // ------------------------------------------------------------ 界面语言（P11）

    /**
     * 切换界面语言（P11-02）。
     *
     * 只做两件事：写偏好、同步系统"应用语言"。**不**发请求、**不**排任务、
     * **不**重算闸门 —— 已生成的结果保持原语言（R-I18N-02/05，切语言不是重生成）。
     * 重建由 Activity 负责，但仅在系统不会自己重建时才需要（见 [recreateRequiredAfterSelect]）。
     */
    fun onLanguageSelected(language: AppLanguage) {
        if (language == _uiState.value.language) return
        uiLanguage.select(language)
        _uiState.update { it.copy(language = language) }
    }

    /**
     * 切语言后是否还要 `Activity.recreate()`。
     *
     * Android 13+ 写 `LocaleManager.applicationLocales` 由系统重建 Activity，
     * 界面再调一次就是双重建（闪两下）：界面的判断依据在这里，不各自写 SDK 判断。
     */
    val recreateRequiredAfterSelect: Boolean
        get() = uiLanguage.recreateRequiredAfterSelect

    // ------------------------------------------------------------ AI Provider（开源改造 P0）

    /**
     * 切换"使用官方服务"（开源改造 P0）。
     *
     * 只改意图，不改三组自定义配置：用户切回自定义模式时，之前填的地址与模型要还在。
     * 保存后立刻刷新向量统计 —— 官方/自定义的向量空间可能不同，"待重建 X 条"必须跟着变。
     */
    fun onUseOfficialServiceToggled(enabled: Boolean) {
        aiProviders.setUseOfficialService(enabled)
        _uiState.update { readState(it) }
        refreshVectorStatus()
    }

    /**
     * 保存官方服务配置。
     *
     * @param token 输入框里的令牌；留空表示**沿用已保存的值**（界面从不回填令牌）
     */
    fun onSaveOfficialService(baseUrl: String, token: String) {
        val effectiveToken = token.trim().takeIf { it.isNotEmpty() } ?: aiProviders.officialToken
        aiProviders.saveOfficial(baseUrl, effectiveToken)
        _uiState.update { readState(it) }
        refreshVectorStatus()
    }

    /** 保存摘要/标签 Provider。空 Key 表示"不修改"，见 [AiProviderSettings.saveChat]。 */
    fun onSaveChatProvider(baseUrl: String, apiKey: String, model: String) {
        aiProviders.saveChat(baseUrl, apiKey.takeIf { it.isNotBlank() }, model)
        _uiState.update { readState(it) }
    }

    /**
     * 切换 Chat 的协议方言（OpenAI 兼容 / Anthropic）。
     *
     * 立即落盘、不等"保存"：它是"先选协议再填地址/模型"的顺序，
     * 而保存按钮那一刻再写会让用户在选完协议后看到的仍是旧的空卡片状态。
     * 只写协议一列，不碰地址/Key/模型（见 [AiProviderSettings.saveChatProtocol]）。
     */
    fun onChatProtocolSelected(protocol: ProviderProtocol) {
        aiProviders.saveChatProtocol(protocol)
        _uiState.update { readState(it) }
    }

    /** 保存向量化 Provider；保存后必须重算"待重建"条数（换模型的第一现场）。 */
    fun onSaveEmbedProvider(baseUrl: String, apiKey: String, model: String) {
        aiProviders.saveEmbed(baseUrl, apiKey.takeIf { it.isNotBlank() }, model)
        _uiState.update { readState(it) }
        refreshVectorStatus()
    }

    /** 保存转写 Provider。 */
    fun onSaveAsrProvider(baseUrl: String, apiKey: String, model: String) {
        aiProviders.saveAsr(baseUrl, apiKey.takeIf { it.isNotBlank() }, model)
        _uiState.update { readState(it) }
    }

    /**
     * 批量重建向量（开源改造 P1）。
     *
     * 只重新排队 `EMBED` 任务，由既有 `EmbedWorker` 执行 —— 云端闸门、敏感确认、
     * 上游等待都还是那一套（见 [ReEmbedAllUseCase] 的类注释）。
     *
     * 文案只说"已提交"而不说"已完成"：重建是后台逐条跑的，完成时间取决于条数与网络。
     */
    fun onReEmbedClick() {
        if (_uiState.value.busy) return

        viewModelScope.launch {
            _uiState.update { it.copy(reEmbedRunning = true, aiNotice = null) }
            try {
                val space = currentVectorSpace()
                val submitted = if (space == null) 0 else reEmbedAll(space.model, space.providerKey)
                val aiNotice = if (submitted > 0) {
                    AiNotice.ReEmbedSubmitted(submitted)
                } else {
                    AiNotice.ReEmbedNothing
                }
                _uiState.update { it.copy(reEmbedRunning = false, aiNotice = aiNotice) }
                refreshVectorStatus()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(reEmbedRunning = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "提交向量重建失败", e)
                _uiState.update {
                    it.copy(reEmbedRunning = false, aiNotice = AiNotice.ReEmbedFailed)
                }
            }
        }
    }

    /**
     * 读出"待重建的向量"条数。
     *
     * 判据是当前向量空间（模型 + 来源）与库内向量不一致。取不到当前空间时
     * （还没配置向量 Provider / 库里一条向量都没有）显示 0 且标记已加载：
     * 此时"没有待重建"是事实，不是"还没算"。
     */
    private fun refreshVectorStatus() {
        viewModelScope.launch {
            val space = currentVectorSpace()
            if (space == null) {
                _uiState.update { it.copy(incompatibleEmbeddingCount = 0, vectorStatusLoaded = true) }
                return@launch
            }
            val count = try {
                embeddings.countIncompatible(space.model, space.providerKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 统计失败只让这一块保持"未加载"，不能把整页设置变成错误页
                Log.w(TAG, "统计待重建向量失败", e)
                return@launch
            }
            _uiState.update { it.copy(incompatibleEmbeddingCount = count, vectorStatusLoaded = true) }
        }
    }

    /**
     * 当前向量空间。
     *
     * - 自定义模式（或官方未配置而自定义已填）：用户填的模型 + 地址，**精确可知**；
     * - 官方模式：模型名由网关部署决定、客户端只知道上一次实际写入的那个，
     *   因此用最近一条向量元信息反推；库里还没有向量时返回 null（无从谈起重建）。
     */
    private suspend fun currentVectorSpace(): EmbeddingSpace? {
        val custom = aiProviders.embedConfig
        val useCustom = custom != null &&
            !(aiProviders.useOfficialService && aiProviders.officialConfigured)
        if (useCustom) return EmbeddingSpace(custom!!.model, custom.baseUrl)

        val latest = embeddings.latestSpace() ?: return null
        return EmbeddingSpace(
            model = latest.model,
            providerKey = aiProviders.officialBaseUrl ?: latest.providerKey,
        )
    }

    /** 打开意见反馈。 */
    fun onFeedbackClick() {
        feedbackLauncher.open()
    }

    // ------------------------------------------------------------ 导出（M15）

    /**
     * 用户在风险提示对话框里确认导出。
     *
     * 完成后**立刻**叫系统分享面板：包在 cacheDir 里，系统可能随时回收，
     * 用户过一会儿再想找就找不到了。
     *
     * 重复点击直接返回：一次导出要写几十 MB，连点两下会把同样的活干两遍。
     */
    fun onExportConfirmed(includeDerivedContent: Boolean) {
        if (_uiState.value.busy) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportRunning = true,
                    exportDone = 0,
                    exportTotal = 0,
                    notice = null,
                    canRetryShare = false,
                )
            }

            try {
                val archive = exportRecords(
                    options = ExportOptions(includeDerivedContent = includeDerivedContent),
                    appVersion = BuildConfig.VERSION_NAME,
                    onProgress = { progress ->
                        // 回调来自 IO 线程：StateFlow.update 是线程安全的，不会撕裂
                        _uiState.update { it.copy(exportDone = progress.done, exportTotal = progress.total) }
                    },
                )
                publishShareResult(
                    PendingShare(
                        file = archive.file,
                        records = archive.recordCount,
                        assets = archive.assetCount,
                        missingAssets = archive.missingAssetCount,
                    ),
                )
            } catch (e: CancellationException) {
                // 页面销毁导致的取消：把状态收回去，别留一个永远转圈的进度条
                _uiState.update { it.copy(exportRunning = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "导出失败", e)
                _uiState.update { it.copy(exportRunning = false, notice = SettingsNotice.ExportFailed) }
            }
        }
    }

    /**
     * 重新尝试打开分享面板（M16）。
     *
     * 只在"包已生成、但面板没打开"时才有意义。**不重新导出**：那要再写几十 MB，
     * 而用户此刻要的只是把已经生成的文件递出去。
     *
     * 包可能已经被系统回收（cacheDir 随时会被清）：这时不再假装还能重试，
     * 直接把 `canRetryShare` 置 false，界面上的按钮随之消失 —— 用户需要重新导出。
     */
    fun onRetryShare() {
        val pending = pendingShare ?: return
        if (!pending.file.exists()) {
            Log.w(TAG, "导出包已不存在，无法重新分享：${pending.file.name}")
            _uiState.update { it.copy(canRetryShare = false) }
            return
        }
        publishShareResult(pending)
    }

    /**
     * 按分享结果发布结论。
     *
     * `ExportDone` 与 `ExportNotShared` 必须分开：前者表示"文件已经交到系统分享面板"，
     * 后者表示"文件生成了，但用户还拿不到"。合并成一个状态的话，
     * 分享失败的用户会看到"导出完成"却在任何地方都找不到那个包。
     */
    private fun publishShareResult(pending: PendingShare) {
        val shared = shareLauncher.share(pending.file)
        pendingShare = if (shared) null else pending
        val canRetry = !shared && pending.file.exists()
        _uiState.update {
            it.copy(
                exportRunning = false,
                // 分享成功后包会被接收方读走，缓存里那份由 ZipExportWriter 按天清理，不必留引用
                canRetryShare = canRetry,
                notice = if (shared) {
                    SettingsNotice.ExportDone(
                        records = pending.records,
                        assets = pending.assets,
                        missingAssets = pending.missingAssets,
                    )
                } else {
                    SettingsNotice.ExportNotShared(
                        records = pending.records,
                        assets = pending.assets,
                        missingAssets = pending.missingAssets,
                    )
                },
            )
        }
    }

    // ------------------------------------------------------------ 清理（M15）

    /** 用户在确认对话框里确认清理。 */
    fun onCleanupConfirmed() {
        if (_uiState.value.busy) return

        viewModelScope.launch {
            _uiState.update { it.copy(cleanupRunning = true, notice = null) }

            try {
                val report = cleanupDeletedRecords()
                // 判据用 nothingDone 而不是"没删记录也没删文件"：只有 1 条记录、它的文件删不掉时，
                // 两个计数都是 0，但磁盘空间没释放，说"没有需要清理的记录"是在撒谎
                val notice = if (report.nothingDone) {
                    SettingsNotice.CleanupNothing
                } else {
                    SettingsNotice.CleanupDone(
                        records = report.recordsDeleted,
                        assets = report.assetsDeleted,
                        failedFiles = report.failedAssetFiles,
                    )
                }
                _uiState.update { it.copy(cleanupRunning = false, notice = notice) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(cleanupRunning = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "清理已删除记录失败", e)
                _uiState.update { it.copy(cleanupRunning = false, notice = SettingsNotice.CleanupFailed) }
            }
        }
    }

    /**
     * 读真实状态。
     *
     * @param previous 保留上一个状态的瞬时字段（进度、结果提示），
     *   只重新读取"真值" —— 否则每次拨开关都会把刚弹出的结果提示抹掉。
     */
    private fun readState(previous: SettingsUiState? = null): SettingsUiState = SettingsUiState(
        floatingEnabled = settings.enabled,
        hasOverlayPermission = gateway.hasPermission(),
        autoClipboardEnabled = settings.autoClipboard,
        autoRestoreOnBoot = settings.autoRestoreOnBoot,
        alwaysVisible = settings.alwaysVisible,
        longPressRecord = settings.longPressRecord,
        keepAlive = readKeepAliveStatus(),
        cloudAiEnabled = aiSettings.cloudAiEnabled,
        language = uiLanguage.current(),
        // 三组 Provider：只回填非机密字段，Key/令牌永远不进状态（见 SettingsUiState KDoc）
        useOfficialService = aiProviders.useOfficialService,
        officialBaseUrl = aiProviders.officialBaseUrl.orEmpty(),
        officialConfigured = aiProviders.officialConfigured,
        chatBaseUrl = aiProviders.chatConfig?.baseUrl.orEmpty(),
        chatModel = aiProviders.chatConfig?.model.orEmpty(),
        chatConfigured = aiProviders.chatAvailable,
        chatProtocol = aiProviders.chatProtocol,
        embedBaseUrl = aiProviders.embedConfig?.baseUrl.orEmpty(),
        embedModel = aiProviders.embedConfig?.model.orEmpty(),
        embedConfigured = aiProviders.embedAvailable,
        asrBaseUrl = aiProviders.asrConfig?.baseUrl.orEmpty(),
        asrModel = aiProviders.asrConfig?.model.orEmpty(),
        asrConfigured = aiProviders.asrAvailable,
        // 向量统计是异步读库的：保留上一次的值，别在拨开关时闪成 0
        incompatibleEmbeddingCount = previous?.incompatibleEmbeddingCount ?: 0,
        vectorStatusLoaded = previous?.vectorStatusLoaded ?: false,
        reEmbedRunning = previous?.reEmbedRunning ?: false,
        aiNotice = previous?.aiNotice,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        exportRunning = previous?.exportRunning ?: false,
        exportDone = previous?.exportDone ?: 0,
        exportTotal = previous?.exportTotal ?: 0,
        cleanupRunning = previous?.cleanupRunning ?: false,
        notice = previous?.notice,
        // 重不重试也属于瞬时字段：它说的是"上一次那个包还在不在"
        canRetryShare = previous?.canRetryShare ?: false,
    )

    /**
     * 读后台常驻条件。
     *
     * 这一项是**附加信息**：读不到就返回 null，界面整块不显示。
     * 它不该有把设置页变成 Error 页的能力 —— 用户打开设置是来改开关的，
     * 不是为了看自检报告。
     */
    private fun readKeepAliveStatus(): KeepAliveStatus? = try {
        keepAliveGateway.status()
    } catch (e: Exception) {
        Log.w(TAG, "读取后台常驻状态失败", e)
        null
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    /**
     * "已生成、待分享"的导出包 + 它的统计数字。
     *
     * 统计数字也存下来，是因为重试成功时要发的仍是同一份 `ExportDone(records, assets, missing)`；
     * 重新从 `ExportedArchive` 取是拿不到的（那时它已经出了作用域）。
     */
    private data class PendingShare(
        val file: File,
        val records: Int,
        val assets: Int,
        val missingAssets: Int,
    )
}
