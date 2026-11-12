package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.search.ScoredRecordId
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeEmbedder
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeSearchRepository
import com.zhicun.app.fake.FakeSemanticIndexRepository
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合检索用例（M10 关键词 + M12 语义）。
 *
 * 这里验的是**编排**：何时查关键词、何时查语义、两路怎么融合、语义失败怎么降级。
 * 真实匹配能力由 `SearchRepositoryImplTest` / `SemanticIndexRepositoryImplTest` 覆盖，
 * 语义不可用的具体原因不是本用例要判断的。
 */
class SearchRecordsUseCaseTest {

    private val searchRepository = FakeSearchRepository()
    private val recordRepository = FakeRecordRepository(
        listOf(
            testRecord(id = "r1", rawText = "断联陪伴"),
            testRecord(id = "r2", rawText = "陪伴 复盘"),
            testRecord(id = "r3", rawText = "可以本地部署的视频模型"),
        ),
    )
    private val embedder = FakeEmbedder()
    private val semanticIndex = FakeSemanticIndexRepository()
    private val derivedContentRepository = FakeDerivedContentRepository()
    private val tagRepository = FakeTagRepository()
    private val aiSettings = FakeAiSettings()
    private val useCase = SearchRecordsUseCase(
        searchRepository = searchRepository,
        recordRepository = recordRepository,
        embedder = embedder,
        semanticIndex = semanticIndex,
        derivedContentRepository = derivedContentRepository,
        tagRepository = tagRepository,
        aiSettings = aiSettings,
        gatewaySettings = FakeAiGatewaySettings(),
    )

    /** 让语义路"确实参与了"：至少比较过一条向量。 */
    private fun semanticHits(vararg pairs: Pair<String, Double>) {
        semanticIndex.hits = pairs.map { ScoredRecordId(it.first, it.second) }
        semanticIndex.comparedCount = pairs.size.coerceAtLeast(1)
    }

    @Test
    fun Invoke_blankQuery_returnsEmptyWithoutQueryingAnything() = runTest {
        assertTrue(useCase("   ").records.isEmpty())
        assertTrue(useCase(null).records.isEmpty())
        assertTrue("空查询不该触碰关键词检索", searchRepository.calls.isEmpty())
        assertTrue("也不该白花一次网关调用", embedder.calls.isEmpty())
    }

    @Test
    fun Invoke_normalizesQueryBeforeSearch() = runTest {
        searchRepository.resultIds["陪伴 复盘"] = listOf("r2")

        useCase("  陪伴   复盘  ")

        assertEquals(
            listOf("陪伴 复盘" to SearchRecordsUseCase.DEFAULT_LIMIT),
            searchRepository.calls,
        )
        assertEquals("语义路拿到的应是同一个归一化串", listOf("陪伴 复盘"), embedder.calls)
    }

    @Test
    fun Invoke_preservesKeywordOrderWhenSemanticAbsent() = runTest {
        // comparedCount = 0 → 本地还没有向量，语义路没有参与
        recordRepository.stored.value = listOf(
            testRecord(id = "r2", createdAt = 2L),
            testRecord(id = "r1", createdAt = 1L),
        )
        searchRepository.resultIds["陪伴"] = listOf("r1", "r2")

        val outcome = useCase("陪伴")

        assertEquals(listOf("r1", "r2"), outcome.records.map { it.id })
        assertTrue("没有向量时必须如实说只有关键词结果", outcome.keywordOnly)
        assertNotNull(outcome.degradeReason)
    }

    @Test
    fun Invoke_semanticOnlyHit_isIncluded() = runTest {
        // 原文里没有"视频模型"以外的线索，语义路补上 r3
        searchRepository.resultIds["想找的东西"] = listOf("r2")
        semanticHits("r3" to 0.82)

        val outcome = useCase("想找的东西")

        assertEquals(setOf("r2", "r3"), outcome.records.map { it.id }.toSet())
        assertFalse("语义参与了就不该提示降级", outcome.keywordOnly)
        assertNull(outcome.degradeReason)
    }

    @Test
    fun Invoke_recordHitByBothPaths_ranksFirst() = runTest {
        // r1 在两路都靠后（关键词第 2、语义第 2），r2 只在关键词第 1
        searchRepository.resultIds["陪伴"] = listOf("r2", "r1")
        semanticHits("r3" to 0.9, "r1" to 0.8)

        val outcome = useCase("陪伴")

        assertEquals("两路都命中的记录应排第一", "r1", outcome.records.first().id)
    }

