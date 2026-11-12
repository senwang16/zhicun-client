package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.domain.clipboard.ClipboardText
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * RecordRepository 的 Room in-memory 集成测试（M2 验收要求：验证插入、查询、软删除）。
 *
 * 用 Robolectric 在 JVM 上运行真实 SQLite，不需要设备/模拟器；
 * 数据库一律经 [AppDatabaseFactory.createInMemory]，与生产共用同一套 FTS 回调。
 */
@RunWith(RobolectricTestRunner::class)
class RecordRepositoryImplTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RecordRepositoryImpl

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        id: String = "rec-1",
        rawText: String? = "kotlin coroutines 笔记",
        createdAt: Long = 1_000L,
        isDeleted: Boolean = false,
        captureSource: CaptureSource = CaptureSource.SYSTEM_SHARE,
    ) = Record(
        id = id,
        type = RecordType.SHARED_TEXT,
        captureSource = captureSource,
        rawText = rawText,
        sourceUrl = "https://example.com/a",
        sourceTitle = "示例标题",
        sourceApp = "Chrome",
        createdAt = createdAt,
        updatedAt = createdAt,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.PENDING,
        sensitivityStatus = SensitivityStatus.UNKNOWN,
        recordRevision = 1,
        isDeleted = isDeleted,
    )

    @Test
    fun RecordRepository_saveThenGetById_returnsSameRecord() = runBlocking {
        val saved = record()
        repository.save(saved)

        assertEquals(saved, repository.getById("rec-1"))
    }

    @Test
    fun RecordRepository_getByIdUnknownId_returnsNull() = runBlocking {
        assertNull(repository.getById("not-exist"))
    }

    @Test
    fun RecordRepository_observeAll_returnsSavedRecords() = runBlocking {
        repository.save(record(id = "rec-1", createdAt = 1_000L))
        repository.save(record(id = "rec-2", createdAt = 2_000L))

        val records = repository.observeAll().first()

        assertEquals(2, records.size)
        assertEquals(setOf("rec-1", "rec-2"), records.map { it.id }.toSet())
    }

    @Test
    fun RecordRepository_observeAll_ordersByCreatedAtDesc() = runBlocking {
        repository.save(record(id = "old", createdAt = 1_000L))
        repository.save(record(id = "new", createdAt = 5_000L))

        val records = repository.observeAll().first()

        assertEquals(listOf("new", "old"), records.map { it.id })
    }

    @Test
    fun RecordRepository_softDelete_excludesFromListButKeepsRow() = runBlocking {
        repository.save(record(id = "rec-1"))

        repository.softDelete("rec-1", updatedAt = 9_000L)

        assertTrue(repository.observeAll().first().isEmpty())
        val stillThere = repository.getById("rec-1")
        assertNotNull(stillThere)
        assertEquals(true, stillThere!!.isDeleted)
        assertEquals(9_000L, stillThere.updatedAt)
    }

    @Test
    fun RecordRepository_updateProcessingStatus_persistsStatus() = runBlocking {
        repository.save(record(id = "rec-1"))

        repository.updateProcessingStatus("rec-1", ProcessingStatus.DONE, updatedAt = 7_000L)

        val updated = repository.getById("rec-1")
        assertEquals(ProcessingStatus.DONE, updated?.processingStatus)
        assertEquals(7_000L, updated?.updatedAt)
    }

    @Test
    fun RecordRepository_update_replacesExistingFields() = runBlocking {
        repository.save(record(id = "rec-1"))

        repository.update(record(id = "rec-1", rawText = "改过的正文", createdAt = 1_000L))

        assertEquals("改过的正文", repository.getById("rec-1")?.rawText)
    }

    // ------------------------------------------------------------ 整理（M16）

    /** 置顶优先、组内按时间倒序 —— 这条排序是"置顶"功能的全部价值所在。 */
    @Test
    fun ObserveInbox_putsPinnedFirstThenNewestFirst() = runBlocking {
        repository.save(record(id = "old", createdAt = 1_000L))
        repository.save(record(id = "new", createdAt = 3_000L))
        repository.save(record(id = "pinned", createdAt = 2_000L))
        repository.setPinned("pinned", pinned = true, updatedAt = 2_000L)

        val ids = repository.observeInbox().first().map { it.id }

        assertEquals(listOf("pinned", "new", "old"), ids)
    }

    @Test
    fun ObserveInbox_excludesArchivedButObserveAllKeepsIt() = runBlocking {
        repository.save(record(id = "live", createdAt = 1_000L))
        repository.save(record(id = "archived", createdAt = 2_000L))

        repository.setArchived(listOf("archived"), archivedAt = 9_000L, updatedAt = 9_000L)

        assertEquals(listOf("live"), repository.observeInbox().first().map { it.id })
        assertEquals(
            "归档不是删除：导出（M15）走的 observeAll 必须仍然能看到它",
            setOf("live", "archived"),
            repository.observeAll().first().map { it.id }.toSet(),
        )
        assertEquals(listOf("archived"), repository.observeArchived().first().map { it.id })
    }

    @Test
    fun ObserveInbox_excludesDeletedRecords() = runBlocking {
        repository.save(record(id = "live", createdAt = 1_000L))
        repository.save(record(id = "gone", createdAt = 2_000L, isDeleted = true))

        assertEquals(listOf("live"), repository.observeInbox().first().map { it.id })
    }

    @Test
    fun SetFavorite_persistsFlagAndTouchesUpdatedAtOnly() = runBlocking {
        repository.save(record(id = "rec-1"))

        val changed = repository.setFavorite("rec-1", favorite = true, updatedAt = 8_000L)

        assertTrue(changed)
        val updated = repository.getById("rec-1")!!
        assertTrue(updated.isFavorite)
        assertEquals(8_000L, updated.updatedAt)
        assertEquals("收藏不能改内容", "kotlin coroutines 笔记", updated.rawText)
        assertEquals("收藏不能让派生结果过期", 1, updated.recordRevision)
    }

    @Test
    fun SetPinned_canBeToggledBack() = runBlocking {
        repository.save(record(id = "rec-1"))

        repository.setPinned("rec-1", pinned = true, updatedAt = 8_000L)
        assertTrue(repository.getById("rec-1")!!.isPinned)

        repository.setPinned("rec-1", pinned = false, updatedAt = 9_000L)

        assertEquals(false, repository.getById("rec-1")!!.isPinned)
    }

    /** 已删除的记录不该被整理动作改动（它随时会被 M15 清理掉）。 */
    @Test
    fun SetFavorite_deletedRecord_returnsFalseAndChangesNothing() = runBlocking {
        repository.save(record(id = "gone", isDeleted = true))

        val changed = repository.setFavorite("gone", favorite = true, updatedAt = 8_000L)

        assertEquals(false, changed)
        assertEquals(false, repository.getById("gone")!!.isFavorite)
    }

    @Test
    fun SetFavorite_unknownId_returnsFalse() = runBlocking {
        assertEquals(false, repository.setFavorite("ghost", favorite = true, updatedAt = 8_000L))
    }

    @Test
    fun SetArchived_batchReturnsAffectedCountAndSkipsDeleted() = runBlocking {
        repository.save(record(id = "a", createdAt = 1_000L))
        repository.save(record(id = "b", createdAt = 2_000L))
        repository.save(record(id = "deleted", createdAt = 3_000L, isDeleted = true))

        val affected = repository.setArchived(
            ids = listOf("a", "b", "deleted"),
            archivedAt = 5_000L,
            updatedAt = 5_000L,
        )

        assertEquals("已删除的记录不该被算进'已归档'", 2, affected)
        assertEquals(5_000L, repository.getById("a")!!.archivedAt)
        assertNull(repository.getById("deleted")!!.archivedAt)
    }

    @Test
    fun SetArchived_emptyList_doesNotTouchDatabase() = runBlocking {
        repository.save(record(id = "a"))

        assertEquals(0, repository.setArchived(emptyList(), archivedAt = 5_000L, updatedAt = 5_000L))
        assertNull(repository.getById("a")!!.archivedAt)
    }

    @Test
    fun SetArchived_nullRestoresRecordToInbox() = runBlocking {
        repository.save(record(id = "a", createdAt = 1_000L))
        repository.setArchived(listOf("a"), archivedAt = 5_000L, updatedAt = 5_000L)

        repository.setArchived(listOf("a"), archivedAt = null, updatedAt = 6_000L)

        assertNull(repository.getById("a")!!.archivedAt)
        assertEquals(listOf("a"), repository.observeInbox().first().map { it.id })
    }

    // ------------------------------------------------- 归档箱内搜索（2026-10-24）

    /**
     * 归档箱搜索只命中归档记录：收件箱的记录**不**出现在结果里。
     *
     * 这与主搜索的过滤方向相反（主搜索排除归档），所以必须是独立查询 ——
     * 复用同一个方法加参数迟早把方向传反。
     */
    @Test
    fun RecordRepository_searchArchivedBySubstring_hitsOnlyArchivedRecords() = runBlocking {
        repository.save(record(id = "inbox", rawText = "断联陪伴笔记", createdAt = 1_000L))
        repository.save(record(id = "archived", rawText = "归档的断联陪伴笔记", createdAt = 2_000L))
        repository.setArchived(listOf("archived"), archivedAt = 9_000L, updatedAt = 9_000L)

        val hits = repository.searchArchivedBySubstring("断联", limit = 50).first()

        assertEquals(listOf("archived"), hits.map { it.id })
    }

    /**
     * 录音归档记录没有正文，能搜到它靠的是转写（派生内容）。
     *
     * 这条测试是归档箱搜索必须走 SQL 而不是内存过滤的依据：
     * `Record` 对象上没有派生内容字段，内存过滤对这类记录永远零命中。
     */
    @Test
    fun RecordRepository_searchArchivedBySubstring_matchesDerivedTranscription() = runBlocking {
        repository.save(record(id = "voice", rawText = null, createdAt = 1_000L))
        repository.setArchived(listOf("voice"), archivedAt = 9_000L, updatedAt = 9_000L)
        database.derivedContentDao().upsert(
            DerivedContentEntity(
                id = "d1",
                recordId = "voice",
                contentType = DerivedType.TRANSCRIPTION,
                content = "这周和医生的复诊改到周四上午",
                processorVersion = "test",
                processedAt = 1L,
                errorMessage = null,
            ),
        )

        val hits = repository.searchArchivedBySubstring("复诊", limit = 50).first()

        assertEquals(listOf("voice"), hits.map { it.id })
    }

    /** 已删除的归档记录不再出现；空查询返回空（"不搜索"，绝不是"列出全部"）。 */
    @Test
    fun RecordRepository_searchArchivedBySubstring_excludesDeletedAndBlankQuery() = runBlocking {
        repository.save(record(id = "gone", rawText = "会被删掉的"))
        repository.setArchived(listOf("gone"), archivedAt = 9_000L, updatedAt = 9_000L)
        repository.softDelete("gone", updatedAt = 10_000L)

        assertTrue(repository.searchArchivedBySubstring("会", limit = 50).first().isEmpty())
        assertTrue(repository.searchArchivedBySubstring("", limit = 50).first().isEmpty())
    }

    // ------------------------------------ 剪贴板去重（2026-10-24，2026-10-27 跨来源）

    @Test
    fun FindDuplicate_sameText_returnsExistingId() = runBlocking {
        repository.save(
            record(id = "clip-1", rawText = "重复的一段", captureSource = CaptureSource.CLIPBOARD),
        )

        assertEquals("clip-1", repository.findDuplicate("重复的一段"))
    }

    /** PRD v1.2 §2.4：与**已有记录**比对，不再限制来源。 */
    @Test
    fun FindDuplicate_differentSource_stillReturnsId() = runBlocking {
        repository.save(
            record(id = "manual-1", rawText = "同一段", captureSource = CaptureSource.MANUAL),
        )

        assertEquals(
            "别的入口存过的同文本同样是重复",
            "manual-1",
            repository.findDuplicate("同一段"),
        )
    }

    @Test
    fun FindDuplicate_deletedRecord_returnsNull() = runBlocking {
        repository.save(
            record(id = "clip-1", rawText = "删掉再存", captureSource = CaptureSource.CLIPBOARD),
        )
        repository.softDelete("clip-1", updatedAt = 9_000L)

        assertNull(
            "已删除的记录不算已存在，用户应当能重新收藏",
            repository.findDuplicate("删掉再存"),
        )
    }

    @Test
    fun FindDuplicate_prefixOnly_returnsNull() = runBlocking {
        repository.save(
            record(id = "clip-1", rawText = "会议纪要", captureSource = CaptureSource.CLIPBOARD),
        )

        assertNull(
            "判据必须是完全相等，前缀相同是两条不同内容",
            repository.findDuplicate("会议纪要（补充）"),
        )
    }

    @Test
    fun FindDuplicate_multipleMatches_returnsLatest() = runBlocking {
        repository.save(
            record(id = "old", rawText = "同一段", createdAt = 1_000L, captureSource = CaptureSource.CLIPBOARD),
        )
        repository.save(
            record(id = "new", rawText = "同一段", createdAt = 2_000L, captureSource = CaptureSource.CLIPBOARD),
        )

        assertEquals(
            "多条命中时返回最新的一条，界面提示才对得上用户刚存的那份",
            "new",
            repository.findDuplicate("同一段"),
        )
    }

    /** 换行符统一是允许的标准化：CRLF 与 LF 视为同一段内容。 */
    @Test
    fun FindDuplicate_lineEndingDifference_returnsId() = runBlocking {
        repository.save(
            record(
                id = "clip-1",
                rawText = "第一行\n第二行",
                captureSource = CaptureSource.CLIPBOARD,
            ),
        )

        assertEquals("clip-1", repository.findDuplicate("第一行\r\n第二行"))
    }

    /** 首尾空白不参与比较（PRD v1.2 §2.4 建议的有限标准化）。 */
    @Test
    fun FindDuplicate_surroundingWhitespace_returnsId() = runBlocking {
        repository.save(
            record(id = "clip-1", rawText = "  一段内容  ", captureSource = CaptureSource.CLIPBOARD),
        )

        assertEquals("clip-1", repository.findDuplicate("一段内容"))
    }

    /** 中文输入法常见的全角空格（U+3000）同样属于首尾空白。 */
    @Test
    fun FindDuplicate_ideographicSpaceAround_returnsId() = runBlocking {
        repository.save(
            record(id = "clip-1", rawText = "\u3000一段内容\u3000", captureSource = CaptureSource.CLIPBOARD),
        )

        assertEquals("clip-1", repository.findDuplicate("一段内容"))
    }

    /**
     * 判重键必须与**落库时的归一化**对齐（2026-10-28 复审补充）。
     *
     * 真实链路是：落库用 [ClipboardText.normalize]、比对用 [ClipboardText.dedupKey]。
     * 两者去掉的空白集合不一致时，U+2000 这类 Kotlin 独有空白会从落库文本里消失、
     * 却保留在查询键里，第二次复制同一内容就判不出重复。
     */
    @Test
    fun FindDuplicate_clipboardRoundTrip_withKotlinOnlyWhitespace_returnsId() = runBlocking {
        val raw = "\u2000一段内容\u2000"
        val stored = requireNotNull(ClipboardText.normalize(raw))
        repository.save(
            record(id = "clip-1", rawText = stored, captureSource = CaptureSource.CLIPBOARD),
        )

        assertEquals("clip-1", repository.findDuplicate(raw))
    }

    /** URL 参数不能被删掉后再比较：参数不同就是不同内容。 */
    @Test
    fun FindDuplicate_urlDiffersByParams_returnsNull() = runBlocking {
        repository.save(
            record(
                id = "link-1",
                rawText = "https://example.com/item?id=1",
                captureSource = CaptureSource.CLIPBOARD,
            ),
        )

        assertNull(repository.findDuplicate("https://example.com/item?id=2"))
    }

    /** 内部空格也不能压缩：`会议 纪要` 与 `会议  纪要` 是两条不同内容。 */
    @Test
    fun FindDuplicate_internalSpacesDiffer_returnsNull() = runBlocking {
        repository.save(
            record(id = "space-1", rawText = "会议 纪要", captureSource = CaptureSource.CLIPBOARD),
        )

        assertNull(repository.findDuplicate("会议  纪要"))
    }

    // ------------------------------------------------ 批量收藏（2026-10-24 整理模式）

    @Test
    fun SetFavorite_batchMarksOnlyGivenIdsAndSkipsDeleted() = runBlocking {
        repository.save(record(id = "a", createdAt = 1_000L))
        repository.save(record(id = "b", createdAt = 2_000L))
        repository.save(record(id = "c", createdAt = 3_000L))
        repository.save(record(id = "gone", createdAt = 4_000L, isDeleted = true))

        val affected = repository.setFavorite(
            ids = listOf("a", "b", "gone"),
            favorite = true,
            updatedAt = 5_000L,
        )

        assertEquals("已删除的记录不该被算进'已收藏'", 2, affected)
        assertTrue(repository.getById("a")!!.isFavorite)
        assertTrue(repository.getById("b")!!.isFavorite)
        assertFalse("没传进去的记录不能被改", repository.getById("c")!!.isFavorite)
        assertFalse(repository.getById("gone")!!.isFavorite)
    }

    @Test
    fun SetFavorite_batchEmptyList_doesNotTouchDatabase() = runBlocking {
        repository.save(record(id = "a"))

        assertEquals(
            0,
            repository.setFavorite(emptyList(), favorite = true, updatedAt = 5_000L),
        )
        assertFalse(repository.getById("a")!!.isFavorite)
    }

    /**
     * 收藏不能递增 `record_revision`。
     *
     * 那一列是"AI 派生结果是否过期"的判据（PRD §4.3）：收藏只是"用户怎么看这条记录"，
     * 动了它会让已经生成好的摘要/向量被判为过期，白白重跑一遍云端任务。
     */
    @Test
    fun SetFavorite_batchDoesNotTouchRevision() = runBlocking {
        repository.save(record(id = "a"))
        val before = repository.getById("a")!!.recordRevision

        repository.setFavorite(listOf("a"), favorite = true, updatedAt = 5_000L)

        assertEquals(before, repository.getById("a")!!.recordRevision)
    }
}
