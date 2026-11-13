package com.zhicun.app.presentation.settings

import com.zhicun.app.BuildConfig
import com.zhicun.app.domain.export.ExportRecordsUseCase
import com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiProviderSettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeProcessingScheduler
import com.zhicun.app.fake.FakeProcessingJobRepository
import com.zhicun.app.fake.FakeEmbeddingRepository
import com.zhicun.app.domain.ai.ProviderMode
import com.zhicun.app.domain.ai.ProviderProtocol
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.usecase.ReEmbedAllUseCase
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.usecase.ResumeCloudJobsUseCase
import com.zhicun.app.fake.FakeArchiveShareLauncher
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeExportArchiveWriter
import com.zhicun.app.domain.floating.KeepAliveStatus
import com.zhicun.app.domain.floating.StandbyBucket
import com.zhicun.app.fake.FakeFeedbackLauncher
import com.zhicun.app.fake.FakeFloatingBubbleGateway
import com.zhicun.app.fake.FakeFloatingBubbleSettings
import com.zhicun.app.fake.FakeKeepAliveGateway
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeMaintenanceRepository
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.FakeUiLanguageSettings
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testRecord
import com.zhicun.app.util.MainDispatcherRule
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 设置页 ViewModel（M13 悬浮入口 / M14 云端 AI / M15 导出与清理）。
 *
 * 这里钉的是"用户开关 × 系统权限 × 隐私开关 × 长任务状态"的组合 ——
 * 这类功能最容易出的错不是崩，而是"开关开了但什么都没发生"或
 * "界面说关了但内容还在上传"、"显示导出完成但包没交出去"。
 * 因此每条用例都在问同一件事：**界面此刻表达的东西，与真实状态一致吗？**
 *
 * 整个类跑在 Robolectric 上：失败分支会走 `Log.e`，未 mock 的 android.jar 里
 * 那行会抛 `RuntimeException`，把"导出失败"测成"测试失败"。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settings: FakeFloatingBubbleSettings
    private lateinit var gateway: FakeFloatingBubbleGateway
    private lateinit var keepAlive: FakeKeepAliveGateway
    private lateinit var aiSettings: FakeAiSettings
    private lateinit var aiProviders: FakeAiProviderSettings
    private lateinit var embeddings: FakeEmbeddingRepository
    private lateinit var feedback: FakeFeedbackLauncher
    private lateinit var language: FakeUiLanguageSettings

    // M15：导出与清理
    private lateinit var records: FakeRecordRepository
    private lateinit var assetRepository: FakeAssetRepository
    private lateinit var tagRepository: FakeTagRepository
    private lateinit var derivedRepository: FakeDerivedContentRepository
    private lateinit var files: FakeLocalFileStore
    private lateinit var exportWriter: FakeExportArchiveWriter
    private lateinit var maintenance: FakeMaintenanceRepository
    private lateinit var share: FakeArchiveShareLauncher

    // R-3：云端开关重开时的补投
    private lateinit var jobRepository: FakeProcessingJobRepository
    private lateinit var scheduler: FakeProcessingScheduler

    /**
     * 假的导出包，但是**真实存在的文件**。
     *
     * `canRetryShare` 的判据是"包还在不在缓存里"，用一个不存在的路径会让
     * "可以重试"这条分支永远测不到（也会让"包被系统回收"这条永远为真）。
     */
    private lateinit var archiveFile: File

    @Before
    fun setUp() {
        settings = FakeFloatingBubbleSettings()
        gateway = FakeFloatingBubbleGateway(granted = false, settings = settings)
        keepAlive = FakeKeepAliveGateway()
        aiSettings = FakeAiSettings()
        aiProviders = FakeAiProviderSettings()
        embeddings = FakeEmbeddingRepository()
        feedback = FakeFeedbackLauncher()
        language = FakeUiLanguageSettings()

        records = FakeRecordRepository()
        assetRepository = FakeAssetRepository()
        tagRepository = FakeTagRepository()
        derivedRepository = FakeDerivedContentRepository()
        files = FakeLocalFileStore()
        // 用一个真实临时文件冒充导出包：重试分享依赖"文件还在"，不存在就测不出成功路径
        archiveFile = File.createTempFile("zhicun-export", ".zip").apply { deleteOnExit() }
        exportWriter = FakeExportArchiveWriter(archive = archiveFile)
        maintenance = FakeMaintenanceRepository()
        share = FakeArchiveShareLauncher()
        jobRepository = FakeProcessingJobRepository()
        scheduler = FakeProcessingScheduler()
    }

    /**
     * 预置"已存过的状态"再建 ViewModel。
     *
     * 必须走构造参数而不是建完再改：开关的场景就是要验证**构造那一刻**
     * 读到的持久化值，事后改假对象等于把要测的读取步骤绕过去了。
     */
    private fun seededViewModel(
        enabled: Boolean = false,
        granted: Boolean = false,
        cloudAi: Boolean = true,
        autoClipboard: Boolean = false,
        autoRestoreOnBoot: Boolean = true,
    ): SettingsViewModel {
        settings = FakeFloatingBubbleSettings(
            enabled = enabled,
            autoClipboard = autoClipboard,
            autoRestoreOnBoot = autoRestoreOnBoot,
        )
        gateway = FakeFloatingBubbleGateway(granted = granted, settings = settings)
        aiSettings = FakeAiSettings(cloudAiEnabled = cloudAi)
        return viewModel()
    }

    private fun viewModel() = SettingsViewModel(
        settings = settings,
        gateway = gateway,
        keepAliveGateway = keepAlive,
        aiSettings = aiSettings,
        aiProviders = aiProviders,
        embeddings = embeddings,
        reEmbedAll = ReEmbedAllUseCase(
            embeddingRepository = embeddings,
            jobRepository = jobRepository,
            retryProcessing = RetryProcessingUseCase(
                recordRepository = records,
                jobRepository = jobRepository,
                scheduler = scheduler,
                aiSettings = aiSettings,
                gatewaySettings = FakeAiGatewaySettings(),
            ),
        ),
        feedbackLauncher = feedback,
        exportRecords = ExportRecordsUseCase(
            recordRepository = records,
            assetRepository = assetRepository,
            tagRepository = tagRepository,
            derivedContentRepository = derivedRepository,
            collectionRepository = FakeCollectionRepository(),
            fileStore = files,
            writer = exportWriter,
        ),
        cleanupDeletedRecords = CleanupDeletedRecordsUseCase(
            maintenanceRepository = maintenance,
            assetRepository = assetRepository,
            fileStore = files,
        ),
        shareLauncher = share,
        uiLanguage = language,
        resumeCloudJobs = ResumeCloudJobsUseCase(
            recordRepository = records,
            collectionRepository = FakeCollectionRepository(),
            jobRepository = jobRepository,
            derivedContentRepository = derivedRepository,
            assetRepository = assetRepository,
            scheduler = scheduler,
            aiSettings = aiSettings,
            gatewaySettings = FakeAiGatewaySettings(),
        ),
    )

    // ------------------------------------------------------------ 界面语言（P11）

    /**
     * 切换语言只写偏好、更新选中态：**零请求、零任务、零数据库写入**（R-I18N-02）。
     *
     * 这条用例盯的就是"顺手做点什么"：一旦有人在这里触发重新生成或补投，
     * 用户切换一次界面语言就会重跑全部历史记录。
     */
    @Test
    fun OnLanguageSelected_persistsAndDoesNotTriggerAnyWork() {
        val viewModel = seededViewModel()
        val before = scheduler.enqueued.size
        val derivedBefore = derivedRepository.stored.size

        viewModel.onLanguageSelected(AppLanguage.EN)

        assertEquals("必须写入偏好", listOf(AppLanguage.EN), language.selections)
        assertEquals(AppLanguage.EN, viewModel.uiState.value.language)
        assertEquals("不能排任何后台任务", before, scheduler.enqueued.size)
        assertEquals("不能改派生内容", derivedBefore, derivedRepository.stored.size)
    }

    @Test
    fun OnLanguageSelected_sameLanguage_isNoOp() {
        val viewModel = seededViewModel()

        viewModel.onLanguageSelected(AppLanguage.ZH)

        assertTrue("选中的就是当前语言，不该再写一次", language.selections.isEmpty())
    }

    // ------------------------------------------------------------ 初始状态

    @Test
    fun Initial_reflectsPersistedSwitchAndPermission() {
        val state = seededViewModel(enabled = true, granted = true).uiState.value

        assertTrue(state.floatingEnabled)
        assertTrue(state.hasOverlayPermission)
        assertFalse(state.needsPermission)
    }

    /** 构造 ViewModel 本身不该有副作用：进设置页只是看看，不能顺手启停服务。 */
    @Test
    fun Initial_doesNotSyncService() {
        viewModel()

        assertEquals(0, gateway.syncCount)
    }

    /** 版本号来自 BuildConfig，M14 验收要求"版本号正确显示"。 */
    @Test
    fun Initial_exposesBuildVersion() {
        val state = viewModel().uiState.value

        assertEquals(BuildConfig.VERSION_NAME, state.versionName)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), state.versionCode)
        assertTrue(state.hasVersion)
    }

    @Test
    fun Initial_reflectsCloudAiSetting() {
        assertTrue(viewModel().uiState.value.cloudAiEnabled)
        assertFalse(seededViewModel(cloudAi = false).uiState.value.cloudAiEnabled)
    }

    /** 自动收录剪贴板默认必须是开的（2026-10-24 附录 T）：引导教的第一个动作就是点气泡存剪贴板。 */
    @Test
    fun Initial_autoClipboardDefaultsOn_reflectsPersistedValue() {
        assertTrue(viewModel().uiState.value.autoClipboardEnabled)
        assertFalse(seededViewModel(enabled = true, autoClipboard = false).uiState.value.autoClipboardEnabled)
    }

    // ------------------------------------------------------------ 后台常驻自检（2026-10-24）

    @Test
    fun KeepAlive_readsStatusOnConstruct() {
        keepAlive.status = KeepAliveStatus(
            batteryOptimizationIgnored = false,
            notificationsEnabled = true,
            standbyBucket = StandbyBucket.RARE,
        )

        val state = viewModel().uiState.value

        assertEquals(keepAlive.status, state.keepAlive)
        assertTrue(state.keepAlive!!.needsBatteryWhitelist)
        assertTrue(state.keepAlive!!.standbyRestricted)
    }

    /**
     * 悬浮关着时整块不显示。
     *
     * 气泡都没开，摆一屏"未加入白名单/受限"只会让用户以为自己弄坏了什么。
     */
    @Test
    fun KeepAlive_hiddenWhenFloatingDisabled() {
        val state = seededViewModel(enabled = false).uiState.value

        assertFalse(state.showKeepAlive)
    }

    @Test
    fun KeepAlive_shownWhenFloatingEnabled() {
        val state = seededViewModel(enabled = true).uiState.value

        assertTrue(state.showKeepAlive)
    }

    /**
     * 读不到就整块不显示，而不是显示"一切正常"。
     *
     * 假消息比没消息更糟：用户会据此认为不需要去加白。
     */
    @Test
    fun KeepAlive_readFailure_isTreatedAsUnknown_notAsHealthy() {
        keepAlive.failOnRead = true

        val state = seededViewModel(enabled = true).uiState.value

        assertNull(state.keepAlive)
        assertFalse(state.showKeepAlive)
    }

    /** 用户去系统设置加白后返回，ON_RESUME 调的 refresh 必须重读，否则那一行永远是红的。 */
    @Test
    fun KeepAlive_refreshRereadsStatus() {
        keepAlive.status = KeepAliveStatus(
            batteryOptimizationIgnored = false,
            notificationsEnabled = true,
        )
        val vm = seededViewModel(enabled = true)
        assertTrue(vm.uiState.value.keepAlive!!.needsBatteryWhitelist)

        // 模拟用户在系统设置里把本应用加入白名单后返回
        keepAlive.status = KeepAliveStatus(
            batteryOptimizationIgnored = true,
            notificationsEnabled = true,
        )
        vm.refresh()

        assertFalse(vm.uiState.value.keepAlive!!.needsBatteryWhitelist)
    }

    @Test
    fun KeepAlive_batteryButtonOpensSystemList() {
        viewModel().onOpenBatterySettingsClick()

        assertEquals(1, keepAlive.batterySettingsOpened)
    }

    @Test
    fun KeepAlive_notificationButtonOpensAppNotificationSettings() {
        viewModel().onOpenNotificationSettingsClick()

        assertEquals(1, keepAlive.notificationSettingsOpened)
    }

    // ------------------------------------------------------------ 拨开关

    @Test
    fun ToggleOn_withPermission_persistsAndStartsService() {
        gateway.granted = true
        val vm = viewModel()

        vm.onFloatingToggled(true)

        assertEquals(listOf(true), settings.enabledWrites)
        assertTrue("开关打开且已授权时必须尝试启动", gateway.syncCount >= 1)
        assertTrue(vm.uiState.value.floatingEnabled)
        assertFalse(vm.uiState.value.needsPermission)
    }

    /**
     * 无权限时不回退开关：用户表达的意图是"我要用"。
     * 界面改为亮出授权入口（`needsPermission`），这才是他能自己走通的下一步。
     */
    @Test
    fun ToggleOn_withoutPermission_keepsSwitchOnButNeedsPermission() {
        gateway.granted = false
        val vm = viewModel()

        vm.onFloatingToggled(true)

        assertEquals(listOf(true), settings.enabledWrites)
        assertTrue("开关必须保持在'开'的位置", vm.uiState.value.floatingEnabled)
        assertTrue("没有权限时必须提示去授权", vm.uiState.value.needsPermission)
    }

    @Test
    fun ToggleOff_persistsAndClearsNeedsPermission() {
        val vm = seededViewModel(enabled = true)

        vm.onFloatingToggled(false)

        assertEquals(listOf(false), settings.enabledWrites)
        assertFalse(vm.uiState.value.floatingEnabled)
        assertFalse(vm.uiState.value.needsPermission)
        assertTrue("关掉开关必须尝试停服务", gateway.syncCount >= 1)
    }

    /**
     * 关键回归：用户点了"去系统设置授权"、授权完返回。
     * 这条路径上**没有任何回调**，唯一的机会就是 ON_RESUME 时的 refresh。
     * 如果 refresh 只读状态不 sync，气泡永远不会出现，用户得退出去重进。
     */
    @Test
    fun Refresh_afterPermissionGranted_clearsHintAndStartsService() {
        gateway.granted = false
        val vm = viewModel()
        vm.onFloatingToggled(true)
        assertTrue(vm.uiState.value.needsPermission)

        // 用户去系统设置里授权后返回
        gateway.granted = true
        val syncBefore = gateway.syncCount
        vm.refresh()

        assertFalse("授权后不该再提示", vm.uiState.value.needsPermission)
        assertTrue(vm.uiState.value.hasOverlayPermission)
        assertTrue("授权返回时必须重新对齐服务", gateway.syncCount > syncBefore)
    }

    /** 反向：用户从系统设置里把权限撤了，设置页要立刻如实反映。 */
    @Test
    fun Refresh_afterPermissionRevoked_reportsNeedsPermissionAgain() {
        val vm = seededViewModel(enabled = true, granted = true)
        assertFalse(vm.uiState.value.needsPermission)

        gateway.granted = false
        vm.refresh()

        assertTrue(vm.uiState.value.needsPermission)
        assertFalse(vm.uiState.value.hasOverlayPermission)
    }

    // ------------------------------------------------------------ 授权入口

    @Test
    fun GrantClick_opensSystemPermissionPage() {
        val vm = viewModel()

        vm.onGrantPermissionClick()

        assertEquals(1, gateway.openPermissionCount)
    }

    // -------------------------------------------------------- 云端 AI（M14）

    @Test
    fun CloudAiToggleOff_persistsAndReflectsInState() {
        val vm = viewModel()

        vm.onCloudAiToggled(false)

        assertEquals(listOf(false), aiSettings.writes)
        assertFalse(vm.uiState.value.cloudAiEnabled)
        assertFalse("关掉云端后转写也必须显示不可用", vm.uiState.value.transcriptionAvailable)
    }

    @Test
    fun CloudAiToggleOn_persistsAndRestoresTranscription() {
        val vm = seededViewModel(cloudAi = false)
        assertFalse(vm.uiState.value.transcriptionAvailable)

        vm.onCloudAiToggled(true)

        assertTrue(vm.uiState.value.cloudAiEnabled)
        assertTrue(vm.uiState.value.transcriptionAvailable)
    }

    /**
     * 云端开关与悬浮开关互不影响：这是两个独立的分组，
     * 拨一个把另一个带上是用户最不能理解的一类 bug。
     */
    @Test
    fun CloudAiToggle_doesNotTouchFloatingBubble() {
        val vm = seededViewModel(enabled = false, granted = true)

        vm.onCloudAiToggled(false)

        assertTrue(settings.enabledWrites.isEmpty())
        assertEquals(0, gateway.syncCount)
        assertFalse(vm.uiState.value.floatingEnabled)
    }

    @Test
    fun FloatingToggle_doesNotTouchCloudAi() {
        val vm = seededViewModel(cloudAi = false)

        vm.onFloatingToggled(true)

        assertTrue(aiSettings.writes.isEmpty())
        assertFalse(vm.uiState.value.cloudAiEnabled)
    }

    // ------------------------------------------------ 自动收录剪贴板（B2）

    @Test
    fun AutoClipboardToggle_persistsAndReflectsInState() {
        val vm = viewModel()

        vm.onAutoClipboardToggled(true)
        assertTrue(vm.uiState.value.autoClipboardEnabled)
        assertEquals(listOf(true), settings.autoClipboardWrites)

        vm.onAutoClipboardToggled(false)
        assertFalse(vm.uiState.value.autoClipboardEnabled)
        assertEquals(listOf(true, false), settings.autoClipboardWrites)
    }

    /**
     * 自动收录只改变"点气泡的行为"，不改变服务是否运行 —— 悬浮服务照常在跑。
     * 因此它不该触发服务对齐（sync），否则会无谓地重建前台通知。
     */
    @Test
    fun AutoClipboardToggle_doesNotSyncService() {
        gateway.granted = true
        val vm = viewModel()
        val syncBefore = gateway.syncCount

        vm.onAutoClipboardToggled(true)

        assertEquals(syncBefore, gateway.syncCount)
    }

    @Test
    fun FeedbackClick_launchesOnce() {
        val vm = viewModel()

        vm.onFeedbackClick()

        assertEquals(1, feedback.openCount)
    }

    // ------------------------------------------------ 开机自动恢复（2026-10-24）

    /**
     * 默认读出来是"开"：这个开关加之前恢复是硬编码的，默认给关等于静默改掉老用户的行为。
     * 界面初值也必须一致，否则首帧会闪一下"已关闭"。
     */
    @Test
    fun Initial_autoRestoreOnBootDefaultsOn_andReflectsPersistedValue() {
        assertTrue(viewModel().uiState.value.autoRestoreOnBoot)
        assertFalse(seededViewModel(autoRestoreOnBoot = false).uiState.value.autoRestoreOnBoot)
    }

    @Test
    fun AutoRestoreToggle_persistsAndReflectsInState() {
        val vm = viewModel()

        vm.onAutoRestoreOnBootToggled(false)
        assertFalse(vm.uiState.value.autoRestoreOnBoot)
        assertEquals(listOf(false), settings.autoRestoreOnBootWrites)

        vm.onAutoRestoreOnBootToggled(true)
        assertTrue(vm.uiState.value.autoRestoreOnBoot)
        assertEquals(listOf(false, true), settings.autoRestoreOnBootWrites)
    }

    /**
     * 这个开关只决定"下次开机要不要恢复"，跟当前这次运行无关 ——
     * 拨它不该顺手去启停服务（否则气泡会当场闪一下）。
     */
    @Test
    fun AutoRestoreToggle_doesNotSyncService() {
        val vm = seededViewModel(enabled = true, granted = true)
        val syncBefore = gateway.syncCount

        vm.onAutoRestoreOnBootToggled(false)

        assertEquals(syncBefore, gateway.syncCount)
    }

    /** 与悬浮开关互不影响：关掉开机恢复，不等于用户不要气泡。 */
    @Test
    fun AutoRestoreToggle_keepsFloatingSwitchOn() {
        val vm = seededViewModel(enabled = true, granted = true)

        vm.onAutoRestoreOnBootToggled(false)

        assertTrue(vm.uiState.value.floatingEnabled)
        assertTrue(settings.enabledWrites.isEmpty())
    }

    // ------------------------------------------------------ 状态推导真值表

    /**
     * `needsPermission` 只有"想要 + 没有"这一种情况为真。
     * 单独测它是因为界面上"是否显示授权入口"完全由它决定，
     * 而它一旦为真就会在设置页里多出一整块内容。
     */
    @Test
    fun NeedsPermission_truthTable() {
        assertFalse(SettingsUiState(floatingEnabled = false, hasOverlayPermission = false).needsPermission)
        assertFalse(SettingsUiState(floatingEnabled = false, hasOverlayPermission = true).needsPermission)
        assertTrue(SettingsUiState(floatingEnabled = true, hasOverlayPermission = false).needsPermission)
        assertFalse(SettingsUiState(floatingEnabled = true, hasOverlayPermission = true).needsPermission)
    }

    @Test
    fun TranscriptionAvailable_followsCloudAiAndProvider() {
        assertTrue(SettingsUiState(cloudAiEnabled = true, asrConfigured = true).transcriptionAvailable)
        assertFalse(SettingsUiState(cloudAiEnabled = false, asrConfigured = true).transcriptionAvailable)
        assertFalse(
            "云端开着但转写 Provider 没配：不能显示「可用」，否则录音会以未配置终态失败",
            SettingsUiState(cloudAiEnabled = true, asrConfigured = false).transcriptionAvailable,
        )
    }

    // ------------------------------------------------------ AI Provider（开源改造 P0/P1）

    @Test
    fun SaveChatProvider_persistsFields_andBlankKeyKeepsExisting() {
        val vm = viewModel()

        vm.onSaveChatProvider("https://api.openai.com/v1/", "sk-chat", "gpt-4o-mini")

        var state = vm.uiState.value
        assertEquals("尾部斜杠应被规范化", "https://api.openai.com/v1", state.chatBaseUrl)
        assertEquals("gpt-4o-mini", state.chatModel)
        assertTrue(state.chatConfigured)

        // 第二次保存时用户没重新输入 Key（界面从不回填 Key）：必须保留原值
        vm.onSaveChatProvider("https://api.openai.com/v1", "", "gpt-4o")

        state = vm.uiState.value
        assertEquals("gpt-4o", state.chatModel)
        assertEquals("sk-chat", aiProviders.chat?.apiKey)
        assertEquals("空 Key 不应该被当成「清除」", "sk-chat", aiProviders.chatConfig?.apiKey)
    }

    @Test
    fun SaveEmbedProvider_newModel_reportsIncompatibleVectors() = runTest {
        embeddings.seed("r1", floatArrayOf(1f, 0f), model = "old-model", providerKey = "https://old")
        embeddings.seed("r2", floatArrayOf(0f, 1f), model = "old-model", providerKey = "https://old")
        val vm = viewModel()

        vm.onUseOfficialServiceToggled(false)
        vm.onSaveEmbedProvider("https://api.siliconflow.cn/v1", "sk-embed", "BAAI/bge-m3")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("换模型后两条旧向量都要提示重建", 2, state.incompatibleEmbeddingCount)
        assertTrue(state.vectorStatusLoaded)
        assertFalse(state.reEmbedRunning)
    }

    @Test
    fun ReEmbedClick_submitsOnlyEmbedJobs_andReportsCount() = runTest {
        embeddings.seed("r1", floatArrayOf(1f), model = "old-model", providerKey = "https://old")
        // getById 读的是 byId（真实实现读库），只改 stored 不够
        records.byId["r1"] = testRecord(id = "r1")
        val vm = viewModel()

        vm.onUseOfficialServiceToggled(false)
        vm.onSaveEmbedProvider("https://api.siliconflow.cn/v1", "sk-embed", "BAAI/bge-m3")
        advanceUntilIdle()

        vm.onReEmbedClick()
        advanceUntilIdle()

        assertEquals(
            "重建只能重排 EMBED，不能顺手把 OCR/转写/网页提取再跑一遍",
            listOf("r1" to JobType.EMBED),
            scheduler.reenqueued,
        )
        val notice = vm.uiState.value.aiNotice
        assertTrue("应给出「已提交 N 条」的结论，实际：$notice", notice is AiNotice.ReEmbedSubmitted)
        assertEquals(1, (notice as AiNotice.ReEmbedSubmitted).count)
        assertFalse(vm.uiState.value.reEmbedRunning)
    }

    @Test
    fun UseOfficialToggle_keepsCustomConfigForSwitchBack() {
        aiProviders.chat = com.zhicun.app.domain.ai.AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk",
            model = "gpt-4o-mini",
            mode = ProviderMode.CUSTOM,
        )
        val vm = viewModel()

        vm.onUseOfficialServiceToggled(true)
        assertTrue(vm.uiState.value.useOfficialService)
        assertEquals("切到官方后自定义配置要保留，切回来不用重填", "gpt-4o-mini", vm.uiState.value.chatModel)
        assertEquals("https://api.openai.com/v1", vm.uiState.value.chatBaseUrl)

        vm.onUseOfficialServiceToggled(false)
        assertFalse(vm.uiState.value.useOfficialService)
    }

    @Test
    fun ChatProtocolSelection_persists_andSurvivesFieldSave() {
        val vm = viewModel()

        vm.onChatProtocolSelected(ProviderProtocol.ANTHROPIC)

        assertEquals(ProviderProtocol.ANTHROPIC, vm.uiState.value.chatProtocol)
        assertEquals(ProviderProtocol.ANTHROPIC, aiProviders.chatProtocolValue)

        // 选完协议再填地址与模型：保存这些字段不能把协议冲回默认值
        vm.onSaveChatProvider(
            "https://api.anthropic.com/v1",
            "sk-ant",
            "claude-3-5-sonnet-latest",
        )

        assertEquals(ProviderProtocol.ANTHROPIC, aiProviders.chat?.protocol)
        assertEquals(ProviderProtocol.ANTHROPIC, vm.uiState.value.chatProtocol)
        assertEquals("https://api.anthropic.com/v1", vm.uiState.value.chatBaseUrl)
    }

    // ------------------------------------------------------------ 导出（M15）

    /** 导出成功的完整含义：包真的交出去了、界面上带着数字。 */
    @Test
    fun Export_success_sharesArchiveAndReportsCounts() {
        records.stored.value = listOf(testRecord(id = "r1"), testRecord(id = "r2"))
        val vm = viewModel()

        vm.onExportConfirmed(includeDerivedContent = false)

        assertEquals("必须真的把包交给系统分享面板", 1, share.shared.size)
        assertEquals(
            SettingsNotice.ExportDone(records = 2, assets = 0, missingAssets = 0),
            vm.uiState.value.notice,
        )
        assertFalse(vm.uiState.value.exportRunning)
        assertFalse("分享成功后不该再留「重试」入口", vm.uiState.value.canRetryShare)
    }

    /**
     * 分享面板起不来时，**不能**报"导出完成"（M16）。
     *
     * 包只在应用私有缓存里，用户自己翻不出来。报成功等于告诉他"东西好了"却拿不到。
     */
    @Test
    fun Export_shareLaunchFails_reportsNotSharedAndOffersRetry() {
        records.stored.value = listOf(testRecord(id = "r1"))
        share.succeeds = false
        val vm = viewModel()

        vm.onExportConfirmed(includeDerivedContent = false)

        assertEquals(
            SettingsNotice.ExportNotShared(records = 1, assets = 0, missingAssets = 0),
            vm.uiState.value.notice,
        )
        assertTrue("包还在缓存里，应给出重试入口", vm.uiState.value.canRetryShare)
        assertFalse(vm.uiState.value.exportRunning)
    }

    /** 重试分享只重开面板，不重写一遍几十 MB 的包。 */
    @Test
    fun Export_retryShare_reopensPanelWithoutRewritingArchive() {
        records.stored.value = listOf(testRecord(id = "r1"))
        share.succeeds = false
        val vm = viewModel()
        vm.onExportConfirmed(includeDerivedContent = false)
        assertEquals(1, exportWriter.writeCount)

        share.succeeds = true
        vm.onRetryShare()

        assertEquals("重试不该重新导出", 1, exportWriter.writeCount)
        assertEquals("面板要被再打开一次", 2, share.shared.size)
        assertEquals(
            SettingsNotice.ExportDone(records = 1, assets = 0, missingAssets = 0),
            vm.uiState.value.notice,
        )
        assertFalse(vm.uiState.value.canRetryShare)
    }

    /** 包被系统回收（cacheDir 会被清）后不再假装还能重试，按钮应当消失。 */
    @Test
    fun Export_retryShare_whenArchiveGone_disablesRetry() {
        records.stored.value = listOf(testRecord(id = "r1"))
        share.succeeds = false
        val vm = viewModel()
        vm.onExportConfirmed(includeDerivedContent = false)
        assertTrue(vm.uiState.value.canRetryShare)

        archiveFile.delete()
        val attemptsBefore = share.shared.size
        vm.onRetryShare()

        assertEquals("文件没了就不该再去调分享", attemptsBefore, share.shared.size)
        assertFalse(vm.uiState.value.canRetryShare)
    }

    /** 附件缺失要如实带回界面：一个"完整"的假象比少几个文件更糟。 */
    @Test
    fun Export_withMissingAsset_reportsMissingCount() {
        records.stored.value = listOf(testRecord(id = "r1"))
        val asset = testAsset(id = "a1", recordId = "r1")
        runBlocking { assetRepository.save(asset) }
        // 故意不放行文件：模拟用户清过缓存
        val vm = viewModel()

        vm.onExportConfirmed(includeDerivedContent = false)

        assertEquals(
            SettingsNotice.ExportDone(records = 1, assets = 1, missingAssets = 1),
            vm.uiState.value.notice,
        )
    }

    /** 一次导出要写几十 MB，连点两下会把同样的活干两遍。 */
    @Test
    fun Export_whileRunning_ignoresSecondClick() = runTest(mainDispatcherRule.dispatcher) {
        records.stored.value = listOf(testRecord(id = "r1"))
        val gate = CompletableDeferred<Unit>()
        exportWriter.gate = gate
        val vm = viewModel()

        vm.onExportConfirmed(includeDerivedContent = false)
        assertTrue("导出进行中，界面要能显示进度", vm.uiState.value.exportRunning)

        vm.onExportConfirmed(includeDerivedContent = false)
        assertEquals("重复点击必须被忽略", 1, exportWriter.writeCount)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.exportRunning)
        assertEquals(1, share.shared.size)
    }

    @Test
    fun Export_failure_clearsProgressAndReportsFailure() {
        records.stored.value = listOf(testRecord(id = "r1"))
        exportWriter.failWith = IOException("磁盘满")
        val vm = viewModel()

        vm.onExportConfirmed(includeDerivedContent = false)

        assertEquals(SettingsNotice.ExportFailed, vm.uiState.value.notice)
        assertFalse("失败的导出不能留一个转圈的进度条", vm.uiState.value.exportRunning)
        assertTrue("失败时不该弹分享面板", share.shared.isEmpty())
    }

    /** 开始新一次操作时清掉上一次的结论，否则用户会以为那句"导出完成"说的是这次。 */
    @Test
    fun Export_clearsPreviousNoticeOnStart() = runTest(mainDispatcherRule.dispatcher) {
        records.stored.value = listOf(testRecord(id = "r1"))
        exportWriter.failWith = IOException("磁盘满")
        val vm = viewModel()
        vm.onExportConfirmed(includeDerivedContent = false)
        assertTrue(vm.uiState.value.notice is SettingsNotice.ExportFailed)

        exportWriter.failWith = null
        val gate = CompletableDeferred<Unit>()
        exportWriter.gate = gate
        vm.onExportConfirmed(includeDerivedContent = false)

        assertNull("新一次操作开始时要清空旧结论", vm.uiState.value.notice)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------ 清理（M15）

    @Test
    fun Cleanup_success_reportsWhatWasRemoved() {
        maintenance.softDeletedRecords += testRecord(id = "r1", isDeleted = true)
        val asset = testAsset(id = "a1", recordId = "r1")
        runBlocking { assetRepository.save(asset) }
        files.presentPaths += asset.localPath
        val vm = viewModel()

        vm.onCleanupConfirmed()

        assertEquals(
            SettingsNotice.CleanupDone(records = 1, assets = 1, failedFiles = 0),
            vm.uiState.value.notice,
        )
        assertFalse(vm.uiState.value.cleanupRunning)
    }

    /** 没有可清理的东西不是错误，是"你已经是干净的"。 */
    @Test
    fun Cleanup_nothingToDo_saysSo() {
        val vm = viewModel()

        vm.onCleanupConfirmed()

        assertEquals(SettingsNotice.CleanupNothing, vm.uiState.value.notice)
    }

    @Test
    fun Cleanup_failure_clearsRunningAndReportsFailure() {
        maintenance.failWith = IOException("database is malformed")
        val vm = viewModel()

        vm.onCleanupConfirmed()

        assertEquals(SettingsNotice.CleanupFailed, vm.uiState.value.notice)
        assertFalse(vm.uiState.value.cleanupRunning)
    }

    /** 清理进行中时导出按钮必须失效，否则两个大任务会同时写盘。 */
    @Test
    fun Cleanup_whileRunning_blocksExport() = runTest(mainDispatcherRule.dispatcher) {
        maintenance.softDeletedRecords += testRecord(id = "r1", isDeleted = true)
        val gate = CompletableDeferred<Unit>()
        maintenance.gate = gate
        val vm = viewModel()

        vm.onCleanupConfirmed()
        assertTrue(vm.uiState.value.cleanupRunning)
        assertTrue(vm.uiState.value.busy)

        vm.onExportConfirmed(includeDerivedContent = false)
        assertEquals("清理进行中不能同时导出", 0, exportWriter.writeCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.busy)
    }

    // -------------------------------------------------------- 状态的瞬时字段

    /**
     * 拨悬浮开关会触发 `refresh()` 回读真值，但**不能**把刚弹出的导出结果抹掉 ——
     * 用户看到的提示会莫名消失，像是操作没生效。
     */
    @Test
    fun Refresh_keepsNoticeAndProgress() {
        records.stored.value = listOf(testRecord(id = "r1"))
        val vm = viewModel()
        vm.onExportConfirmed(includeDerivedContent = false)
        val notice = vm.uiState.value.notice

        vm.onFloatingToggled(true)

        assertEquals(notice, vm.uiState.value.notice)
    }
}
