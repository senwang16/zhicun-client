package com.zhicun.app.presentation.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.audio.AudioPlayer
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.usecase.AddUserTagUseCase
import com.zhicun.app.domain.usecase.ArchiveRecordsUseCase
import com.zhicun.app.domain.usecase.ConfirmCloudProcessingUseCase
import com.zhicun.app.domain.usecase.ConfirmWebFetchUseCase
import com.zhicun.app.domain.usecase.DeleteAssetUseCase
import com.zhicun.app.domain.usecase.DeleteRecordsUseCase
import com.zhicun.app.domain.usecase.GetDerivedContentUseCase
import com.zhicun.app.domain.usecase.GetRecordByIdUseCase
import com.zhicun.app.domain.usecase.GetRelatedRecordsUseCase
import com.zhicun.app.domain.usecase.RelatedRecords
import com.zhicun.app.domain.usecase.RetryProcessingUseCase
import com.zhicun.app.domain.usecase.UpdateRecordTextUseCase
import com.zhicun.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页 ViewModel。
 *
 * 拆分去向（AGENTS.md §2.1 单文件 400 行上限，全部为纯移动、零行为变更）：
 * - 播放状态机 → [DetailAudioPlayback]；
 * - 门禁判据推导 → `computeDetailGateState`（`DetailGateState.kt`）；
 * - 相关记录加载 → `observeRelatedRecords`（`DetailRelatedRecords.kt`）；
 * - 页面状态定义 → [RecordDetailUiState]（`RecordDetailUiState.kt`）。
 *
 * 标签编辑动作的实现 → `DetailTagEditing.kt`（这里保留成员函数以满足方法引用）。
 *
 * 本文件保留页面数据装配与用户动作分发。
 */
