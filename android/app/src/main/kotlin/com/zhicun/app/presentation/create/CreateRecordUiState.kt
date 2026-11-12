package com.zhicun.app.presentation.create

import androidx.annotation.StringRes

/** 附件的来源类别，只用于界面标签；真实 MIME 在保存时由 UseCase 按 URI 查询。 */
enum class AttachmentKind {
    IMAGE,
    FILE,
}

/**
 * 已选中但尚未保存的附件。
 *
 * 只保留 URI、类别与**展示用的原始文件名**：MIME 解析与真实复制都要碰 `ContentResolver`，
 * 属 data 层职责，留到保存时由 UseCase 查。文件名是例外 —— 用户选完就要看到
 * "IMG_20261001.jpg" 而不是"图片 1"，否则多附件时根本分不清自己选了哪几张。
 * 拿不到名字（`displayName == null`）时界面回退序号标签。
 */
data class PendingAttachment(
    val uri: String,
    val kind: AttachmentKind,
    val displayName: String? = null,
)

/**
 * 新建记录页状态。
 *
 * 用不可变 data class + `MutableStateFlow.update` 而不是散落的多个 StateFlow：
 * "能不能保存"同时依赖 text 与 attachments，拆开会出现一瞬不一致的中间态（AGENTS.md §2.2）。
 */
data class CreateRecordUiState(
    val text: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val isSaving: Boolean = false,
    /** 保存失败提示；用资源 id 而不是字符串，便于测试断言且不硬编码中文。 */
    @param:StringRes val errorMessageRes: Int? = null,
    /** 从草稿恢复过内容：界面上给一句说明，避免用户以为是自己刚打的字。 */
    val restoredFromDraft: Boolean = false,
    /** 返回时弹"丢弃内容"确认。 */
    val showDiscardDialog: Boolean = false,
    /** M21：有未保存内容时点语音入口，先确认"会离开此页"。 */
    val showVoiceConfirmDialog: Boolean = false,
) {

    /** 空内容禁止保存（M5 验收标准 / PRD §11 禁止创建空记录）。 */
    val canSave: Boolean
        get() = !isSaving && (text.isNotBlank() || attachments.isNotEmpty())

    /** 有未保存内容时，返回要先确认。 */
    val hasUnsavedContent: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty()
}