    @Test
    fun Invoke_semanticFailure_degradesToKeywordOnly() = runTest {
        embedder.failure = IOException("网关 503")
        searchRepository.resultIds["陪伴"] = listOf("r1")

        val outcome = useCase("陪伴")

        assertEquals(listOf("r1"), outcome.records.map { it.id })
        assertTrue("语义挂了不该让搜索变空", outcome.records.isNotEmpty())
        assertTrue(outcome.keywordOnly)
        assertEquals(
            "降级原因要能被上层记录（domain 不引 Log，但也不能吞错）",
            "语义检索不可用：IOException",
            outcome.degradeReason,
        )
    }

    @Test
    fun Invoke_cloudAiDisabled_searchQueryNotSentToGateway() = runTest {
        aiSettings.cloudEnabled = false
        semanticHits("r3" to 0.82)
        searchRepository.resultIds["陪伴"] = listOf("r1")

        val outcome = useCase("陪伴")

        assertEquals(listOf("r1"), outcome.records.map { it.id })
        assertTrue("云端关闭时只该有关键词结果", outcome.keywordOnly)
        assertEquals("云端 AI 处理已关闭，内容未上传", outcome.degradeReason)
        assertTrue("搜索词绝不能发到网关（QODER P1-2）", embedder.calls.isEmpty())
    }

    @Test
    fun Invoke_semanticIndexFailure_degradesToKeywordOnly() = runTest {
        semanticIndex.failure = IllegalStateException("库被锁")
        searchRepository.resultIds["陪伴"] = listOf("r1")

        val outcome = useCase("陪伴")

        assertEquals(listOf("r1"), outcome.records.map { it.id })
        assertTrue(outcome.keywordOnly)
        assertTrue(outcome.degradeReason!!.contains("IllegalStateException"))
    }

    @Test
    fun Invoke_emptyQueryVector_degradesWithoutQueryingIndex() = runTest {
        embedder.result = Embedding(model = "test-model", vector = FloatArray(0))
        searchRepository.resultIds["陪伴"] = listOf("r1")

        val outcome = useCase("陪伴")

        assertTrue(outcome.keywordOnly)
        assertEquals("网关没给向量", "网关未返回查询向量", outcome.degradeReason)
        assertTrue("没有查询向量就不该去扫库", semanticIndex.calls.isEmpty())
    }

    @Test
    fun Invoke_indexHasVectorsButNoMatch_stillCountsAsSemanticParticipated() = runTest {
        searchRepository.resultIds["不存在"] = emptyList()
        semanticIndex.hits = emptyList()
        semanticIndex.comparedCount = 42

        val outcome = useCase("不存在")

        assertTrue(outcome.records.isEmpty())
        assertFalse(
            "索引里有向量、语义路正常跑完，只是没命中 —— 这不是降级",
            outcome.keywordOnly,
        )
    }

    @Test
    fun Invoke_missingRecord_isSkipped() = runTest {
        // 检索出 id 之后、取实体之前被删掉的记录不能以"空卡片"出现在结果里
        searchRepository.resultIds["陪伴"] = listOf("r1", "gone")

        assertEquals(listOf("r1"), useCase("陪伴").records.map { it.id })
    }

    @Test
    fun Invoke_customLimit_passedToBothPaths() = runTest {
        searchRepository.resultIds["陪伴"] = listOf("r1")
        semanticHits("r1" to 0.9)

        useCase("陪伴", limit = 5)

        assertEquals(5, searchRepository.calls.single().second)
        assertEquals("语义路也要按同一个上限截断", 5, semanticIndex.calls.single().second)
    }

    // ------------------------------------------------------------ 二级重排信号（M17）

    /** 标题命中的结果要被顶上来：PRD §7.1 把标题匹配列为排序信号。 */
    @Test
    fun Invoke_titleMatch_boostsOverAdjacentKeywordRank() = runTest {
        recordRepository.stored.value = listOf(
            testRecord(id = "plain", rawText = "别的记录"),
            testRecord(id = "titled", rawText = "别的记录"),
        )
        derivedContentRepository.upsert(
            testDerived(recordId = "titled", contentType = DerivedType.TITLE, content = "本地部署指南"),
        )
        // titled 在关键词路只排第 2，但它的标题里出现了查询词
        searchRepository.resultIds["部署"] = listOf("plain", "titled")

        val outcome = useCase("部署", nowMillis = 0L)

        assertEquals("标题命中的结果应排到前面", "titled", outcome.records.first().id)
    }

    @Test
    fun Invoke_tagMatch_boostsRank() = runTest {
        recordRepository.stored.value = listOf(
            testRecord(id = "plain", rawText = "别的记录"),
            testRecord(id = "tagged", rawText = "别的记录"),
        )
        tagRepository.addUserTag("tagged", "部署", now = 1L)
        searchRepository.resultIds["部署"] = listOf("plain", "tagged")

        val outcome = useCase("部署", nowMillis = 0L)

        assertEquals("标签命中的结果应排到前面", "tagged", outcome.records.first().id)
    }

