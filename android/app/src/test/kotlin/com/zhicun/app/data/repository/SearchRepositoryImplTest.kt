package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.search.SearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 检索仓储（M10）—— 跑真实 SQLite（Robolectric 内存库），不打桩。
 *
 * 这一层是**唯一**能验证"FTS4 语法真的成立、LIKE + EXISTS 真的命中"的地方：
 * 用假 DAO 测只能证明"我调用了这个方法"，测不出 SQL 写错（比如 ESCAPE 漏了、
 * MATCH 表达式带了非法字符）。中文检索是 M10 的核心诉求，必须在这里钉死。
 */
@RunWith(RobolectricTestRunner::class)
class SearchRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: SearchRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = SearchRepositoryImpl(
            recordFtsDao = database.recordFtsDao(),
            recordDao = database.recordDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun recordEntity(
        id: String,
        type: RecordType = RecordType.SHARED_TEXT,
        rawText: String? = null,
        sourceTitle: String? = null,
        sourceUrl: String? = null,
        createdAt: Long = 1L,
        isDeleted: Boolean = false,
    ) = RecordEntity(
        id = id,
        type = type,
        captureSource = CaptureSource.SYSTEM_SHARE,
        rawText = rawText,
        sourceUrl = sourceUrl,
        sourceTitle = sourceTitle,
        sourceApp = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.PENDING,
        sensitivityStatus = SensitivityStatus.UNKNOWN,
        recordRevision = 1,
        isDeleted = isDeleted,
    )

    private fun derived(recordId: String, type: DerivedType, content: String) =
        DerivedContentEntity(
            id = "$recordId:$type",
            recordId = recordId,
            contentType = type,
            content = content,
            processorVersion = "test/1.0",
            processedAt = 1L,
            errorMessage = null,
        )

    private fun search(raw: String, limit: Int = 20): List<String> {
        val normalized = SearchQuery.normalize(raw) ?: return emptyList()
        return runBlocking { repository.searchIds(normalized, limit) }
    }

    @Test
    fun Chinese_substringMatch_insideLongerText() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "断联陪伴 App 的产品定位笔记"))
        database.recordDao().insert(recordEntity(id = "r2", rawText = "别的内容"))

        // FTS4 的 simple 分词把整段汉字当一个 token，"陪伴"必须靠子串匹配命中
        assertEquals(listOf("r1"), search("陪伴"))
    }

    @Test
    fun Chinese_matchesSourceTitleAndUrl() = runBlocking {
        database.recordDao().insert(
            recordEntity(id = "r1", sourceTitle = "商汤 SenseNova 定价", sourceUrl = "https://example.com/pricing"),
        )

        assertEquals(listOf("r1"), search("定价"))
        assertEquals(listOf("r1"), search("pricing"))
    }

    @Test
    fun DerivedContent_matchedThroughExists() = runBlocking {
        // 图片记录原文为空，内容全在 OCR 派生里 —— M10 验收要求"搜 OCR 文字能命中图片记录"
        database.recordDao().insert(recordEntity(id = "img-1", rawText = null))
        database.derivedContentDao().upsert(
            derived("img-1", DerivedType.OCR_TEXT, "报错：java.lang.NullPointerException"),
        )
        database.recordDao().insert(recordEntity(id = "note-1", rawText = "无关记录"))

        assertEquals(listOf("img-1"), search("NullPointerException"))
    }

    @Test
    fun LatinWord_matchedByFtsPrefix() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "kotlin coroutines 的取消语义"))

        // 前缀匹配：输入 "corout" 应命中 "coroutines"
        assertEquals(listOf("r1"), search("corout"))
    }

    /**
     * 合集可被检索（M20 验收）。
     *
     * 合集是一普通记录：`raw_text` 是用户写的说明，真正的信息在 `SUMMARY` 派生内容里。
     * 两处都必须命中 —— 只搜说明会让"合并后的内容搜不到"，只搜摘要会让用户按自己写的
     * 标题找不到它。语义检索走同一套 `recordId` 索引（EMBEDDING 由 EmbedWorker 写入），
     * 不因记录类型区分，故这里验证关键词路即可。
     */
    @Test
    fun Collection_descriptionAndSummary_areBothSearchable() = runBlocking {
        database.recordDao().insert(
            recordEntity(id = "c1", type = RecordType.COLLECTION, rawText = "十月读的东西"),
        )
        database.derivedContentDao().upsert(
            derived("c1", DerivedType.SUMMARY, "关于离线优先架构与本地检索的几条笔记"),
        )
        database.recordDao().insert(recordEntity(id = "other", rawText = "无关记录"))

        assertEquals("说明由 raw_text 命中", listOf("c1"), search("十月读"))
        assertEquals("摘要由派生内容命中", listOf("c1"), search("离线优先"))
    }

    @Test
    fun TagName_matchedThroughCrossRef() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "一篇没有关键词的读书笔记"))
        database.tagDao().insert(
            TagEntity(id = "tag-1", name = "产品设计", source = TagSource.AI, confidence = 0.8, createdAt = 1L),
        )
        database.tagDao().attachCrossRef(RecordTagCrossRef(recordId = "r1", tagId = "tag-1"))

        assertEquals(listOf("r1"), search("产品设计"))
    }

    @Test
    fun MultipleDerivedRows_doNotDuplicateRecord() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "断联陪伴"))
        database.derivedContentDao().upsert(derived("r1", DerivedType.OCR_TEXT, "断联陪伴 截图"))
        database.derivedContentDao().upsert(derived("r1", DerivedType.TRANSCRIPTION, "断联陪伴 语音"))

        // EXISTS 子查询而不是 JOIN：命中几条派生内容都只该出现一次
        assertEquals(listOf("r1"), search("断联陪伴"))
    }

    @Test
    fun SoftDeletedRecords_excluded() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "断联陪伴"))
        database.recordDao().softDelete("r1", updatedAt = 2L)

        assertTrue("已删除记录不应出现在结果里", search("陪伴").isEmpty())
    }

    @Test
    fun DeletedAfterFtsHit_excludedBySecondPass() = runBlocking {
        // FTS 索引与 records 由触发器同步，但检索是两段式（先 id 再取实体），
        // 这里验证最终结果仍受 is_deleted 约束
        database.recordDao().insert(recordEntity(id = "r1", rawText = "kotlin notes"))
        database.recordDao().softDelete("r1", updatedAt = 2L)

        assertTrue(search("kotlin").isEmpty())
    }

    @Test
    fun Results_areOrderedNewestFirst() = runBlocking {
        database.recordDao().insert(recordEntity(id = "old", rawText = "陪伴 计划", createdAt = 1L))
        database.recordDao().insert(recordEntity(id = "new", rawText = "陪伴 复盘", createdAt = 2L))

        assertEquals(listOf("new", "old"), search("陪伴"))
    }

    @Test
    fun Limit_isRespected() = runBlocking {
        repeat(5) { index ->
            database.recordDao().insert(
                recordEntity(id = "r$index", rawText = "陪伴 第 $index 条", createdAt = index.toLong()),
            )
        }

        assertEquals(2, search("陪伴", limit = 2).size)
    }

    @Test
    fun BlankQuery_returnsEmpty() {
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun LikeWildcardsAreEscaped() = runBlocking {
        database.recordDao().insert(recordEntity(id = "r1", rawText = "discount 100% off"))
        database.recordDao().insert(recordEntity(id = "r2", rawText = "discount 100 items"))

        // 若 % 未转义，"100%" 会退化成"以 100 开头"，r2 也会被命中
        assertEquals(listOf("r1"), search("100%"))
    }
}
