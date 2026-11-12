package com.zhicun.app.data.local

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 创建并维护全文检索虚表 `record_fts`。
 *
 * ## 为什么是 FTS4 而不是 ARCHITECTURE.md §6.3 写的 FTS5
 *
 * Android 系统自带的 SQLite **没有编译 FTS5 模块**，实测证据：
 * 1. AOSP `platform/external/sqlite/dist/Android.bp`（main 分支）只定义了
 *    `SQLITE_ENABLE_FTS3` 与 `SQLITE_ENABLE_FTS4`，全文件无 `SQLITE_ENABLE_FTS5`；
 * 2. 在 Robolectric 的原生 SQLite（3.32.2）上执行
 *    `CREATE VIRTUAL TABLE t USING fts5(a)` 直接抛 `no such module: fts5`，
 *    而同一环境下 `fts4` 创建成功。
 *
 * 因此 FTS5 在任何未魔改 ROM 的真机上都不可用，写 FTS5 只会落到"永远失败 →
 * 降级"的死分支（降级分支无法被任何测试覆盖，属于不可验证代码）。故这里直接用
 * FTS4，与 `androidx.room:room-fts` 支持的后端一致，全链路单一实现、可测。
 *
 * 未来若引入捆绑版 SQLite（例如 `requery/sqlite-android` 或 SQLDelight 自带驱动，
 * 代价约 +1.5MB APK），可把 `FTS_TABLE_DDL` 换成 `fts5(...)`，触发器与查询语句无需改动。
 * 该偏差已在 DEVELOPMENT_PLAN.md M2「完成说明」中记录，待架构师裁决。
 *
 * ## 为什么不用 Room 注解
 * Room 只提供 `@Fts3` / `@Fts4` 注解，且注解方式无法挂同步触发器；本表需要与
 * `records` 表保持实时一致（含软删除语义），故用原生 SQL 建表 + 触发器维护。
 *
 * 索引字段：`raw_text`、`source_url`、`source_title`（M2 验收要求）。
 * 派生内容（OCR/转写/摘要）的检索由 M10 通过派生表 JOIN 解决，不放进这张表。
 */
class FtsSyncCallback : RoomDatabase.Callback() {

