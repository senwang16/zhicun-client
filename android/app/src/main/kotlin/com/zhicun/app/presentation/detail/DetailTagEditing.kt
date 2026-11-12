package com.zhicun.app.presentation.detail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zhicun.app.domain.usecase.AddTagResult
import com.zhicun.app.domain.usecase.AddUserTagUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 详情页标签编辑动作（M16 / U-7）—— 从 `RecordDetailViewModel` 原样抽出。
 *
 * 纯移动、零行为变更，目的是让 ViewModel 回到 400 行以内（AGENTS.md §2.1）。
 * 公开入口仍是 ViewModel 上的成员函数：界面层以 `viewModel::onTagInputChange`
 * 形式绑定方法引用，保留薄包装比让调用点改成扩展函数引用更稳。
 */

/** 用户改标签输入框：同时清掉上一次的错误提示，否则会一直标红。 */
internal fun RecordDetailViewModel.updateTagInput(text: String) {
    _organize.update { it.copy(tagInput = text, tagError = null) }
}

/**
 * 添加用户标签（M16）。
 *
 * 规范化（trim、合并内部空白）在 [AddUserTagUseCase] 里做，这里不重复 ——
 * 标签是跨记录共享的，各处各 trim 一次迟早会走样。
 */
internal fun RecordDetailViewModel.submitTag() {
    val record = currentRecord() ?: return
    val raw = _organize.value.tagInput
    viewModelScope.launch {
        try {
            when (val result = addUserTag(record.id, raw)) {
                is AddTagResult.Added -> {
                    // 清空输入：用户往往要连着加两三个
                    _organize.value = DetailOrganizeState()
                    reloadSignal.update { it + 1 }
                }

                // 全是空白：静默清掉，不报错（用户可能只是误触了回车）
                AddTagResult.EmptyName -> _organize.value = DetailOrganizeState()

                AddTagResult.NameTooLong ->
                    _organize.update { it.copy(tagError = TagInputError.TooLong) }

                AddTagResult.Duplicate ->
                    _organize.update { it.copy(tagError = TagInputError.Duplicate) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RecordDetailViewModel.TAG, "添加标签失败 recordId=${record.id}", e)
        }
    }
}

/** 从记录上摘掉一个标签（M16）。标签行本身保留，别的记录可能还在用它。 */
internal fun RecordDetailViewModel.removeTagById(tagId: String) {
    if (tagId.isBlank()) return
    runOrganizeAction("删除标签") {
        tagRepository.removeTag(recordId, tagId)
    }
}

/**
 * 标签行编辑保存（U-7，2026-10-24 用户反馈）：点标签行进入编辑，保存时传全量标签名。
 *
 * 差量落库，不整体重建：
 * - 名字没变的标签（无论 AI/用户来源）一律不动 —— AI 标签保持 AI 来源，
 *   下次摘要重算时还能被模型修正；
 * - 新名单里没有的删除；
 * - 新名字经 [addUserTag] 落成**用户标签**。"改 AI 标签"的实际语义是
 *   "删旧 AI 标签 + 建同名用户标签"：AI 标签会被下次摘要重算覆盖，
 *   用户标签才是持久的。
 *
 * 规范化复用 [AddUserTagUseCase.normalize]：差量对比必须与落库用同一个名字标准，
 * 否则"会议　"（带全角空格）会被判成改名，把原标签删了又加回来。
 * 空名单不做事：界面上空输入已被当作"取消"，这里再兜一层防误传。
 */
internal fun RecordDetailViewModel.saveEditedTags(names: List<String>) {
    val record = currentRecord() ?: return
    viewModelScope.launch {
        try {
            val cleaned = names.map { AddUserTagUseCase.normalize(it) }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            if (cleaned.isEmpty()) return@launch

            val existing = tagRepository.getByRecordId(record.id)
            existing.filter { tag -> cleaned.none { it.equals(tag.name, ignoreCase = true) } }
                .forEach { tag ->
                    Log.i(RecordDetailViewModel.TAG, "标签编辑：删除 \"${tag.name}\" recordId=${record.id}")
                    tagRepository.removeTag(record.id, tag.id)
                }
            // 只加"旧名单里没有"的名字：与现有 AI 标签同名的名字不加 —— addUserTag 的
            // 判重只看 (name, USER)，不拦的话会在 AI 标签旁边再挂一个同名用户标签，
            // 界面上出现两个一样的 chip。Duplicate 在这条路径下理论上到不了，兜底静默
            cleaned.filter { name -> existing.none { it.name.equals(name, ignoreCase = true) } }
                .forEach { name ->
                    when (addUserTag(record.id, name)) {
                        is AddTagResult.Added, AddTagResult.Duplicate, AddTagResult.EmptyName -> Unit
                        is AddTagResult.NameTooLong ->
                            Log.w(RecordDetailViewModel.TAG, "标签超长未保存 name=$name recordId=${record.id}")
                    }
                }
            reloadSignal.update { it + 1 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(RecordDetailViewModel.TAG, "编辑标签失败 recordId=${record.id}", e)
        }
    }
}
