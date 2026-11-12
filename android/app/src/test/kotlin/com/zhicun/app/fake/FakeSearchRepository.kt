package com.zhicun.app.fake

import com.zhicun.app.domain.repository.SearchRepository

/**
 * 检索仓储假实现（M10）。
 *
 * 不做任何真实匹配逻辑，只按测试预置的映射回答：这样 `SearchRecordsUseCase`
 * 的顺序/去重/截断规则可以被单独验证，不被"假分词"干扰。
 * 真实匹配行为由 `SearchRepositoryImplTest`（跑内存 Room + 真 FTS4）覆盖。
 */
class FakeSearchRepository : SearchRepository {

    /** 归一化查询串 → 命中 id（按期望顺序）。 */
    val resultIds: MutableMap<String, List<String>> = mutableMapOf()

    /** 置为非 null 时 [searchIds] 抛该异常，用于测 Error 分支。 */
    var failure: Throwable? = null

    /** 记录每次调用的 (查询串, limit)，用于断言归一化与上限传递。 */
    val calls: MutableList<Pair<String, Int>> = mutableListOf()

    override suspend fun searchIds(normalized: String, limit: Int): List<String> {
        calls += normalized to limit
        failure?.let { throw it }
        return resultIds[normalized].orEmpty()
    }
}
