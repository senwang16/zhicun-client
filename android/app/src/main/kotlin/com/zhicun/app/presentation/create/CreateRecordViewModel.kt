package com.zhicun.app.presentation.create

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhicun.app.R
import com.zhicun.app.domain.file.AssetNameResolver
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.repository.DraftRepository
import com.zhicun.app.domain.usecase.BuildRecordInputUseCase
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一次性事件：导航归导航层管，ViewModel 只负责说"该走了"。 */
sealed interface CreateRecordEvent {

    /** 保存成功，参数是新建记录的 id（可据此跳详情，M5 暂只回列表）。 */
    data class Saved(val recordId: String) : CreateRecordEvent

    /** 放弃/无需保存，退出编辑页。 */
    data object NavigateBack : CreateRecordEvent

    /** 去录音页（M21）：录音是独立的记录，不加入本条附件。 */
    data object NavigateToVoice : CreateRecordEvent
}

/**
 * 新建记录页 ViewModel（M5）。
 *
 * 三条职责：编辑态管理、保存（复用 M4 的 [SaveRecordUseCase]）、草稿落盘。
 * 保存必须走 UseCase 而不是直接调仓储：附件复制顺序、敏感检测、类型推断都在那里，
 * 绕过它等于把 M4 已经验证过的规则重写一遍。
 */
