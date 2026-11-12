package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.SearchHistoryRepository
import com.zhicun.app.domain.search.SearchQuery
import javax.inject.Inject

/**
 * 记录一次搜索（M21）。
 *
 * 只接受**显式搜索**（回车、点开结果、点历史/标签 chip）：边打边搜的每一次防抖
 * 都会产生半截词（`com`/`comp`/`compo`），把它们写进历史会让这个区域变成噪声。
 *
 * 归一化与去重键都在这里收口：调用方传原文即可，空白串直接丢弃。
 */
class RecordSearchHistoryUseCase @Inject constructor(
    private val repository: SearchHistoryRepository,
) {

    suspend operator fun invoke(raw: String, now: Long = System.currentTimeMillis()) {
        val normalized = SearchQuery.normalize(raw) ?: return
        repository.record(normalized, now)
    }
}