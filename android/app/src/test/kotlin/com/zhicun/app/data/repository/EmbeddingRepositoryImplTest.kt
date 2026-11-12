package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.search.VectorMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 向量仓储（M12）—— 真实 SQLite。
 *
 * 钉住四件事：Base64 往返不失真、`recordId:EMBEDDING` 主键天然幂等、
 * 模型不同不混读、软删除与坏行被排除。
 */
@RunWith(RobolectricTestRunner::class)
class EmbeddingRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: EmbeddingRepositoryImpl

    private val model = "test-model"

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = EmbeddingRepositoryImpl(
            derivedContentDao = database.derivedContentDao(),
            embeddingMetaDao = database.embeddingMetaDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seedRecord(
        id: String,
        isDeleted: Boolean = false,
        archivedAt: Long? = null,
    ) = runBlocking {
        database.recordDao().insert(
            RecordEntity(
                id = id,
                type = RecordType.TEXT_NOTE,
                captureSource = CaptureSource.MANUAL,
                rawText = "内容",
                sourceUrl = null,
                sourceTitle = null,
                sourceApp = null,
                createdAt = 1L,
                updatedAt = 1L,
                localStatus = LocalStatus.SAVED,
                processingStatus = ProcessingStatus.PENDING,
                sensitivityStatus = SensitivityStatus.CLEAR,
                recordRevision = 1,
                isDeleted = isDeleted,
                archivedAt = archivedAt,
            ),
        )
    }

    @Test
    fun Upsert_thenGetAll_returnsDecodedVector() = runBlocking {
        seedRecord("r1")
        val vector = floatArrayOf(0.25f, -0.5f, 0.75f)

        repository.upsert("r1", Embedding(model, vector), now = 10L)

        val stored = repository.getAll(model)
        assertEquals(1, stored.size)
        assertEquals("r1", stored.single().recordId)
        assertArrayEquals(vector, stored.single().vector, 0f)
    }

    @Test
    fun UpsertTwice_replacesSameRow() = runBlocking {
        seedRecord("r1")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f, 0f)), now = 10L)
        repository.upsert("r1", Embedding(model, floatArrayOf(0f, 1f)), now = 20L)

        val stored = repository.getAll(model)

        assertEquals("主键固定为 recordId:EMBEDDING，不该堆出两行", 1, stored.size)
        assertArrayEquals(floatArrayOf(0f, 1f), stored.single().vector, 0f)
        // 派生内容表上也只该有一行
        assertEquals(1, database.derivedContentDao().getByRecordId("r1").size)
    }

    @Test
    fun GetAll_otherModel_isExcluded() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f, 0f)), now = 1L)
        repository.upsert("r2", Embedding("another-model", floatArrayOf(1f, 0f)), now = 1L)

        val stored = repository.getAll(model)

        assertEquals("换模型后旧向量不能再参与比较", listOf("r1"), stored.map { it.recordId })
    }

    @Test
    fun GetAll_softDeletedRecord_isExcluded() = runBlocking {
        seedRecord("r1")
        seedRecord("r2", isDeleted = true)
        repository.upsert("r1", Embedding(model, floatArrayOf(1f)), now = 1L)
        repository.upsert("r2", Embedding(model, floatArrayOf(1f)), now = 1L)

        val stored = repository.getAll(model)

        assertEquals(listOf("r1"), stored.map { it.recordId })
    }

    /**
     * 归档也算"不该出现在检索里"（2026-10-24 裁决）。
     *
     * 与软删除的区别只在于**向量仍然留着**：归档可逆，取消归档后不该再调一次模型。
     * 所以这里断言的是"召回结果不含它"，而不是"向量被删了"。
     */
    @Test
    fun GetAll_archivedRecord_isExcludedButVectorKept() = runBlocking {
        seedRecord("r1")
        seedRecord("r2", archivedAt = 9L)
        repository.upsert("r1", Embedding(model, floatArrayOf(1f)), now = 1L)
        repository.upsert("r2", Embedding(model, floatArrayOf(1f)), now = 1L)

        val stored = repository.getAll(model)

        assertEquals("归档记录不该进入相似召回", listOf("r1"), stored.map { it.recordId })
        assertEquals(
            "向量必须留着：归档可逆，取消归档后不该重算",
            1,
            database.derivedContentDao().getByRecordId("r2").size,
        )
    }

    @Test
    fun Upsert_emptyVector_writesNothing() = runBlocking {
        seedRecord("r1")

        repository.upsert("r1", Embedding(model, FloatArray(0)), now = 1L)

        assertTrue("空向量不该占用一行", repository.getAll(model).isEmpty())
        assertTrue(database.derivedContentDao().getByRecordId("r1").isEmpty())
    }

    @Test
    fun GetAll_corruptedRow_isSkipped() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f, 0f)), now = 1L)
        // 直接写一行坏数据（模拟库被改坏 / 旧格式残留）
        database.derivedContentDao().upsert(
            DerivedContentEntity(
                id = "r2:${DerivedType.EMBEDDING}",
                recordId = "r2",
                contentType = DerivedType.EMBEDDING,
                content = "不是 base64",
                processorVersion = "embed/$model",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val stored = repository.getAll(model)

        assertEquals("一条坏行不该让整个语义检索不可用", listOf("r1"), stored.map { it.recordId })
    }

    @Test
    fun GetAll_ignoresNonEmbeddingDerivedContent() = runBlocking {
        seedRecord("r1")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f)), now = 1L)
        database.derivedContentDao().upsert(
            DerivedContentEntity(
                id = "r1:${DerivedType.SUMMARY}",
                recordId = "r1",
                contentType = DerivedType.SUMMARY,
                content = VectorMath.encode(floatArrayOf(2f)),
                processorVersion = "embed/$model",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val stored = repository.getAll(model)

        assertEquals(1, stored.size)
        assertArrayEquals(floatArrayOf(1f), stored.single().vector, 0f)
    }

    // ------------------------------------------------------------ v10：向量元信息与来源

    @Test
    fun Upsert_writesEmbeddingMetaWithDimensionsAndProvider() = runBlocking {
        seedRecord("r1")
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)

        repository.upsert("r1", Embedding(model, vector), now = 42L, providerKey = "https://api.example.com/v1")

        val meta = database.embeddingMetaDao().getByRecordId("r1")
        assertEquals(model, meta?.embedModel)
        assertEquals(3, meta?.embedDim)
        assertEquals("https://api.example.com/v1", meta?.embedProviderUrl)
        assertEquals(42L, meta?.embeddedAt)
    }

    /**
     * 换供应商（同名模型）后，两批向量不能混算。
     *
     * 这是 v10 新增的核心判据：模型名相同 ≠ 向量空间相同。
     */
    @Test
    fun GetAll_otherProvider_isExcludedUnlessUnknown() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f)), 1L, "https://a.example.com")
        repository.upsert("r2", Embedding(model, floatArrayOf(1f)), 1L, "https://b.example.com")

        val strict = repository.getAll(model, "https://a.example.com")
        assertEquals("来源不同的同名模型不能混算", listOf("r1"), strict.map { it.recordId })

        val wildcard = repository.getAll(model, "")
        assertEquals(
            "不知道当前来源时必须放行，否则升级一次语义检索就清空了",
            listOf("r1", "r2").sorted(),
            wildcard.map { it.recordId }.sorted(),
        )
    }

    @Test
    fun GetAll_legacyRowWithoutMeta_isTreatedAsCompatible() = runBlocking {
        seedRecord("r1")
        // 模拟 v9 及以前落库的行：只有向量本体，没有任何 meta
        database.derivedContentDao().upsert(
            DerivedContentEntity(
                id = "r1:${DerivedType.EMBEDDING}",
                recordId = "r1",
                contentType = DerivedType.EMBEDDING,
                content = VectorMath.encode(floatArrayOf(1f)),
                processorVersion = "embed/$model",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val stored = repository.getAll(model, "https://new.example.com")

        assertEquals("没有身份证的老向量按通配处理，不能被升级清掉", 1, stored.size)
        assertEquals(0, repository.countIncompatible(model, "https://new.example.com"))
    }

    @Test
    fun CountIncompatible_countsModelAndProviderMismatch() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        seedRecord("r3")
        repository.upsert("r1", Embedding(model, floatArrayOf(1f)), 1L, "https://a.example.com")
        repository.upsert("r2", Embedding("another-model", floatArrayOf(1f)), 1L, "https://a.example.com")
        repository.upsert("r3", Embedding(model, floatArrayOf(1f)), 1L, "https://b.example.com")

        assertEquals(2, repository.countIncompatible(model, "https://a.example.com"))

        val ids = repository.findIncompatibleRecordIds(model, "https://a.example.com", limit = 10)
        assertEquals(listOf("r2", "r3").sorted(), ids.sorted())
    }

    @Test
    fun LatestSpace_reflectsMostRecentUpsert() = runBlocking {
        seedRecord("r1")
        seedRecord("r2")
        repository.upsert("r1", Embedding("old-model", floatArrayOf(1f)), 1L, "https://old")
        repository.upsert("r2", Embedding("new-model", floatArrayOf(1f)), 9L, "https://new")

        val latest = repository.latestSpace()

        assertEquals("new-model", latest?.model)
        assertEquals("https://new", latest?.providerKey)
    }
}
