package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.SearchHistoryRepository
import javax.inject.Inject

/** 清空全部搜索历史（M21）。物理删除，不做软删/回收站。 */
class ClearSearchHistoryUseCase @Inject constructor(
    private val repository: SearchHistoryRepository,
) {

    suspend operator fun invoke() {
        repository.clear()
    }
}