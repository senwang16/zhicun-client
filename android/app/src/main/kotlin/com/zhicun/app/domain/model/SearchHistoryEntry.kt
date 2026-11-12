package com.zhicun.app.domain.model

/**
 * 一条搜索历史（M21）。
 *
 * 只承载"用户搜过什么"这一件事：不带结果数、不带命中记录 id ——
 * 历史是输入辅助，不是统计报表；多存一份结果数就得在结果变化时同步维护，
 * 而它对用户没有任何用处。
 */
data class SearchHistoryEntry(
    /** 去重键（归一化后的小写查询串），删除单条时回传。 */
    val queryKey: String,
    /** 展示用查询串（保留用户最近一次的写法）。 */
    val query: String,
    val searchedAt: Long,
)