    /**
     * 建库时执行一次（`onOpen` 在新建库之后也会被调用，两条路径的语义相同，故都挂）。
     *
     * 保留 `onCreate` 而不是只留 `onOpen`：Room 的 `onOpen` 依赖 `SQLiteOpenHelper`
     * 在 `onCreate` 之后回调，不同实现（含测试替身）不能保证这个顺序。两处调用同一份
     * 幂等逻辑，不会产生"两套判据"，只是两个入口。
     */
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        ensureSchema(db)
    }

    /**
     * **每次打开库都对齐一次**（2026-10-24 全项目审查 M-2）。
     *
     * 之前只在 `onCreate` 里建表建触发器，带来三个问题：
     * 1. 迁移路径不执行 `onCreate` —— 用 schema json 造的旧库（迁移测试就是这么造的）
     *    以及从外部恢复进来的库，`record_fts` 根本不存在，检索会静默返回空；
     * 2. 触发器用 `IF NOT EXISTS`，定义一旦改过，老用户永远拿不到新版本；
     * 3. 虚表缺失时没有任何补建与回填的机会。
     *
     * 因此改为每次打开都做一次幂等对齐：缺表就建并**从 records 回填**，触发器一律先删后建
     * （保证定义与代码一致）。代价是每次冷启动多几条 DDL，可忽略。
     */
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        ensureSchema(db)
    }

    companion object {
        private const val TAG = "FtsSync"

        /** 虚表名，DAO/查询侧统一引用此常量。 */
        const val TABLE = "record_fts"

        private const val TRIGGER_INSERT = "trg_records_fts_ai"
        private const val TRIGGER_UPDATE = "trg_records_fts_au"
        private const val TRIGGER_DELETE = "trg_records_fts_ad"

        /**
         * 幂等对齐：确保虚表、三个触发器、以及（虚表刚刚被补建时的）索引回填。
         * 重复调用不会产生重复行，也不会覆盖已有索引。
         */
        internal fun ensureSchema(db: SupportSQLiteDatabase) {
            val tableExisted = tableExists(db, TABLE)
            db.execSQL(FTS_TABLE_DDL)

            // 触发器先删后建：`IF NOT EXISTS` 保不住"定义升级"，只有重建才能让老库跟上代码
            listOf(TRIGGER_INSERT, TRIGGER_UPDATE, TRIGGER_DELETE).forEach { name ->
                db.execSQL("DROP TRIGGER IF EXISTS $name")
            }
            db.execSQL(TRIGGER_AFTER_INSERT)
            db.execSQL(TRIGGER_AFTER_UPDATE)
            db.execSQL(TRIGGER_AFTER_DELETE)

            if (tableExisted) {
                // 表已经在：触发器定义可能刚被改过（本次就把"归档不进索引"加进了条件），
                // 老库的索引内容不会自己跟着变 → 必须对账一次，见 [reconcile]
                reconcile(db)
            } else {
                // 虚表是本轮才补建的（迁移库 / 从备份恢复的库 / 人工删过索引）→ 必须回填，
                // 否则表在、索引空，表现为"检索永远没结果"这种最难受的静默故障
                backfill(db)
            }
        }

        /**
         * 索引与"当前定义"对账（2026-10-24，归档退出检索时新增）。
         *
         * ## 为什么必须有这一步
         * 触发器只对**以后**的写入生效。把 `archived_at IS NULL` 加进触发器条件之后，
         * 老用户库里那些**升级前就已归档**的记录仍然留在索引里 —— 触发器重建了，
         * 但它们没被 UPDATE 过，索引不会自己变。结果就是"用户要求归档后搜不到，
         * 升级完却还能搜到"，而且只对历史数据失效，测新数据永远测不出来。
         *
         * ## 为什么不是每次全量回填
         * 全量回填要把所有记录重新分词，且 `onOpen` 跑在主线程 —— 记录多了就是可见的启动卡顿。
         * 对账只做两次全表扫描 + 差集，正常情况下零写入。
         *
         * 代价：触发器若因某种原因完全失效，这里会静默修好，只留下一条日志。
         * 相比"检索结果不可信"，这个方向可以接受，但**不能**因此就不查触发器的问题。
         *
         * ## 为什么还要比内容（2026-11-05 移植海外轨 Antigravity BUG-03）
         * 只比 ID 集合有一个盲区：触发器若因批量更新/迁移中断失效，主表文字已经变了、
         * 索引里仍是旧文本 —— ID 集合完全一致，这里会判定"已同步"，检索却命中旧关键词
         * （幽灵结果）。因此同时对账三列内容，漂移则删旧插新，等价于触发器重跑一次。
         *
         * 提升为 `internal` 是为了让用例能直接驱动对账逻辑（原先 private 只能靠开库间接触发）。
         */
        internal fun reconcile(db: SupportSQLiteDatabase) {
            val stored = HashMap<String, List<String?>>()
            db.query("SELECT record_id, raw_text, source_url, source_title FROM $TABLE").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    stored[id] = listOf(
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                    )
                }
            }

            val expected = HashMap<String, List<String?>>()
            db.query(
                """
                SELECT id, raw_text, source_url, source_title FROM records
                WHERE is_deleted = 0 AND archived_at IS NULL
                """.trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    expected[cursor.getString(0)] = listOf(
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                    )
                }
            }

            var removed = 0
            var added = 0
            var reindexed = 0
            stored.keys.forEach { id ->
                if (id !in expected) {
                    db.execSQL("DELETE FROM $TABLE WHERE record_id = ?", arrayOf<Any?>(id))
                    removed++
                }
            }
            expected.forEach { (id, columns) ->
                val existing = stored[id]
                when {
                    existing == null -> {
                        db.execSQL(
                            "INSERT INTO $TABLE(record_id, raw_text, source_url, source_title) VALUES (?, ?, ?, ?)",
                            arrayOf<Any?>(id, columns[0], columns[1], columns[2]),
                        )
                        added++
                    }

                    existing != columns -> {
                        // 内容漂移：删掉旧行再插当前值，等价于触发器重跑一次
                        db.execSQL("DELETE FROM $TABLE WHERE record_id = ?", arrayOf<Any?>(id))
                        db.execSQL(
                            "INSERT INTO $TABLE(record_id, raw_text, source_url, source_title) VALUES (?, ?, ?, ?)",
                            arrayOf<Any?>(id, columns[0], columns[1], columns[2]),
                        )
                        reindexed++
                    }
                }
            }
            if (removed > 0 || added > 0 || reindexed > 0) {
                // 留痕：正常升级会打一次（历史归档记录被移出索引），此后应当安静
                Log.w(
                    TAG,
                    "全文索引与记录不一致，已对账：移出 $removed 条，补入 $added 条，按内容重建 $reindexed 条",
                )
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name),
            ).use { it.moveToFirst() }

        private fun backfill(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO $TABLE(record_id, raw_text, source_url, source_title)
                SELECT id, raw_text, source_url, source_title FROM records
                WHERE is_deleted = 0 AND archived_at IS NULL
                """.trimIndent(),
            )
        }

        /**
         * `record_id` 用 `notindexed=` 排除出索引：它只是回查 `records` 的键，
         * 参与分词既浪费索引体积，也会让形如 "rec" 的检索词命中所有行。
         */
        private val FTS_TABLE_DDL = """
            CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(
                record_id,
                raw_text,
                source_url,
                source_title,
                notindexed=record_id
            )
        """.trimIndent()

        // 软删除与已归档的记录都不进入索引：两者都是"用户不想再看到它"，
        // 判据只有一处（这里），检索侧不必各自再记一遍。
        private val TRIGGER_AFTER_INSERT = """
            CREATE TRIGGER $TRIGGER_INSERT
            AFTER INSERT ON records
            WHEN new.is_deleted = 0 AND new.archived_at IS NULL
            BEGIN
                INSERT INTO $TABLE(record_id, raw_text, source_url, source_title)
                VALUES (new.id, new.raw_text, new.source_url, new.source_title);
            END
        """.trimIndent()

        /**
         * 更新（含写入/修改原文、软删除、归档与恢复）统一先删后插，避免 FTS 残留旧词。
         *
         * `AFTER UPDATE OF ...` + `WHEN ... IS NOT ...` 两层限定缺一不可（2026-10-24 全项目审查 M-1）：
         * 没有限定的话，**任何** UPDATE 都会重建这一行的索引 —— 而 `records` 上最频繁的写入恰恰
         * 不是正文，是 `processing_status` / `updated_at` / `is_favorite`。任务重试与状态聚合
         * 会反复触发，长 OCR 文本每次都被整体重新分词，纯属白烧 CPU。
         *
         * `IS NOT` 而不是 `!=`：只有它把 NULL 参与的比较算成"相等"，正文从 NULL 改成空串这类
         * 无意义变更不会误触发（反过来用 `!=` 会漏掉 NULL → 值 的变化）。
         *
         * `archived_at` 必须同时出现在 `OF` 列表与 `WHEN` 里：只加在 `OF` 里，归档不会重建索引；
         * 只加在 `WHEN` 里，这个触发器根本不会被归档的 UPDATE 唤醒。两处都写才算接上。
         */
        private val TRIGGER_AFTER_UPDATE = """
            CREATE TRIGGER $TRIGGER_UPDATE
            AFTER UPDATE OF raw_text, source_url, source_title, is_deleted, archived_at ON records
            WHEN old.raw_text IS NOT new.raw_text
              OR old.source_url IS NOT new.source_url
              OR old.source_title IS NOT new.source_title
              OR old.is_deleted IS NOT new.is_deleted
              OR old.archived_at IS NOT new.archived_at
            BEGIN
                DELETE FROM $TABLE WHERE record_id = old.id;
                INSERT INTO $TABLE(record_id, raw_text, source_url, source_title)
                SELECT new.id, new.raw_text, new.source_url, new.source_title
                WHERE new.is_deleted = 0 AND new.archived_at IS NULL;
            END
        """.trimIndent()

        private val TRIGGER_AFTER_DELETE = """
            CREATE TRIGGER $TRIGGER_DELETE
            AFTER DELETE ON records
            BEGIN
                DELETE FROM $TABLE WHERE record_id = old.id;
            END
        """.trimIndent()
    }
}
