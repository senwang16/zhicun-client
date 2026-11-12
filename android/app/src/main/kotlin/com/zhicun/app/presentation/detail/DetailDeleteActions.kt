package com.zhicun.app.presentation.detail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zhicun.app.domain.usecase.DeleteAssetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 详情页的删除动作（2026-10-29）—— 从 `RecordDetailViewModel` 独立出来，
 * 保持 ViewModel 在 AGENTS.md §2.1 的 400 行以内。
 *
 * 公开入口仍是 ViewModel 上的成员函数（`viewModel::onDeleteAsset` 这类方法引用要用），
 * 真正的分支逻辑放在这里。
 */

/**
 * 一次性结果事件。
 *
 * 不放进 `RecordDetailUiState` 的原因：这些是"发生过"的事实（提示条、引导框），
 * 关掉就该消失。放进状态流的话，旋转屏幕重建页面会把已经处理过的对话框又弹一遍。
 */
sealed interface RecordDetailEvent {

    data object AssetDeleted : RecordDetailEvent
    data object AssetDeleteFailed : RecordDetailEvent

    /** 删的是最后一项内容：界面引导改为删除整条记录。 */
    data object LastContent : RecordDetailEvent

    data object RecordDeleted : RecordDetailEvent
    data object RecordDeleteFailed : RecordDetailEvent
}

/**
 * 删除单个附件。
 *
 * 删除前若正播着这一段，先暂停：文件马上会被删掉，让播放器继续拿着已被删除的路径
 * 只会在几秒后以"播放失败"收场，用户看到的是莫名其妙的错误而不是自己刚做的操作。
 */
internal fun RecordDetailViewModel.deleteAssetById(assetId: String) {
    val record = currentRecord() ?: return
    if (playback.value.activeAssetId == assetId) onPlayPauseClicked(assetId)
    viewModelScope.launch {
        try {
            when (deleteAsset(record.id, assetId)) {
                DeleteAssetResult.Deleted -> {
                    reloadSignal.update { it + 1 }
                    emitEvent(RecordDetailEvent.AssetDeleted)
                }

                // 最后一项内容：不删，交给页面问"要不要删整条记录"
                DeleteAssetResult.LastContent -> emitEvent(RecordDetailEvent.LastContent)

                // 文件删不掉时数据库一行没动，界面保持原样即可，用户可再试一次
                DeleteAssetResult.FileDeleteFailed -> emitEvent(RecordDetailEvent.AssetDeleteFailed)

                // 并发删除后重入：重新读一次，界面自然回到真实状态，不必再提示
                DeleteAssetResult.NotFound -> reloadSignal.update { it + 1 }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RecordDetailViewModel.TAG, "删除附件失败 recordId=${record.id} assetId=$assetId", e)
            emitEvent(RecordDetailEvent.AssetDeleteFailed)
        }
    }
}

/**
 * 删除整条记录。
 *
 * 复用收件箱同一个用例（软删除 + 取消任务 + 物理清理），成功后由页面直接返回列表：
 * 继续停在一条已不存在的记录上，用户看到的只能是"记录不存在"。
 */
internal fun RecordDetailViewModel.deleteCurrentRecord() {
    val record = currentRecord() ?: return
    viewModelScope.launch {
        try {
            deleteRecords(listOf(record.id))
            emitEvent(RecordDetailEvent.RecordDeleted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RecordDetailViewModel.TAG, "删除记录失败 recordId=${record.id}", e)
            emitEvent(RecordDetailEvent.RecordDeleteFailed)
        }
    }
}