    /** 置顶 + 较新的记录：两个信号叠加要能翻过相邻的关键词名次。 */
    @Test
    fun Invoke_pinnedAndRecent_boostsRank() = runTest {
        recordRepository.stored.value = listOf(
            testRecord(id = "old", rawText = "别的记录", createdAt = 1_000L),
            testRecord(id = "pinned", rawText = "别的记录", createdAt = 9_000L, isPinned = true),
        )
        searchRepository.resultIds["部署"] = listOf("old", "pinned")

        val outcome = useCase("部署", nowMillis = 10_000L)

        assertEquals("置顶且较新的记录应排到前面", "pinned", outcome.records.first().id)
    }

    /** 信号只在候选集里收集：与本次检索无关的标题/标签不该拖慢或影响结果。 */
    @Test
    fun Invoke_signalsOnlyCollectForCandidates() = runTest {
        recordRepository.stored.value = listOf(testRecord(id = "r1", rawText = "别的记录"))
        derivedContentRepository.upsert(
            testDerived(recordId = "elsewhere", contentType = DerivedType.TITLE, content = "部署"),
        )
        searchRepository.resultIds["部署"] = listOf("r1")

        val outcome = useCase("部署", nowMillis = 0L)

        assertEquals("候选之外不参与任何信号", listOf("r1"), outcome.records.map { it.id })
    }

    /** 标题/标签查询挂了不能毁掉一次本来成功的检索（见用例类注释的降级原则）。 */
    @Test
    fun Invoke_signalLookupFailure_degradesQuietly() = runTest {
        recordRepository.stored.value = listOf(
            testRecord(id = "r1", rawText = "断联陪伴"),
            testRecord(id = "r2", rawText = "陪伴 复盘"),
        )
        derivedContentRepository.readFailure = IllegalStateException("派生表被锁")
        searchRepository.resultIds["陪伴"] = listOf("r1", "r2")
        // 让语义路确实参与，才能断言"keywordOnly == false 与信号失败无关"
        semanticHits("r1" to 0.9)

        val outcome = useCase("陪伴", nowMillis = 0L)

        assertEquals("信号拿不到就按没有信号排序", listOf("r1", "r2"), outcome.records.map { it.id })
        assertFalse("辅助信号失败不该被当成语义降级", outcome.keywordOnly)
    }

    // ==== 两段式检索（2026-10-24 搜索卡顿整改）====

    @Test
    fun SearchStaged_emitsKeywordFirst_thenFusedWithoutRequerying() = runTest {
        val gate = CompletableDeferred<Unit>()
        embedder.gate = gate
        searchRepository.resultIds["陪伴"] = listOf("r1")
        semanticHits("r2" to 0.9)

        val emissions = mutableListOf<SearchOutcome>()
        // UNDISPATCHED：collect 一路跑到第一个真实挂起点（闸门），
        // 第一段发射的时序由此变成确定性的，不依赖调度器排队细节
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            useCase.searchStaged("陪伴").collect { emissions += it }
        }

        // 第一段：关键词已到、语义还在路上 —— 不带降级原因（没失败，只是没到）
        assertEquals(1, emissions.size)
        assertEquals(listOf("r1"), emissions[0].records.map { it.id })
        assertTrue(emissions[0].keywordOnly)
        assertNull(emissions[0].degradeReason)
        assertEquals("第二段的向量化已发起但被闸门挂起", 1, embedder.calls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        // 第二段：语义命中 r2 与关键词命中 r1 融合；FTS/LIKE 只查过一次（第二段复用命中）
        assertEquals(2, emissions.size)
        assertEquals(setOf("r1", "r2"), emissions[1].records.map { it.id }.toSet())
        assertFalse(emissions[1].keywordOnly)
        assertEquals("两段共享一次关键词查询，代价不能翻倍", 1, searchRepository.calls.size)
    }

    @Test
    fun SearchStaged_cloudPathBlocked_finalEmissionDegradesWithoutNetwork() = runTest {
        aiSettings.cloudEnabled = false
        searchRepository.resultIds["陪伴"] = listOf("r1")

        val emissions = useCase.searchStaged("陪伴").toList()

        assertEquals(2, emissions.size)
        assertEquals("第一段不受云端闸影响：它本来就是纯本地的", listOf("r1"), emissions[0].records.map { it.id })
        assertTrue("闸拦下时终态如实标注仅关键词", emissions[1].keywordOnly)
        assertNotNull("降级原因要能进日志排障", emissions[1].degradeReason)
        assertTrue("云端通路不通时不该发起向量化", embedder.calls.isEmpty())
    }

    @Test
    fun SearchStaged_blankQuery_emitsNothing() = runTest {
        val emissions = useCase.searchStaged("   ").toList()

        assertTrue("空查询语义是不搜索，两段都不发射", emissions.isEmpty())
    }
}
