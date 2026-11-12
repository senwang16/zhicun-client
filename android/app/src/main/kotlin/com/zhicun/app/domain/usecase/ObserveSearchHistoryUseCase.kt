package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.SearchHistoryEntry
import com.zhicun.app.domain.repository.SearchHistoryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** 订阅最近搜索（M21）。默认只给界面 10 条，库里另有 30 条上限。 */
class ObserveSearchHistoryUseCase @Inject constructor(
    private val repository: SearchHistoryRepository,
) {

    operator fun invoke(limit: Int = SearchHistoryRepository.DISPLAY_LIMIT): Flow<List<SearchHistoryEntry>> =
        repository.observeRecent(limit)
}