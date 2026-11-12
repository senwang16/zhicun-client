package com.zhicun.app.presentation.detail

/**
 * 详情页"整理"分区的输入态（M16）。
 *
 * 只有标签输入需要在多次组合之间保留；收藏/置顶/归档是即点即生效的标记，
 * 它们的结果直接体现在 `RecordDetailUiState.Content.record` 上，不另存一份
 * —— 另存就会出现"按钮说已收藏、记录本身说没收藏"的双份真源。
 *
 * 与 `DetailOrganizeBlock.kt` 分开放：那个文件已经承载了整个分区 UI，
 * 再塞状态类会让"状态从哪来"更难找；这里的类被 ViewModel 与 UI 同时引用，
 * 单独一个文件是最小公共面。
 *
 * @param tagInput 输入框里的原始文本（未规范化）。
 * @param tagError 上一次添加失败的原因；用户重新输入后清空。
 */
data class DetailOrganizeState(
    val tagInput: String = "",
    val tagError: TagInputError? = null,
)