@HiltViewModel
class RecordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getRecordById: GetRecordByIdUseCase,
    private val getDerivedContent: GetDerivedContentUseCase,
    private val assetRepository: AssetRepository,
    internal val recordRepository: RecordRepository,
    internal val collectionRepository: CollectionRepository,
    private val retryProcessingUseCase: RetryProcessingUseCase,
    private val updateRecordText: UpdateRecordTextUseCase,
    private val confirmCloudProcessing: ConfirmCloudProcessingUseCase,
    private val confirmWebFetch: ConfirmWebFetchUseCase,
    internal val tagRepository: TagRepository,
    internal val addUserTag: AddUserTagUseCase,
    private val archiveRecords: ArchiveRecordsUseCase,
    private val getRelatedRecords: GetRelatedRecordsUseCase,
    // 删除动作的实现在 DetailDeleteActions.kt（单文件 400 行），这里只注入依赖
    internal val deleteAsset: DeleteAssetUseCase,
    internal val deleteRecords: DeleteRecordsUseCase,
    audioPlayer: AudioPlayer,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
    // 转写区失败判据的数据源（2026-10-29 修复 P2），放末尾便于既有测试按名补传
    private val processingJobRepository: ProcessingJobRepository,
) : ViewModel() {

    internal val recordId: String = savedStateHandle.get<String>(Routes.RECORD_ID_ARG).orEmpty()

    private val _uiState = MutableStateFlow<RecordDetailUiState>(RecordDetailUiState.Loading)

    val uiState: StateFlow<RecordDetailUiState> = _uiState.asStateFlow()

    /**
     * 与当前记录最相似的历史记录（M18）。
     *
     * 空（`isEmpty`）= 不渲染分区（无向量 / 无命中 / 加载失败）。
     */
    private val _relatedRecords = MutableStateFlow(RelatedRecords())
    val relatedRecords: StateFlow<RelatedRecords> = _relatedRecords.asStateFlow()

    /** 语音播放中枢。独立 Flow，进度每 300ms 变一次，混进 [uiState] 会让整页重组。 */
    private val audio = DetailAudioPlayback(audioPlayer, viewModelScope)

    /** 语音播放状态（M9）。转发自 [DetailAudioPlayback]，调用方与测试的用法保持不变。 */
    val playback: StateFlow<VoicePlaybackState> = audio.state

    /** 整理分区的输入态（M16）。独立于 [uiState]，理由见 [DetailOrganizeState]。 */
    internal val _organize = MutableStateFlow(DetailOrganizeState())
    val organize: StateFlow<DetailOrganizeState> = _organize.asStateFlow()

    /** 删除等一次性结果（2026-10-29）；留几个缓冲位，旋转重建的瞬间也不至于丢事件。 */
    private val _events = MutableSharedFlow<RecordDetailEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RecordDetailEvent> = _events.asSharedFlow()

    /** 原文编辑状态机（2026-10-26）。独立于 [uiState]，理由见 [DetailContentEditing]。 */
    private val contentEditing = DetailContentEditing(
        scope = viewModelScope,
        updateRecordText = updateRecordText,
        currentRecord = ::currentRecord,
        onRecordChanged = { reloadSignal.update { it + 1 } },
    )
    val contentEdit: StateFlow<DetailContentEditState> = contentEditing.state

    /** 自增即触发一次"重读附件 + 派生内容"。 */
    internal val reloadSignal = MutableStateFlow(0L)

    init {
        audio.attachCompletionReset()

        viewModelScope.launch {
            getRecordById.observe(recordId)
                .combine(reloadSignal) { record, _ -> record }
                .catch { e ->
                    Log.e(TAG, "详情页订阅失败 recordId=$recordId", e)
                    _uiState.update { RecordDetailUiState.Error(R.string.detail_error_load) }
                }
                .collectLatest { record -> render(record) }
        }

        loadRelatedRecords()
    }

    /**
     * 相关记录（M18）的加载订阅。实现见 `DetailRelatedRecords.kt`
     * （`observeRelatedRecords`），这里只传入它需要的三个协作者。
     */
    private fun loadRelatedRecords() {
        observeRelatedRecords(
            getRecordById = getRecordById,
            getRelatedRecords = getRelatedRecords,
            relatedRecords = _relatedRecords,
        )
    }

    /**
     * 播放/暂停语音（M9）。实现在 [DetailAudioPlayback]，这里只把附件递过去。
     *
     * @param assetId 要点的是哪一段音频（2026-10-29 多音频）；`null` = 第一段
     */
    fun onPlayPauseClicked(assetId: String? = null) {
        val content = _uiState.value as? RecordDetailUiState.Content ?: return
        audio.onPlayPauseClicked(content.assets, assetId)
    }

    /** 删除单个附件（2026-10-29）。实现见 `DetailDeleteActions.kt`。 */
    fun onDeleteAsset(assetId: String) = deleteAssetById(assetId)

    /** 删除整条记录（2026-10-29）。实现见 `DetailDeleteActions.kt`。 */
    fun onDeleteRecord() = deleteCurrentRecord()

    /** 发一个一次性事件给界面（提示条 / 引导框）。 */
    internal fun emitEvent(event: RecordDetailEvent) {
        _events.tryEmit(event)
    }

    /**
     * 用户确认"可以上传"（M11 疑似敏感内容）。
     *
     * 确认后要重新读一次：`sensitivityStatus` 与 `processingStatus` 都变了，
     * 界面上的敏感横幅与"未上传"状态必须立刻消失，否则用户会以为没生效。
     */
    fun onConfirmCloudClicked() {
        val record = currentRecord() ?: return
        viewModelScope.launch {
            try {
                confirmCloudProcessing(record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "确认上传失败 recordId=${record.id}", e)
            } finally {
                reloadSignal.update { it + 1 }
            }
        }
    }

    /**
     * 重新读一遍记录与门禁状态（2026-10-23 整改 F-6）。
     *
     * `AiSettings` / `AiGatewaySettings` 是同步属性、没有 Flow，用户在设置页拨了开关之后
     * 这里收不到任何通知：从设置返回时横幅还写着"云端通路当前不可用"、确认按钮也不出现，
     * 得退出重进才更新 —— 引导链路等于断在最后一步。
     * 页面 `ON_RESUME` 时调这个方法（见 `RecordDetailScreen`）。
     */
    fun refresh() {
        reloadSignal.update { it + 1 }
    }

    /**
     * 用户点"允许抓取此网页"（2026-10-23 复核 F-5）。
     *
     * 只放行向源站取页面这一件事：**不**写云端确认、**不**排云端任务，
     * 也不需要云端通路可用（抓取不经 AI 网关）。正文抓回来之后要不要发去摘要，
     * 是另一道门，届时 AI 分区会独立问一次。
     *
     * 失败时静默保持原状（按钮还在）：用户在同一个位置再点一次即可，
     * 一次写库失败不该把整页换成错误页。
     */
    fun onConfirmWebFetchClicked() {
        val record = currentRecord() ?: return
        viewModelScope.launch {
            try {
                confirmWebFetch(record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "允许抓取失败 recordId=${record.id}", e)
            } finally {
                reloadSignal.update { it + 1 }
            }
        }
    }

    /** 用户点"重新识别"：复位任务并重新排队，然后立刻重读一次让界面显示"处理中"。 */
    fun onRetryClicked() {
        val record = currentRecord() ?: return
        viewModelScope.launch {
            try {
                retryProcessingUseCase(record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 重排失败不弹错误页：界面上仍是原来的失败状态，用户可以再点一次
                Log.e(TAG, "重试处理失败 recordId=${record.id}", e)
            } finally {
                reloadSignal.update { it + 1 }
            }
        }
    }

    /** 开始编辑原文（2026-10-26）：预填当前正文，后续保存会触发 AI 重算。 */
    fun onStartRawTextEdit() = contentEditing.start()

    fun onRawTextDraftChange(text: String) = contentEditing.onDraftChange(text)

    fun onCancelRawTextEdit() = contentEditing.cancel()

    fun onSaveRawText() = contentEditing.save()

    /**
     * 切换收藏（M16）。
     *
     * 失败时界面保持原状（按钮文案不变），用户可以再点一次 —— 不弹错误页：
     * 一个标记没打上，不该把整页内容换成"读取失败"。
     */
    fun onToggleFavorite() {
        val record = currentRecord() ?: return
        runOrganizeAction("收藏") {
            recordRepository.setFavorite(
                id = record.id,
                favorite = !record.isFavorite,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 切换置顶（M16）。置顶影响收件箱排序，本页只体现按钮状态。 */
    fun onTogglePinned() {
        val record = currentRecord() ?: return
        runOrganizeAction("置顶") {
            recordRepository.setPinned(
                id = record.id,
                pinned = !record.isPinned,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 切换归档（M16）。
     *
     * 归档后**不退出详情页**：记录只是离开收件箱，内容仍然完整可读。
     * 直接把用户弹回去反而像"操作失败了"，所以就地变成"取消归档"按钮。
     */
    fun onToggleArchived() {
        val record = currentRecord() ?: return
        if (record.isArchived) {
            runOrganizeAction("取消归档") { archiveRecords.restore(listOf(record.id)) }
        } else {
            runOrganizeAction("归档") { archiveRecords(listOf(record.id)) }
        }
    }

    internal fun currentRecord(): Record? =
        (_uiState.value as? RecordDetailUiState.Content)?.record

    /**
     * 标签编辑动作（M16 / U-7）。
     *
     * 实现原样放在 `DetailTagEditing.kt`（纯移动，AGENTS.md §2.1 单文件 400 行）；
     * 这里保留成员函数是因为界面层用 `viewModel::onTagInputChange` 绑定方法引用。
     */
    fun onTagInputChange(text: String) = updateTagInput(text)

    fun onAddTag() = submitTag()

    fun onRemoveTag(tagId: String) = removeTagById(tagId)

    fun onTagsEdited(names: List<String>) = saveEditedTags(names)

    /**
     * 整理类动作的统一外壳：执行 → 记日志 → 无论成败都重读一次。
     *
     * 重读放在 `finally` 而不是成功分支：操作失败时数据库可能已经改了一半
     * （比如 UPDATE 成功但后续读取失败），界面必须回到库里的真实状态，
     * 而不是停在乐观更新的结果上。
     */
    internal fun runOrganizeAction(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 空 catch 是明令禁止的（AGENTS.md §8）
                Log.e(TAG, "$what 失败 recordId=$recordId", e)
            } finally {
                reloadSignal.update { it + 1 }
            }
        }
    }

    private suspend fun render(record: Record?) {
        if (record == null) {
            _uiState.update { RecordDetailUiState.NotFound }
            return
        }
        try {
            val assets = assetRepository.getByRecordId(record.id)
            val derived = getDerivedContent(record.id)
            val tags = tagRepository.getByRecordId(record.id)
            // 转写区失败判据（2026-10-29 修复 P2）：精确读 TRANSCRIPTION 任务自身状态。
            // 记录级 processingStatus.hasFailure 会被摘要/嵌入失败（PARTIAL）污染 ——
            // 静音录音转出空文本算成功，随后"没有可用于摘要的正文"却让整个记录带失败，
            // 转写区跟着报"转写失败"就是误报。没有任务行 = 还没排上/老数据，按未失败处理。
            val transcribeJobFailed = processingJobRepository
                .getByRecordAndType(record.id, JobType.TRANSCRIPTION)?.status == JobStatus.FAILED
            // 合集成员实时读取（M20）：成员的原记录、派生正文与附件都要一起拿，
            // 详情页展示与门禁（确认范围 / 敏感并集）共用这一份，避免两处各查一遍再走样。
            val collection = loadDetailCollectionState(
                record = record,
                collectionRepository = collectionRepository,
                getDerivedContent = getDerivedContent,
                assetRepository = assetRepository,
            )
            // 全部门禁判据抽在 computeDetailGateState（DetailGateState.kt），判据零改动
            val gate = computeDetailGateState(
                record = record,
                assets = assets,
                derived = derived,
                cloudAiEnabled = aiSettings.cloudAiEnabled,
                gatewayConfigured = gatewaySettings.isConfigured,
                collectionMembers = collection.members,
            )
            _uiState.update {
                RecordDetailUiState.Content(
                    record = record,
                    assets = assets,
                    derived = derived,
                    tags = tags,
                    cloudBlockReason = gate.cloudBlockReason,
                    cloudConfirmPending = gate.cloudConfirmPending,
                    canRetry = gate.canRetry,
                    canRetryEmptySummary = gate.canRetryEmptySummary,
                    webFetchConfirmVisible = gate.webFetchConfirmVisible,
                    webFetchPending = gate.webFetchPending,
                    collection = collection,
                    transcribeJobFailed = transcribeJobFailed,
                )
            }
        } catch (e: CancellationException) {
            // collectLatest 用取消来表达"有新值了"，这里必须放行，否则订阅会静默停摆
            throw e
        } catch (e: Exception) {
            // 记录日志后给一句人话，不让用户看见异常类名（AGENTS.md §8 禁止空 catch）
            Log.e(TAG, "详情页读取失败 recordId=${record.id}", e)
            _uiState.update { RecordDetailUiState.Error(R.string.detail_error_load) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 页面销毁必须释放播放器：漏掉会一直占着音频焦点，其它 App 播不出声
        audio.release()
    }

    companion object {
        internal const val TAG = "RecordDetailViewModel"
    }
}
