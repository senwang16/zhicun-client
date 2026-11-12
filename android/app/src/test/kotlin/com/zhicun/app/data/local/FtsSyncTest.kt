package com.zhicun.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
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
 * 全文检索虚表与同步触发器测试（M2 验收：FTS 表包含 raw_text / source_url / source_title）。
 *
 * 检索词统一用 ASCII，避免中文分词（unicode61）带来的不确定性；
 * 中文检索质量属于 M10 的验收范畴。
 */
@RunWith(RobolectricTestRunner::class)
class FtsSyncTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun recordEntity(
        id: String = "rec-1",
        rawText: String? = "kotlin coroutines notes",
        sourceUrl: String? = "https://example.com/kotlin",
        sourceTitle: String? = "Kotlin Guide",
    ) = RecordEntity(
        id = id,
        type = RecordType.SHARED_TEXT,
        captureSource = CaptureSource.SYSTEM_SHARE,
        rawText = rawText,
        sourceUrl = sourceUrl,
        sourceTitle = sourceTitle,
        sourceApp = "Chrome",
        createdAt = 1L,
        updatedAt = 1L,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.PENDING,
        sensitivityStatus = SensitivityStatus.UNKNOWN,
        recordRevision = 1,
        isDeleted = false,
    )

    private fun search(term: String): List<String> = runBlocking {
        database.recordFtsDao().searchRecordIds(
            SimpleSQLiteQuery(
                "SELECT record_id FROM ${FtsSyncCallback.TABLE} WHERE ${FtsSyncCallback.TABLE} MATCH ?",
                arrayOf(term),
            ),
        )
    }

    /**
     * 后端固定为 FTS4：Android 系统 SQLite 未编译 FTS5（AOSP Android.bp 只有 FTS3/FTS4），
     * 详见 FtsSyncCallback 类注释。此处锁死"确实是虚拟表且后端是 fts4"，
     * 防止有人照抄架构文档改回 fts5 而让检索静默失效。
     */
    @Test
    fun Fts_createdAsFts4VirtualTable() {
        val createSql = database.openHelper.readableDatabase.let { db ->
            db.query(
                "SELECT sql FROM sqlite_master WHERE name = ?",
                arrayOf(FtsSyncCallback.TABLE),
            ).use { cursor ->
                // SQLite 把虚拟表在 sqlite_master 里记作 type='table'，
                // 是否是虚拟表只能看 DDL 是否以 CREATE VIRTUAL TABLE 开头。
                assertTrue("FTS 虚表应已创建", cursor.moveToFirst())
                cursor.getString(0)
            }
        }

        assertTrue("应为虚拟表，实际 DDL: $createSql", createSql.contains("VIRTUAL TABLE", ignoreCase = true))
        assertTrue("应使用 fts4（Android 无 FTS5），实际 DDL: $createSql", createSql.contains("fts4"))
    }

    @Test
    fun Fts_tableContainsIndexedColumns() {
        val columns = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("PRAGMA table_info(${FtsSyncCallback.TABLE})")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
            }

        assertTrue("缺少 raw_text 列: $columns", columns.contains("raw_text"))
        assertTrue("缺少 source_url 列: $columns", columns.contains("source_url"))
        assertTrue("缺少 source_title 列: $columns", columns.contains("source_title"))
    }

    @Test
    fun Fts_insertRecord_indexedByRawText() = runBlocking {
        database.recordDao().insert(recordEntity())

        assertEquals(listOf("rec-1"), search("kotlin"))
    }

    @Test
    fun Fts_insertRecord_indexedBySourceUrlAndTitle() {
        runBlocking { database.recordDao().insert(recordEntity()) }

        assertTrue(search("example").contains("rec-1"))
        assertTrue(search("Guide").contains("rec-1"))
    }

    @Test
    fun Fts_softDeleteRecord_removesFromIndex() = runBlocking {
        database.recordDao().insert(recordEntity())
        assertEquals(listOf("rec-1"), search("kotlin"))

        database.recordDao().softDelete("rec-1", updatedAt = 2L)

        assertTrue("软删除后不应再被检索到", search("kotlin").isEmpty())
    }

    /**
     * 归档退出检索（2026-10-24 用户裁决）。
     *
     * 这一条钉的是**触发器条件**里的 `archived_at IS NULL`：它一旦被删掉，
     * 归档记录会重新出现在搜索结果里，而这个 App 里"归档"的用户预期就是"别再给我看"。
     */
    @Test
    fun Fts_archivedRecord_isNotSearchable() = runBlocking {
        database.recordDao().insert(recordEntity())
        assertEquals(listOf("rec-1"), search("kotlin"))

        database.recordDao().setArchived(listOf("rec-1"), archivedAt = 5L, updatedAt = 6L)

        assertTrue("归档后不应再被检索到", search("kotlin").isEmpty())
    }

    /** 取消归档必须把记录送回索引 —— 归档是可逆的，不能只做半程。 */
    @Test
    fun Fts_unarchive_reindexes() = runBlocking {
        database.recordDao().insert(recordEntity())
        database.recordDao().setArchived(listOf("rec-1"), archivedAt = 5L, updatedAt = 6L)
        assertTrue(search("kotlin").isEmpty())

        database.recordDao().setArchived(listOf("rec-1"), archivedAt = null, updatedAt = 7L)

        assertEquals("取消归档后应恢复可检索", listOf("rec-1"), search("kotlin"))
    }

    /**
     * 升级对账：老库里"升级前就已归档"的记录当初进了索引，触发器重建不会回头改它们。
     *
     * 这里手工往索引里塞一行来模拟那个状态 —— 不塞的话，新建测试库的触发器已经是新版，
     * 这个漏洞永远不会被覆盖到（这正是它能在真实升级路径上漏过去的原因）。
     */
    @Test
    fun Fts_reconcile_removesArchivedRowsLeftInLegacyIndex() = runBlocking {
        database.recordDao().insert(recordEntity())
        database.recordDao().setArchived(listOf("rec-1"), archivedAt = 5L, updatedAt = 6L)
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO ${FtsSyncCallback.TABLE}(record_id, raw_text, source_url, source_title) " +
                "VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("rec-1", "kotlin coroutines notes", null, null),
        )
        assertEquals("先造出旧版遗留状态", listOf("rec-1"), search("kotlin"))

        FtsSyncCallback.ensureSchema(db)

        assertTrue("对账应把不该在索引里的归档记录移出", search("kotlin").isEmpty())
    }

    /** 对账的另一半：索引缺行（虚表被重建过、写入曾失败）时要能补回来。 */
    @Test
    fun Fts_reconcile_restoresMissingRows() = runBlocking {
        database.recordDao().insert(recordEntity())
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "DELETE FROM ${FtsSyncCallback.TABLE} WHERE record_id = ?",
            arrayOf<Any?>("rec-1"),
        )
        assertTrue(search("kotlin").isEmpty())

        FtsSyncCallback.ensureSchema(db)

        assertEquals("对账应补回缺失的索引行", listOf("rec-1"), search("kotlin"))
    }

    @Test
    fun Fts_updateRawText_reindexesWithoutStaleTerms() = runBlocking {
        database.recordDao().insert(recordEntity())

        database.recordDao().update(
            recordEntity(rawText = "android compose 笔记", sourceTitle = "Compose Guide"),
        )

        assertTrue("新词应可检索", search("compose").contains("rec-1"))
        assertTrue("旧词应已从索引移除", search("coroutines").isEmpty())
    }

    /**
     * 2026-10-24 全项目审查 M-1：更新触发器必须限定 `UPDATE OF` 与"值真的变了"，
     * 否则每次写 `processing_status` / `updated_at` 都会重建整行索引。
     *
     * 用"先清空索引再看它是否被填回来"来判定：如果触发器仍然对任意 UPDATE 生效，
     * 索引会被重建回 1 行；正确实现下它应该保持为空。
     */
    @Test
    fun Fts_statusOrFavoriteUpdate_doesNotRebuildIndex() = runBlocking {
        database.recordDao().insert(recordEntity())
        assertEquals(listOf("rec-1"), search("kotlin"))

        val writeDb = database.openHelper.writableDatabase
        writeDb.execSQL("DELETE FROM ${FtsSyncCallback.TABLE} WHERE record_id = 'rec-1'")

        database.recordDao().updateProcessingStatus("rec-1", ProcessingStatus.DONE, updatedAt = 2L)
        database.recordDao().setFavorite("rec-1", favorite = true, updatedAt = 3L)

        assertTrue(
            "只改状态/收藏不应重建 FTS 索引（否则每次任务状态聚合都重新分词整篇正文）",
            search("kotlin").isEmpty(),
        )
    }

    /**
     * 2026-10-24 全项目审查 M-2：虚表与触发器只在 `onCreate` 建，导致
     * 迁移来的库 / 从备份恢复的库没有索引，检索静默返回空。
     *
     * 这里用文件库模拟"打开时索引缺失"，断言重开之后既有记录已被回填。
     */
    @Test
    fun Fts_missingIndex_isRebuiltAndBackfilledOnOpen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "fts-rebuild-test.db"
        context.deleteDatabase(name)

        var db = AppDatabaseFactory.create(context, name)
        runBlocking { db.recordDao().insert(recordEntity()) }
        db.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS trg_records_fts_ai")
            execSQL("DROP TRIGGER IF EXISTS trg_records_fts_au")
            execSQL("DROP TRIGGER IF EXISTS trg_records_fts_ad")
            execSQL("DROP TABLE IF EXISTS ${FtsSyncCallback.TABLE}")
        }
        db.close()

        db = AppDatabaseFactory.create(context, name)
        try {
            val hits = runBlocking {
                db.recordFtsDao().searchRecordIds(
                    SimpleSQLiteQuery(
                        "SELECT record_id FROM ${FtsSyncCallback.TABLE} " +
                            "WHERE ${FtsSyncCallback.TABLE} MATCH ?",
                        arrayOf("kotlin"),
                    ),
                )
            }
            assertEquals("重开时应补建索引并回填既有记录", listOf("rec-1"), hits)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