@HiltViewModel
class CreateRecordViewModel @Inject constructor(
    private val saveRecord: SaveRecordUseCase,
    private val buildInput: BuildRecordInputUseCase,
    private val draftRepository: DraftRepository,
    private val assetNameResolver: AssetNameResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateRecordUiState())
    val uiState: StateFlow<CreateRecordUiState> = _uiState.asStateFlow()

    private val _events = Channel<CreateRecordEvent>(Channel.BUFFERED)
    val events: Flow<CreateRecordEvent> = _events.receiveAsFlow()

    /**
     * 只承载"用户键入了什么"，用于草稿防抖落盘。
     *
     * 刻意不复用 [uiState] 做防抖：草稿恢复、保存成功等状态变化也会让 uiState 发出，
     * 那样刚清掉的草稿会被立刻写回去。
     */
    private val textEdits = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /**
     * 防抖落盘的协程句柄。
     *
     * 必须持有并可取消：用户打完字 400ms 内就点保存时，`clear()` 先执行、
     * 排队的防抖任务随后才触发，会把刚清掉的草稿又写回文件 —— 下次进新建页就会"复活"旧内容。
     */
    private var draftPersistJob: Job? = null

    init {
        restoreDraft()
        persistDraftDebounced()
    }

    fun onTextChange(value: String) {
        _uiState.update {
            it.copy(text = value, errorMessageRes = null, restoredFromDraft = false)
        }
        textEdits.tryEmit(value)
    }

    fun onAttachmentPicked(uri: String, kind: AttachmentKind) {
        if (uri.isBlank()) return
        _uiState.update { state ->
            if (state.attachments.any { it.uri == uri }) {
                state // 重复选同一个文件不重复挂
            } else {
                state.copy(
                    attachments = state.attachments + PendingAttachment(uri = uri, kind = kind),
                    errorMessageRes = null,
                )
            }
        }
        // 名字异步补齐：先让附件条立刻出现，查到名字再就地更新。
        // 用户在此期间移除该附件也没关系 —— 下面的 map 找不到 uri 就什么都不改。
        viewModelScope.launch {
            val name = resolveNameQuietly(uri) ?: return@launch
            _uiState.update { state ->
                state.copy(
                    attachments = state.attachments.map {
                        if (it.uri == uri) it.copy(displayName = name) else it
                    },
                )
            }
        }
    }

    fun onAttachmentRemoved(uri: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.uri == uri })
        }
    }

    /**
     * 点语音入口（M21）。
     *
     * 录音会**离开本页**并生成另一条记录；当前文字虽然会落成草稿，但附件不会保留，
     * 所以有内容时先问一句。没有内容就直接去录音，不多一步点击。
     */
    fun onVoiceRequested() {
        if (_uiState.value.hasUnsavedContent) {
            _uiState.update { it.copy(showVoiceConfirmDialog = true) }
        } else {
            _events.trySend(CreateRecordEvent.NavigateToVoice)
        }
    }

    fun onVoiceConfirmed() {
        _uiState.update { it.copy(showVoiceConfirmDialog = false) }
        val text = _uiState.value.text
        viewModelScope.launch {
            // 对话框承诺“已输入的文字会保留为草稿”，但防抖窗口是 400ms：用户打完字立刻
            // 确认去录音时，页面销毁会取消 viewModelScope 里的防抖任务，文字就丢了。
            // 因此离开前显式落盘一次（2026-10-28 修复）。
            try {
                draftRepository.saveText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 草稿写失败不该把用户拦在新建页：继续去录音，只留痕
                Log.e(TAG, "去录音前保存草稿失败", e)
            }
            _events.trySend(CreateRecordEvent.NavigateToVoice)
        }
    }

    fun onVoiceDismissed() {
        _uiState.update { it.copy(showVoiceConfirmDialog = false) }
    }

    /**
     * 保存。
     *
     * 失败时**保留输入与附件**，只给一句可重试的提示（M5 验收标准）；
     * 成功后清草稿再通知导航，顺序不能反 —— 先跳走再清草稿会留下"下次打开又冒出旧内容"。
     */
    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true, errorMessageRes = null) }
        viewModelScope.launch {
            try {
                val input = buildInput(
                    text = state.text,
                    picked = state.attachments.map {
                        InputUri(uri = it.uri, mimeType = null, displayName = it.displayName)
                    },
                )
                val result = saveRecord(input, CaptureSource.MANUAL)
                discardDraft()
                _uiState.update { it.copy(isSaving = false) }
                _events.send(CreateRecordEvent.Saved(result.recordId))
            } catch (e: CancellationException) {
                // 协程被取消（页面销毁）不是错误，必须原样抛出，否则会吞掉取消信号
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "手动保存记录失败", e)
                _uiState.update {
                    it.copy(isSaving = false, errorMessageRes = R.string.create_save_failed)
                }
            }
        }
    }

    /** 返回键/取消：有内容先确认，避免一次误触丢掉正在写的字。 */
    fun onBackRequested() {
        if (_uiState.value.hasUnsavedContent) {
            _uiState.update { it.copy(showDiscardDialog = true) }
        } else {
            _events.trySend(CreateRecordEvent.NavigateBack)
        }
    }

    fun onDiscardConfirmed() {
        _uiState.update { it.copy(showDiscardDialog = false) }
        viewModelScope.launch {
            discardDraft()
            _events.send(CreateRecordEvent.NavigateBack)
        }
    }

    fun onDiscardDismissed() {
        _uiState.update { it.copy(showDiscardDialog = false) }
    }

    /**
     * 清掉草稿，并**先停掉防抖落盘**。
     *
     * 顺序不能反：先 clear 再 cancel 的话，队列里那次防抖仍会执行一次 `saveText`，
     * 把刚丢弃的内容重新写回文件。这也是"用户打完字立刻点保存"这个真实场景会踩到的顺序问题。
     */
    private suspend fun discardDraft() {
        draftPersistJob?.cancel()
        draftPersistJob = null
        draftRepository.clear()
    }

    private fun restoreDraft() {
        viewModelScope.launch {
            val draft = draftRepository.loadText() ?: return@launch
            // 用户可能已经开始打字（恢复是异步的），此时不能覆盖他的手输内容
            _uiState.update { state ->
                if (state.text.isBlank()) {
                    state.copy(text = draft, restoredFromDraft = true)
                } else {
                    state
                }
            }
        }
    }

    /**
     * 防抖落盘：每次输入都写文件会有大量无用 IO，而延迟 400ms 在"切走页面"这个场景里
     * 仍然足够短（页面切走前必然有一次输入事件 + 防抖窗口）。
     * 用 `FlowPreview.debounce` —— 它在 kotlinx.coroutines 中长期稳定但仍是实验 API，
     * 此处只需"延迟一会儿"，行为确定，故接受该 opt-in。
     */
    @OptIn(FlowPreview::class)
    private fun persistDraftDebounced() {
        draftPersistJob = viewModelScope.launch {
            textEdits.debounce(DRAFT_DEBOUNCE_MS).collect { text ->
                draftRepository.saveText(text)
            }
        }
    }

    /**
     * 查附件原名；查不到或出错都只是回到序号标签，不打扰用户。
     * 取消必须放行：用户离开页面时协程被取消，不能被这里当成"没名字"吞掉。
     */
    private suspend fun resolveNameQuietly(uri: String): String? = try {
        assetNameResolver.resolve(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 不记 URI：可能带资源标识，界面层没有脱敏工具，宁可不记（AGENTS §6.1 日志脱敏）
        Log.d(TAG, "读取附件文件名失败，回退序号标签", e)
        null
    }

    private companion object {
        const val TAG = "CreateRecord"
        const val DRAFT_DEBOUNCE_MS = 400L
    }
}
