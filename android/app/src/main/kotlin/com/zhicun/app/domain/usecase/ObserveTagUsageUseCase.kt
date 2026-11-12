package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.TagUsage
import com.zhicun.app.domain.repository.TagRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * 订阅"可点的标签"（M21）：按使用记录数降序，同名 AI/用户标签合并。
 *
 * 口径由数据层保证（只统计未删除、未归档的记录），这里不再过滤 ——
 * 否则"标签数"会出现两套判据，界面与卡片迟早对不上。
 */
class ObserveTagUsageUseCase @Inject constructor(
    private val tagRepository: TagRepository,
) {

    operator fun invoke(limit: Int = DEFAULT_LIMIT): Flow<List<TagUsage>> =
        tagRepository.observeTagUsage(limit)

    companion object {
        /** 空闲态最多展示的标签数：一行放不下时会被 FlowRow 折行，12 个大约两到三行。 */
        const val DEFAULT_LIMIT: Int = 12
    }
}