package com.zhicun.app.presentation.search

import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.TagUsage

/**
 * 搜索页状态（M10）。
 *
 * 五种互斥状态，界面按它渲染，不做二次推断：
 * - [Idle]：还没输入（或刚清空）—— 显示引导语，不是"空结果"
 * - [Searching]：有关键词但结果还没出来 —— 显示进度，避免"输一半就闪空态"
 * - [Results]：有命中
 * - [Empty]：搜完了确实没有 —— 必须与 [Idle] 区分（PRD 要求"无结果时显示明确提示"）
 * - [Error]：检索抛异常
 */
sealed interface SearchUiState {

    data object Idle : SearchUiState

    data class Searching(val keyword: String) : SearchUiState

    /**
     * @param keywordOnly 本次结果只用了关键词（FTS/子串）匹配，没有语义召回。
     *   M12 起由数据层根据"语义路是否真的参与了"决定（网关不可用、本地还没有向量、
     *   查询向量为空都会回落到 true），界面据此显示"当前为关键词搜索结果"的降级提示。
     * @param semanticPending 两段式检索的第一段（2026-10-24 搜索卡顿整改）：关键词结果
     *   已就绪、语义融合还在网络往返中。界面照常渲染结果列表，另显示"语义排序中"提示，
     *   **不**当作降级；终态（第二段）到达后本字段为 false，届时 keywordOnly 才如实生效。
     * @param resultTags 结果页"结果标签"（M21 决策 3）：这批结果上的标签，按命中记录数降序；
     *   结果少于 2 条或标签少于 2 个时为空。它是"结果内"口径，不是全库标签云。
     * @param narrowedTag 当前是否按某个结果标签收窄；null = 展示完整结果。
     * @param narrowDroppedTag 语义段把收窄标签挤出结果集时，被自动取消的标签名；
     *   界面据此提示"这批结果里已经没有 #x 的记录"，下一次操作后清空。
     */
    data class Results(
        val keyword: String,
        val records: List<Record>,
        val keywordOnly: Boolean = true,
        val semanticPending: Boolean = false,
        val resultTags: List<TagUsage> = emptyList(),
        val narrowedTag: String? = null,
        val narrowDroppedTag: String? = null,
    ) : SearchUiState

    data class Empty(val keyword: String) : SearchUiState

    data class Error(val messageRes: Int) : SearchUiState
}
