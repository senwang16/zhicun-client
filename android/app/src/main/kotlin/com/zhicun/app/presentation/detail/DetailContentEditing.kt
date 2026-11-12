package com.zhicun.app.presentation.detail

import android.util.Log
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.usecase.UpdateRecordTextResult
import com.zhicun.app.domain.usecase.UpdateRecordTextUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页原文编辑的输入态（2026-10-26）。
 *
 * 与 `DetailOrganizeState` 分开放：那份管标签输入，这份管整段原文，
 * 两者可以同时存在（编辑原文时标签输入框也可能留着草稿），
 * 塞进同一个类会让"清空标签草稿"顺手把原文编辑也关掉。
 */
data class DetailContentEditState(
    val editing: Boolean = false,
    val draft: String = "",
    val saving: Boolean = false,
    val error: DetailContentEditError? = null,
)

/** 原文编辑失败的原因（表现层类型：领域层的结果带成功分支，不该漏到 UI）。 */
enum class DetailContentEditError {
    /** 内容为空：原文是记录的核心，不允许保存成空白。 */
    Empty,

    /** 期间有别的写入推进了版本：重新加载后再改。 */
    Conflict,

    /** 读库/写库/排队失败。 */
    Failed,
}

/**
 * 原文编辑状态机（2026-10-26）。
 *
 * 单独成类而不是塞进 ViewModel：ViewModel 已接近 400 行上限（AGENTS.md §2.1），
 * 而这块有独立的输入态、进行中标记与失败原因，正好是一个内聚单元。
 *
 * **不做失焦取消**：与标签编辑不同，原文可能是一整篇长文，误点一下页面就丢掉
 * 几十分钟的修改代价太大。取消只走显式按钮或返回键（见 `RawTextCard` 的 `BackHandler`）。
 */
internal class DetailContentEditing(
    private val scope: CoroutineScope,
    private val updateRecordText: UpdateRecordTextUseCase,
    private val currentRecord: () -> Record?,
    private val onRecordChanged: () -> Unit,
) {

    private val _state = MutableStateFlow(DetailContentEditState())
    val state: StateFlow<DetailContentEditState> = _state.asStateFlow()

    /** 开始编辑：预填当前原文。已在编辑时不覆盖草稿（重复点击不该清掉用户输入）。 */
    fun start() {
        if (_state.value.editing) return
        val record = currentRecord() ?: return
        _state.update {
            DetailContentEditState(editing = true, draft = record.rawText.orEmpty())
        }
    }

    fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text, error = null) }
    }

    fun cancel() {
        if (_state.value.saving) return
        _state.update { DetailContentEditState() }
    }

    /**
     * 保存并触发 AI 重算。
     *
     * 成功或"内容没变"都关闭编辑；失败保留编辑态与草稿 —— 用户刚写的字不能因为
     * 一次读库失败就消失。失败原因区分"空内容 / 版本冲突 / 其它"，前两者用户能自己解决。
     */
    fun save() {
        val record = currentRecord() ?: run { cancel(); return }
        val draft = _state.value.draft
        if (draft.isBlank()) {
            _state.update { it.copy(error = DetailContentEditError.Empty) }
            return
        }
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }

        scope.launch {
            try {
                when (updateRecordText(record.id, draft)) {
                    UpdateRecordTextResult.Updated -> {
                        close()
                        onRecordChanged()
                    }

                    UpdateRecordTextResult.Unchanged -> close()

                    UpdateRecordTextResult.NotFound -> {
                        close()
                        onRecordChanged()
                    }

                    UpdateRecordTextResult.Conflict ->
                        _state.update { it.copy(saving = false, error = DetailContentEditError.Conflict) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "保存原文失败 recordId=${record.id}", e)
                _state.update { it.copy(saving = false, error = DetailContentEditError.Failed) }
            }
        }
    }

    private fun close() {
        _state.update { DetailContentEditState() }
    }

    private companion object {
        const val TAG = "DetailContentEditing"
    }
}
