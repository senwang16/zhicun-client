package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.SearchHistoryRepository
import javax.inject.Inject

/** 删除一条搜索历史（M21，长按 chip）。 */
class DeleteSearchHistoryUseCase @Inject constructor(
    private val repository: SearchHistoryRepository,
) {

    suspend operator fun invoke(queryKey: String) {
        repository.delete(queryKey)
    }
}