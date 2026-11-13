package com.zhicun.app.data.local.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.CollectionMemberEntity
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.SearchHistoryEntity
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 数据库迁移 1 → 2（M16）。
 *
 * ## 为什么这个测试值得单独存在
 * 迁移**只在"装过旧版本、又升级"的设备上执行**。开发机上每次都装干净包，永远走
 * `onCreate`，所以迁移代码写错也不会有人发现 —— 而写错的代价是用户升级后**打不开 App**
 * （Room 结构校验失败）或**丢数据**。这是全项目唯一一处"本地全绿、真机必炸"的高危改动。
 *
 * ## 怎么造出"旧库"
 * 不用手抄 v1 的 DDL：直接读 Room 导出的 `schemas/1.json` 里的 `createSql` 执行。
 * 手抄的 DDL 一旦和真实 v1 有差别（少个索引、少个默认值），测出来的就不是用户的库。
 *
 * ## 断言分三层
 * 1. **能打开**：Room 在迁移后会逐项校验结构（列类型、默认值、索引名）。打不开就说明
 *    迁移 SQL 与实体不一致 —— 这是最容易错、也最致命的一项；
 * 2. **数据没丢**：迁移前写入的行迁移后还在，且各字段值不变；
 * 3. **新列有合理默认值**：旧记录必须是"未收藏、未置顶、未归档"。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 同一个用例内重复跑时不要受上次残留影响
        context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }.delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /** 找到 Room 导出的 schema 文件；单测工作目录可能是模块根，也可能是仓库根。 */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.zhicun.app.data.local.AppDatabase/$version.json"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 schema 文件：尝试过 ${candidates.joinToString { it.absolutePath }}")
    }

    /**
     * 按指定版本的导出 schema 建库，模拟"用户手机上的旧数据库"。
     *
     * 返回已打开的 [SQLiteDatabase]，由调用方写入种子数据并自行关闭。
     * 拆成"建库"与"塞数据"两步，是因为 1→2 与 3→4 需要不同的种子：
     * 前者要一条旧记录，后者要一条"确认过版本号"的记录。
     */
    private fun createSchemaDatabase(version: Int): SQLiteDatabase {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val database = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))

            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                db.execSQL(index.getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        db.version = version
        return db
    }

    /** 按 v1 的导出 schema 建库，模拟"用户手机上的旧数据库"。 */
    private fun createVersion1Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(1).use { db ->
            // 迁移前先放一条"用户在旧版本里存的记录"
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`)
                VALUES
                    ('old-1', 'TEXT_NOTE', 'MANUAL', '旧版本存的正文', NULL, NULL,
                     NULL, 1000, 2000, 'SAVED', 'DONE',
                     'CLEAR', 3, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`)
                VALUES
                    ('old-deleted', 'TEXT_NOTE', 'MANUAL', '已删除的旧记录', NULL, NULL,
                     NULL, 900, 900, 'SAVED', 'DONE',
                     'CLEAR', 1, 1)
                """.trimIndent(),
            )
        }
        return dbFile
    }

    /**
     * 按 v3 的 schema 建库并塞两条记录：一条**已确认过**（`cloud_confirmed_revision` 非空）、
     * 一条从未确认，外加一条"正在等在跑的上游"的任务行。
     *
     * 这正是 3→4 迁移要区分的两种形态 —— 回填只该命中前者。
     */
    private fun createVersion3Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(3).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`,
                     `is_favorite`, `is_pinned`, `archived_at`, `cloud_confirmed_revision`)
                VALUES
                    ('v3-confirmed', 'TEXT_NOTE', 'MANUAL', '确认过原文的记录', NULL, NULL,
                     NULL, 1000, 2000, 'SAVED', 'LOCAL_ONLY',
                     'SUSPECTED', 2, 0, 0, 0, NULL, 2)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`,
                     `is_favorite`, `is_pinned`, `archived_at`, `cloud_confirmed_revision`)
                VALUES
                    ('v3-unconfirmed', 'TEXT_NOTE', 'MANUAL', '从未确认过的记录', NULL, NULL,
                     NULL, 1000, 2000, 'SAVED', 'LOCAL_ONLY',
                     'SUSPECTED', 2, 0, 0, 0, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `processing_jobs`
                    (`id`, `record_id`, `job_type`, `status`, `attempts`, `max_attempts`,
                     `next_retry_at`, `error_category`, `started_at`, `finished_at`)
                VALUES
                    ('job-1', 'v3-confirmed', 'SUMMARIZE', 'QUEUED', 0, 3,
                     5000, '等待上游任务 OCR 产出正文', NULL, NULL)
                """.trimIndent(),
            )
        }
        return dbFile
    }

    /**
     * 按 v4 的 schema 建库并塞一条**已确认上传云端**的敏感网页记录。
     *
     * 4→5 要证明的正是"云端的确认不能自动变成抓取授权"：这两件事在 v4 里只有一列，
     * 升级时若顺手拿它回填新列，等于替用户签了一次"允许访问该 URL"。
     */
    private fun createVersion4Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(4).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`,
                     `is_favorite`, `is_pinned`, `archived_at`,
                     `cloud_confirmed_revision`, `cloud_confirmed_scope`)
                VALUES
                    ('v4-confirmed-web', 'WEB_LINK', 'MANUAL', NULL, 'https://example.com/a', '示例',
                     NULL, 1000, 2000, 'SAVED', 'LOCAL_ONLY',
                     'SUSPECTED', 2, 0, 0, 0, NULL, 2, 'RAW_TEXT')
                """.trimIndent(),
            )
        }
        return dbFile
    }

    /** 按 v5 的 schema 建库并塞一条记录：5→6 只需验证新列对存量行为 null 且可写。 */
    private fun createVersion5Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(5).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`, `is_favorite`,
                     `is_pinned`, `archived_at`, `cloud_confirmed_revision`,
                     `cloud_confirmed_scope`, `web_fetch_confirmed_revision`)
                VALUES
                    ('v5-1', 'TEXT_NOTE', 'MANUAL', 'v5 存的一段文字', NULL, NULL,
                     NULL, 1000, 2000, 'SAVED', 'DONE', 'CLEAR', 1, 0, 0, 0, NULL, NULL,
                     NULL, NULL)
                """.trimIndent(),
            )
        }
        return dbFile
    }

    /** 按 v6 的 schema 建库并塞两条普通记录：6→7 只新增表，不改动既有行。 */
    private fun createVersion6Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(6).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`, `is_favorite`,
                     `is_pinned`, `archived_at`, `cloud_confirmed_revision`,
                     `cloud_confirmed_scope`, `web_fetch_confirmed_revision`,
                     `capture_started_at`, `capture_ended_at`, `capture_duration_ms`)
                VALUES
                    ('v6-collection', 'COLLECTION', 'MANUAL', '合集说明', NULL, NULL,
                     NULL, 1000, 2000, 'SAVED', 'DONE', 'CLEAR', 1, 0, 0, 0, NULL, NULL,
                     NULL, NULL, NULL, NULL, NULL),
                    ('v6-member', 'TEXT_NOTE', 'MANUAL', '成员原文', NULL, NULL,
                     NULL, 900, 900, 'SAVED', 'DONE', 'CLEAR', 1, 0, 0, 0, NULL, NULL,
                     NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }
        return dbFile
    }

    @Test
    fun Migrate1To2_keepsExistingRowsAndAppliesDefaults() = runBlocking {
        createVersion1Database()

        // 触发迁移：Room 会先跑 MIGRATION_1_2，再校验最终结构
        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val migrated = database.recordDao().getById("old-1")

            assertTrue("旧记录必须还在", migrated != null)
            assertEquals("旧版本存的正文", migrated!!.rawText)
            assertEquals(RecordType.TEXT_NOTE, migrated.type)
            assertEquals(CaptureSource.MANUAL, migrated.captureSource)
            assertEquals(1000L, migrated.createdAt)
            assertEquals(2000L, migrated.updatedAt)
            assertEquals(LocalStatus.SAVED, migrated.localStatus)
            assertEquals(ProcessingStatus.DONE, migrated.processingStatus)
            assertEquals(SensitivityStatus.CLEAR, migrated.sensitivityStatus)
            assertEquals("修订号不能被迁移改掉", 3, migrated.recordRevision)
            assertFalse(migrated.isDeleted)

            // 新列：旧记录一律是"未收藏、未置顶、未归档"
            assertFalse("旧记录不该被误标为收藏", migrated.isFavorite)
            assertFalse("旧记录不该被误标为置顶", migrated.isPinned)
            assertNull("旧记录不该被误标为已归档", migrated.archivedAt)
        } finally {
            database.close()
        }
    }

    /** 软删除状态属于用户数据，迁移不得"顺手恢复"或"顺手清掉"。 */
    @Test
    fun Migrate1To2_preservesSoftDeleteFlag() = runBlocking {
        createVersion1Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val deleted = database.recordDao().getById("old-deleted")

            assertTrue(deleted!!.isDeleted)
            assertFalse(deleted.isFavorite)
        } finally {
            database.close()
        }
    }

    /**
     * 索引名必须与 Room 从实体推导出来的一致。
     *
     * 这一条单独查 `PRAGMA index_list`：结构校验失败时 Room 抛的是笼统的
     * "Migration didn't properly handle records"，看不出到底差了哪个索引 ——
     * 把名字直接断言出来，改错时一眼能看到期望值。
     */
    @Test
    fun Migrate1To2_createsInboxIndex() {
        createVersion1Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val names = indexNames(database, "records")

            assertTrue(
                "缺少收件箱用的组合索引：$names",
                "index_records_is_deleted_archived_at" in names,
            )
            assertTrue("v1 原有索引不能被迁移弄丢：$names", "index_records_processing_status" in names)
        } finally {
            database.close()
        }
    }

    /**
     * 反向控制：迁移写好之后，用户真的能继续写入新值。
     *
     * 只验"旧数据还在"会漏掉一种情况——列加上了但 Room 的绑定没生效（写进去始终是默认值），
     * 那种 bug 在界面上表现为"点了收藏又变回去"。
     */
    @Test
    fun Migrate1To2_supportsWritingNewFlags() = runBlocking {
        createVersion1Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val repository = database.recordDao()
            val old = repository.getById("old-1")!!

            repository.update(old.copy(isFavorite = true, isPinned = true, archivedAt = 5_000L))

            val updated = repository.getById("old-1")!!
            assertTrue(updated.isFavorite)
            assertTrue(updated.isPinned)
            assertEquals(5_000L, updated.archivedAt)
        } finally {
            database.close()
        }
    }

    /**
     * 3 → 4 的确认范围回填（2026-10-23 审查 R-2）。
     *
     * 只给"确认过版本号"的记录补 `RAW_TEXT`：当时的界面上能看到的只有原文，
     * 因此"确认过"在那个版本里的语义就是"原文已看过"。
     * 从未确认过的记录必须保持 `NULL` —— 否则等于替用户签了一次字。
     */
    @Test
    fun Migrate3To4_backfillsScopeOnlyForConfirmedRecords() = runBlocking {
        createVersion3Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val confirmed = database.recordDao().getById("v3-confirmed")!!
            assertEquals("RAW_TEXT", confirmed.cloudConfirmedScope)
            assertEquals("版本号不能被迁移改掉", 2, confirmed.cloudConfirmedRevision)

            val unconfirmed = database.recordDao().getById("v3-unconfirmed")!!
            assertNull("从未确认过的记录不能被回填成已确认", unconfirmed.cloudConfirmedScope)
        } finally {
            database.close()
        }
    }

    /** 3 → 4 的等待轮次列：存量任务一律是"还没等过"（R-4）。 */
    @Test
    fun Migrate3To4_waitRoundsDefaultsToZero() = runBlocking {
        createVersion3Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val job = database.processingJobDao()
                .getByRecordAndType("v3-confirmed", JobType.SUMMARIZE)
            assertEquals("存量任务的等待轮次必须是 0", 0, job!!.waitRounds)
            assertEquals("等待中的任务状态不能被迁移改掉", JobStatus.QUEUED, job.status)
        } finally {
            database.close()
        }
    }

    /**
     * 反向控制：迁移后这两列真的能写进去、读得出来。
     *
     * 只验"旧数据还在"会漏掉"列加上了但 Room 绑定没生效"（写进去永远是默认值）——
     * 那种 bug 在界面上表现为"点了确认没反应"。
     */
    @Test
    fun Migrate3To4_supportsWritingScope() = runBlocking {
        createVersion3Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val repository = database.recordDao()
            val old = repository.getById("v3-unconfirmed")!!

            repository.update(old.copy(cloudConfirmedScope = "RAW_TEXT,TRANSCRIPTION"))

            assertEquals(
                "RAW_TEXT,TRANSCRIPTION",
                repository.getById("v3-unconfirmed")!!.cloudConfirmedScope,
            )
        } finally {
            database.close()
        }
    }

    /**
     * 4 → 5：云端的确认**不能**自动变成"允许抓取该网页"（2026-10-23 复核 F-5）。
     *
     * 这是本次升级最容易做错的一处：两列长得像、语义又都是"用户同意"，
     * 顺手回填会让所有历史敏感网页在用户毫不知情的情况下获得"访问该 URL"的授权。
     */
    @Test
    fun Migrate4To5_doesNotInheritCloudConfirmation() = runBlocking {
        createVersion4Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val record = database.recordDao().getById("v4-confirmed-web")!!

            assertEquals("v4 的云端确认必须原样保留", 2, record.cloudConfirmedRevision)
            assertEquals("RAW_TEXT", record.cloudConfirmedScope)
            assertNull(
                "抓取授权必须从零开始，不能继承云端确认",
                record.webFetchConfirmedRevision,
            )
        } finally {
            database.close()
        }
    }

    /**
     * 反向控制：新列真的能写进去、读得出来。
     *
     * 只验"迁移后是 NULL"会漏掉"列加上了但 Room 绑定没生效"——
     * 那种 bug 在界面上的表现是"点了允许抓取没反应"。
     */
    @Test
    fun Migrate4To5_supportsWritingWebFetchConfirmation() = runBlocking {
        createVersion4Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val dao = database.recordDao()
            val old = dao.getById("v4-confirmed-web")!!

            dao.update(old.copy(webFetchConfirmedRevision = 2))

            assertEquals(2, dao.getById("v4-confirmed-web")!!.webFetchConfirmedRevision)
        } finally {
            database.close()
        }
    }

    /**
     * 5 → 6：录音采集时间窗（PRD v1.2 §4.2）。
     *
     * 存量记录不能拿 `created_at` 冒充采集时间 —— 落库时间不等于用户按下录音的时间。
     */
    @Test
    fun Migrate5To6_captureFieldsDefaultToNull() = runBlocking {
        createVersion5Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val old = database.recordDao().getById("v5-1")!!
            assertEquals("v5 存的一段文字", old.rawText)
            assertNull("非录音记录不该有采集开始时间", old.captureStartedAt)
            assertNull("非录音记录不该有采集结束时间", old.captureEndedAt)
            assertNull("非录音记录不该有采集时长", old.captureDurationMs)
        } finally {
            database.close()
        }
    }

    /** 反向控制：新列真的能写进去、读得出来，而不是永远停在 null。 */
    @Test
    fun Migrate5To6_supportsWritingCaptureTimestamps() = runBlocking {
        createVersion5Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val dao = database.recordDao()
            val old = dao.getById("v5-1")!!

            dao.update(
                old.copy(
                    captureStartedAt = 1_000L,
                    captureEndedAt = 4_500L,
                    captureDurationMs = 3_500L,
                ),
            )

            val updated = dao.getById("v5-1")!!
            assertEquals(1_000L, updated.captureStartedAt)
            assertEquals(4_500L, updated.captureEndedAt)
            assertEquals(3_500L, updated.captureDurationMs)
        } finally {
            database.close()
        }
    }

    /**
     * 6 → 7：新增合集成员表，既有记录与字段保持原样。
     *
     * 建表迁移不碰 `records`，所以这里重点验证"打开成功 + 旧行不变 + 新表从空开始"。
     */
    @Test
    fun Migrate6To7_keepsExistingRowsAndStartsWithoutMembers() = runBlocking {
        createVersion6Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val old = database.recordDao().getById("v6-member")!!
            assertEquals("成员原文", old.rawText)
            assertEquals(RecordType.TEXT_NOTE, old.type)
            assertTrue("v6 已有成员表不应凭空出现成员关系", database.collectionDao().getMembers("v6-collection").isEmpty())
        } finally {
            database.close()
        }
    }

    /**
     * 反向控制：迁移后成员关系真的能写、能读、能更新快照。
     *
     * 只验"表存在"会漏掉外键/索引写错导致的插入失败；再顺带验证
     * `DELETE records` 会级联清理成员关系（M15 物理清理依赖这条）。
     */
    @Test
    fun Migrate6To7_supportsWritingMembersAndCascadesOnRecordDelete() = runBlocking {
        createVersion6Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val dao = database.collectionDao()
            dao.insertAll(
                listOf(
                    CollectionMemberEntity(
                        collectionId = "v6-collection",
                        recordId = "v6-member",
                        position = 0,
                        addedAt = 3_000L,
                    ),
                ),
            )

            val member = dao.getMembers("v6-collection").single()
            assertEquals("v6-member", member.recordId)
            assertNull("从未生成过摘要时快照必须为空", member.snapshotFingerprint)

            dao.updateSnapshot("v6-collection", "v6-member", "rev:1;deleted:0;archived:0;body:0")
            assertEquals(
                "rev:1;deleted:0;archived:0;body:0",
                dao.getMembers("v6-collection").single().snapshotFingerprint,
            )

            // 物理删除成员记录：外键 CASCADE 必须带走成员关系，不能留下死行
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM `records` WHERE `id` = 'v6-member'",
            )
            assertTrue(
                "物理删除成员后成员关系必须级联消失",
                dao.getMembers("v6-collection").isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    /**
     * 链式升级 1 → 当前版本：从 v1 一路升到最后，中间不漏一步。
     *
     * v1 时代的记录根本没有"确认"这个概念，升级后必须是"从未确认"（云端与抓取都是），
     * 也不需要虚构一段录音采集时间。
     */
    @Test
    fun Migrate1ToCurrentVersion_chainLeavesOldRecordsUnconfirmed() = runBlocking {
        createVersion1Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val old = database.recordDao().getById("old-1")!!
            assertEquals("旧版本存的正文", old.rawText)
            assertNull("v1 记录不该被回填确认版本号", old.cloudConfirmedRevision)
            assertNull("v1 记录不该被回填确认范围", old.cloudConfirmedScope)
            assertNull("v1 记录也不该自动获得抓取授权", old.webFetchConfirmedRevision)
            assertNull("v1 记录不该被补上录音采集时间", old.captureStartedAt)
        } finally {
            database.close()
        }
    }

    /**
     * 按 v7 的 schema 建库并塞一条含两个附件、两条旧格式派生内容的记录。
     *
     * 这正是 7→8 要处理的存量形态：附件没有文件名/顺序，OCR 结果只有一条
     * （且 `asset_id` 列还不存在）。迁移必须原样保留，不得猜它属于哪个附件。
     */
    private fun createVersion7Database(): File {
        val dbFile = context.getDatabasePath(dbName)
        createSchemaDatabase(7).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `source_url`, `source_title`,
                     `source_app`, `capture_started_at`, `capture_ended_at`, `capture_duration_ms`,
                     `created_at`, `updated_at`, `local_status`, `processing_status`,
                     `sensitivity_status`, `record_revision`, `is_deleted`, `is_favorite`,
                     `is_pinned`, `archived_at`, `cloud_confirmed_revision`,
                     `cloud_confirmed_scope`, `web_fetch_confirmed_revision`)
                VALUES
                    ('v7-multi', 'IMAGE', 'MANUAL', NULL, NULL, NULL,
                     NULL, NULL, NULL, NULL, 1000, 2000, 'SAVED', 'DONE',
                     'CLEAR', 1, 0, 0, 0, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `assets`
                    (`id`, `record_id`, `mime_type`, `local_path`, `file_size`,
                     `file_hash`, `original_uri`, `save_status`)
                VALUES
                    ('asset-a', 'v7-multi', 'image/png', '/data/a.png', 1024,
                     'hash-a', 'content://a', 'SAVED'),
                    ('asset-b', 'v7-multi', 'image/jpeg', '/data/b.jpg', 2048,
                     'hash-b', NULL, 'SAVED')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `derived_contents`
                    (`id`, `record_id`, `content_type`, `content`, `processor_version`,
                     `processed_at`, `error_message`)
                VALUES
                    ('v7-multi:OCR_TEXT', 'v7-multi', 'OCR_TEXT', '第一张图上的字',
                     'mlkit-text-chinese/16.0.1', 3000, NULL),
                    ('v7-multi:SUMMARY', 'v7-multi', 'SUMMARY', '旧摘要',
                     'gateway-summarize/v1', 3000, NULL)
                """.trimIndent(),
            )
        }
        return dbFile
    }

    /**
     * 7 → 8：旧附件与旧派生内容原样保留（多附件需求 R1–R3）。
     *
     * 重点是"不回填"：`file_name` 必须为 NULL（旧版本根本没存过），
     * 旧 OCR 行的 `asset_id` 必须是空串而不是被猜成某张图 —— 猜错会让用户以为
     * 某张图已经识别过，而实际上它从未被处理。
     */
    @Test
    fun Migrate7To8_keepsAssetsAndLeavesLegacyRowsRecordLevel() = runBlocking {
        createVersion7Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val assets = database.assetDao().getByRecordId("v7-multi")
            assertEquals("两个附件都不能丢", 2, assets.size)
            assertEquals(listOf("asset-a", "asset-b"), assets.map { it.id })
            assets.forEach {
                assertNull("旧附件没有原始文件名，不能编造", it.fileName)
                assertEquals("旧附件顺序只能是 0（退化为按 id）", 0, it.orderIndex)
            }

            val derived = database.derivedContentDao().getByRecordId("v7-multi")
            assertEquals(2, derived.size)
            assertTrue(
                "旧 OCR 结果必须保留，且只能是记录级（不猜附件）",
                derived.all { it.assetId.isEmpty() },
            )
            assertEquals(
                "第一张图上的字",
                derived.single { it.contentType == DerivedType.OCR_TEXT }.content,
            )
        } finally {
            database.close()
        }
    }

    /**
     * 反向控制：迁移后同一记录可以按附件各存一条 OCR，且同一附件同类型仍只留一条。
     *
     * 只验"旧行还在"会漏掉唯一索引没换的情况：索引还是两列时，写第二张图会直接抛
     * `UNIQUE constraint failed`，表现为"多图识别到第二张就崩"。
     */
    @Test
    fun Migrate7To8_allowsOneRowPerAsset() = runBlocking {
        createVersion7Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val dao = database.derivedContentDao()
            dao.upsert(ocrRow(assetId = "asset-a", content = "图 A 的字"))
            dao.upsert(ocrRow(assetId = "asset-b", content = "图 B 的字"))

            val ocr = dao.getByRecordId("v7-multi").filter { it.contentType == DerivedType.OCR_TEXT }
            assertEquals("每个附件各一条 OCR", 3, ocr.size)
            assertEquals("图 A 的字", ocr.single { it.assetId == "asset-a" }.content)
            assertEquals("图 B 的字", ocr.single { it.assetId == "asset-b" }.content)

            // 同一附件的同类型重跑：REPLACE 覆盖而不是新增
            dao.upsert(ocrRow(assetId = "asset-a", content = "图 A 重跑后的字"))
            val refreshed = dao.getByAssetId("asset-a").single()
            assertEquals("图 A 重跑后的字", refreshed.content)
        } finally {
            database.close()
        }
    }

    /** 7 → 8 的索引改名与新增必须与实体推导一致，否则升级后 Room 结构校验直接失败。 */
    @Test
    fun Migrate7To8_swapsDerivedIndexAndAddsAssetOrderIndex() {
        createVersion7Database()

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val derivedIndices = indexNames(database, "derived_contents")
            assertTrue(
                "三列唯一索引缺失：$derivedIndices",
                "index_derived_contents_record_id_content_type_asset_id" in derivedIndices,
            )
            assertFalse(
                "两列旧索引必须删掉，否则同类型多附件仍被拦：$derivedIndices",
                "index_derived_contents_record_id_content_type" in derivedIndices,
            )

            val assetIndices = indexNames(database, "assets")
            assertTrue(
                "缺少按记录取序的索引：$assetIndices",
                "index_assets_record_id_order_index" in assetIndices,
            )
        } finally {
            database.close()
        }
    }

    /**
     * 8 → 9：新建 `search_history`（M21），既有记录原样保留，历史表立即可写可读。
     *
     * 迁移只做加法，因此这里同时验证"旧数据还在"与"新表结构/索引与实体一致"
     * （后者不对会在打开库时被 Room 结构校验直接拒开）。
     */
    @Test
    fun Migrate8To9_createsSearchHistoryAndKeepsRecords() = runBlocking {
        createSchemaDatabase(8).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `created_at`, `updated_at`,
                     `local_status`, `processing_status`, `sensitivity_status`)
                VALUES
                    ('v8-keep', 'TEXT_NOTE', 'MANUAL', 'v8 里的记录', 1000, 2000, 'SAVED', 'DONE', 'CLEAR')
                """.trimIndent(),
            )
        }

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            assertEquals("v8 里的记录", database.recordDao().getById("v8-keep")?.rawText)

            assertTrue(
                "缺少按时间取最近搜索的索引：${indexNames(database, "search_history")}",
                "index_search_history_last_searched_at" in indexNames(database, "search_history"),
            )

            val dao = database.searchHistoryDao()
            dao.insert(SearchHistoryEntity("陪伴", "陪伴", 1_000, 1))
            dao.insert(SearchHistoryEntity("compose", "Compose", 2_000, 1))
            assertEquals(
                listOf("compose", "陪伴"),
                dao.observeRecent(10).first().map { it.queryKey },
            )

            dao.touch(queryKey = "陪伴", query = "陪伴", now = 3_000)
            val top = dao.observeRecent(10).first().first()
            assertEquals("陪伴", top.queryKey)
            assertEquals(2, top.searchCount)
        } finally {
            database.close()
        }
    }

    /**
     * 9 → 10：新建 `embedding_meta` 并回填老向量（开源改造 P1）。
     *
     * 三条断言对应三个失败后果：
     * 1. 表结构能通过 Room 校验（否则用户升级后打不开 App）；
     * 2. 老向量的模型名被认领回来（否则"换模型检测"对存量数据完全失效）；
     * 3. 老向量在"来源未知"时仍参与语义检索（否则升级一次语义搜索就空了）。
     */
    @Test
    fun Migrate9To10_createsEmbeddingMetaAndBackfillsOldVectors() = runBlocking {
        createSchemaDatabase(9).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `created_at`, `updated_at`,
                     `local_status`, `processing_status`, `sensitivity_status`)
                VALUES
                    ('v9-embed', 'TEXT_NOTE', 'MANUAL', 'v9 里的记录', 1000, 2000, 'SAVED', 'DONE', 'CLEAR')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `derived_contents`
                    (`id`, `record_id`, `content_type`, `content`, `processor_version`,
                     `processed_at`, `error_message`, `asset_id`)
                VALUES
                    ('v9-embed:EMBEDDING', 'v9-embed', 'EMBEDDING', 'AAAAAA==',
                     'embed/legacy-model', 2000, NULL, '')
                """.trimIndent(),
            )
        }

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            val meta = database.embeddingMetaDao().getByRecordId("v9-embed")
            assertEquals("老向量的模型名必须被回填", "legacy-model", meta?.embedModel)
            assertEquals("历史维度无从得知，填 0 而不是猜", 0, meta?.embedDim)
            assertEquals("历史来源未知", "", meta?.embedProviderUrl)

            val rows = database.derivedContentDao().getSearchableEmbeddings(
                type = DerivedType.EMBEDDING,
                expectedVersion = "embed/legacy-model",
                providerKey = "https://new-provider.example.com",
            )
            assertEquals("来源未知的老向量必须继续参与语义检索", 1, rows.size)
        } finally {
            database.close()
        }
    }

    /**
     * 10 → 11：AI 输出语言快照（P11-07 / R-I18N-08）。
     *
     * 三条断言对应三个失败后果：
     * 1. 老 AI 结果回填 `zh`（否则英文界面下历史中文摘要会被当成英文，重生成判断失真）；
     * 2. 老任务行语言为 NULL（不能用默认值假装知道，自动重试必须回落到中文口径）；
     * 3. 新结果能写入并读回 `en`（否则本次改造对生成类结果不生效）。
     */
    @Test
    fun Migrate10To11_addsLanguageColumnsAndBackfillsChinese() = runBlocking {
        createSchemaDatabase(10).use { db ->
            db.execSQL(
                """
                INSERT INTO `records`
                    (`id`, `type`, `capture_source`, `raw_text`, `created_at`, `updated_at`,
                     `local_status`, `processing_status`, `sensitivity_status`)
                VALUES
                    ('v10-lang', 'TEXT_NOTE', 'MANUAL', 'v10 里的记录', 1000, 2000, 'SAVED', 'DONE', 'CLEAR')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `derived_contents`
                    (`id`, `record_id`, `content_type`, `content`, `processor_version`,
                     `processed_at`, `error_message`, `asset_id`)
                VALUES
                    ('v10-lang:SUMMARY', 'v10-lang', 'SUMMARY', '升级前生成的中文摘要',
                     'gateway-enrich/v1', 2000, NULL, '')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `processing_jobs`
                    (`id`, `record_id`, `job_type`, `status`, `attempts`, `max_attempts`,
                     `wait_rounds`, `next_retry_at`, `error_category`, `started_at`, `finished_at`)
                VALUES
                    ('job-v10', 'v10-lang', 'SUMMARIZE', 'DONE', 1, 3, 0, NULL, NULL, 1000, 2000)
                """.trimIndent(),
            )
        }

        val database = AppDatabaseFactory.create(context, name = dbName)
        try {
            database.openHelper.readableDatabase
                .query("SELECT `output_language` FROM `derived_contents` WHERE `id` = 'v10-lang:SUMMARY'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("升级前的 AI 结果按中文如实回填", "zh", cursor.getString(0))
                }
            database.openHelper.readableDatabase
                .query("SELECT `output_language` FROM `processing_jobs` WHERE `id` = 'job-v10'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue("历史任务没有语言信息，不能用默认值假装知道", cursor.isNull(0))
                }

            database.derivedContentDao().upsert(
                DerivedContentEntity(
                    id = "v10-lang:SUMMARY:new",
                    recordId = "v10-lang",
                    contentType = DerivedType.SUMMARY,
                    content = "English summary",
                    processorVersion = "gateway-enrich/v1",
                    processedAt = 3000,
                    errorMessage = null,
                    outputLanguage = "en",
                ),
            )
            database.openHelper.readableDatabase
                .query("SELECT `output_language` FROM `derived_contents` WHERE `id` = 'v10-lang:SUMMARY:new'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("新结果必须能记录英文", "en", cursor.getString(0))
                }
        } finally {
            database.close()
        }
    }

    private fun ocrRow(assetId: String, content: String) = DerivedContentEntity(
        id = "v7-multi:OCR_TEXT:$assetId",
        recordId = "v7-multi",
        contentType = DerivedType.OCR_TEXT,
        content = content,
        processorVersion = "mlkit-text-chinese/16.0.1",
        processedAt = 4_000L,
        errorMessage = null,
        assetId = assetId,
    )

    private fun indexNames(database: com.zhicun.app.data.local.AppDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }
}
