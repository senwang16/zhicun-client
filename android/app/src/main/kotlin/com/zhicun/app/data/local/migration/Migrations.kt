package com.zhicun.app.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 → 2：给 `records` 补"整理"用的三列（M16）。
 *
 * ## 为什么是三条 `ALTER TABLE` 而不是"建新表 + 拷数据"
 * 只加列、不改类型、不删列时，`ALTER TABLE ADD COLUMN` 是 SQLite 的原生操作：
 * O(1) 元数据修改、不动任何数据行。重建表要把整张 `records` 读出来再写回去，
 * 一旦中途失败就留下半张表 —— 为了加三个带默认值的列冒这个险不值得。
 *
 * ## 默认值必须写死在 SQL 里
 * `NOT NULL` 的新列如果没有 `DEFAULT`，SQLite 无法为**已有行**填值，语句直接报错。
 * `0` 同时表达"未收藏/未置顶"，与 `RecordEntity` 上的 `defaultValue = "0"` 一致：
 * 两者不一致时 Room 会在迁移后的结构校验里报错（这正是我们想要的失败方式）。
 *
 * ## `archived_at` 可空
 * `NULL` = 未归档。可空列不需要默认值，加列后所有旧记录自动是"未归档"，
 * 语义上正好是"归档功能上线前存的东西都还在收件箱里"。
 *
 * ## 索引名必须与 Room 推导出来的一致
 * Room 对未命名索引按 `index_<表>_<列...>` 生成名字，并在迁移后逐项校验。
 * 这里手写的 `index_records_is_deleted_archived_at` 与实体里的
 * `Index(value = ["is_deleted", "archived_at"])` 一一对应；改名会让升级在真机上
 * 抛 `IllegalStateException`，而只在有旧数据的设备上才复现。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `records` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `records` ADD COLUMN `archived_at` INTEGER")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_records_is_deleted_archived_at` " +
                "ON `records` (`is_deleted`, `archived_at`)",
        )
    }
}

/**
 * 2 → 3：给 `records` 补 `cloud_confirmed_revision`（2026-10-23 QODER P2-6）。
 *
 * 敏感记录的"用户同意上传"从"改写 sensitivity_status 为 CLEAR"改为"记录确认时的
 * 内容版本"。可空列、无默认值：加列后所有存量行自动是"从未确认过"。
 *
 * 已知限制：旧版本的确认会把 `sensitivity_status` 直接改成 `CLEAR`，与"检测结果干净"
 * 无法区分，因此本迁移**无法**自动识别并重新拦截那些曾被洗白的历史记录；它们保持
 * CLEAR。新流程不再改写该列，确认绑定版本号，编辑后自动失效。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `cloud_confirmed_revision` INTEGER")
    }
}

/**
 * 3 → 4：确认范围 + 等待轮次（2026-10-23 审查 R-2 / R-4）。
 *
 * 两件事一起升版，是因为它们都只差一列，而**一次迁移比两次省一次风险窗口**：
 * 迁移代码只在"装过旧版本又升级"的设备上跑，多一次迁移就多一处本地测不到的真机风险。
 *
 * ## `records.cloud_confirmed_scope`
 * 只比版本号不够：OCR / 转写落地时不递增 `record_revision`，一次确认会把之后
 * 才产生的内容一起放行（R-2）。存"确认时已存在哪些内容块"。
 *
 * 可空列、无默认值 + 一条回填：**老数据的确认语义就是"原文已看过"**
 * （当时界面上能看到的只有 `raw_text`），所以补成 `RAW_TEXT`。
 * 不补的话老记录会立刻变成"未确认"，升级后摘要停在原地，而用户不知道要点什么。
 * 只补 `RAW_TEXT` 也是刻意的：那些记录如果含 OCR / 转写，说明用户当时确实没看过，
 * 让它们重新确认一次正是本次修复的目的。
 *
 * ## `processing_jobs.wait_rounds`
 * `NOT NULL DEFAULT 0`：等待轮次是计数器，`0` 对存量任务正好是"还没等过"。
 * 默认值必须与实体上的 `defaultValue = "0"` 一致，否则 Room 迁移后结构校验会报错。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `cloud_confirmed_scope` TEXT")
        db.execSQL(
            "ALTER TABLE `processing_jobs` ADD COLUMN `wait_rounds` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "UPDATE `records` SET `cloud_confirmed_scope` = 'RAW_TEXT' " +
                "WHERE `cloud_confirmed_revision` IS NOT NULL",
        )
    }
}

/**
 * 4 → 5：网页抓取的独立确认（2026-10-23 复核 F-5，决策 D-5）。
 *
 * ## 为什么要单独一列，不复用 `cloud_confirmed_revision`
 * 抓取网页是"向源站取页面"，AI 摘要/向量是"把正文发到网关"，两件事的授权范围不同。
 * 复用一列会带来两个具体毛病：
 * 1. 用户只是想抓到正文，却顺带授权了将来的 AI 上传（语义污染）；
 * 2. 反过来更糟 —— 确认 AI 上传要求云端通路可用（开关打开 + 网关已配置），
 *    于是**默认安装包（无网关）里的敏感网页永远抓不下来**，界面还给不出一句真话。
 *
 * ## 可空、无默认值、不回填
 * `NULL` = "从未单独允许过"。存量记录一律保持 `NULL`：v1～v4 的确认都只针对云端上传，
 * 拿它当抓取授权等于替用户签字。用户下次打开这条记录才会看到"允许抓取此网页"。
 *
 * ## 只加一列，不建索引
 * 这一列只按 `id` 整行读出后判断，没有"按它筛选"的查询；加索引是纯开销。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `web_fetch_confirmed_revision` INTEGER")
    }
}

/**
 * 5 → 6：录音采集时间窗（2026-10-27，PRD v1.2 §4.2）。
 *
 * 三列都可空、无默认值、不回填：`NULL` 的语义是"这条记录不是录音，或产生于本版本之前"。
 * 用 `created_at` 回填开始/结束时间会伪造用户数据 —— 落库时间不等于按下录音的时间。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `capture_started_at` INTEGER")
        db.execSQL("ALTER TABLE `records` ADD COLUMN `capture_ended_at` INTEGER")
        db.execSQL("ALTER TABLE `records` ADD COLUMN `capture_duration_ms` INTEGER")
    }
}

/**
 * 6 → 7：合集成员关系（M20）。
 *
 * 这是本工程第一次为"新能力"**建表**而不是给 `records` 加列。表结构必须与
 * `CollectionMemberEntity` 完全一致：复合主键、两个外键（都 CASCADE）与两个索引，
 * 任何一处对不上，Room 都会在升级后第一次打开数据库时抛结构校验失败。
 *
 * ## 为什么外键是 CASCADE
 * - 删除合集 → 成员关系随之消失（成员原记录不受影响，因为是另一条 FK 的方向）；
 * - M15 物理清理成员 → 关系随之消失，避免指向不存在记录的死行。
 * 软删除只改 `is_deleted`，不触发级联，因此"已删除来源仍可在合集里展示"成立。
 *
 * ## 为什么 `snapshot_fingerprint` 可空且不回填
 * `NULL` 的语义是"还没生成过合集摘要"。存量数据库里根本没有合集记录，
 * 回填任何值都是在虚构一次生成历史。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collection_members` (
                `collection_id` TEXT NOT NULL,
                `record_id` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `added_at` INTEGER NOT NULL,
                `snapshot_fingerprint` TEXT,
                PRIMARY KEY(`collection_id`, `record_id`),
                FOREIGN KEY(`collection_id`) REFERENCES `records`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`record_id`) REFERENCES `records`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_members_record_id` " +
                "ON `collection_members` (`record_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_members_collection_id_position` " +
                "ON `collection_members` (`collection_id`, `position`)",
        )
    }
}

/**
 * 7 → 8：多附件识别（2026-10-29，多附件需求 R1–R3）。
 *
 * 三件事：
 * 1. `assets` 补原始文件名与选择顺序。`file_name` 可空（拿不到就是拿不到，不编造）；
 *    `order_index` 必须 `NOT NULL DEFAULT 0`，且与实体上的 `defaultValue = "0"` 一致。
 * 2. 建 `(record_id, order_index)` 索引——附件读取永远按记录 + 顺序，这是唯一的热路径。
 * 3. `derived_contents` 补 `asset_id` 并把唯一索引扩成三列。
 *
 * ## 为什么 `asset_id` 用空串而不是 NULL
 * SQLite 的唯一索引把每一条 NULL 都视为互不相等，记录级行若存 NULL，
 * 同一记录可以塞进两条 SUMMARY 而不违反约束。空串让「记录级一种类型一条」由数据库兜底，
 * 而不是只靠代码里的确定性 id。实体上的 `defaultValue = "''"` 与此处的 `DEFAULT ''` 对齐。
 *
 * ## 为什么不改写旧行
 * 老版本只有一条 OCR/转写结果，但代码从不记录它来自哪个附件，迁移时无从得知。
 * 旧行保持 `assetId = ""`，由下一次处理覆盖/清理；宁可多跑一次本地 OCR，
 * 也不在迁移里猜一个可能是错的附件 id。多附件记录的自动重跑见处理侧的一次性标记。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `assets` ADD COLUMN `file_name` TEXT")
        db.execSQL("ALTER TABLE `assets` ADD COLUMN `order_index` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_assets_record_id_order_index` " +
                "ON `assets` (`record_id`, `order_index`)",
        )

        db.execSQL("ALTER TABLE `derived_contents` ADD COLUMN `asset_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("DROP INDEX IF EXISTS `index_derived_contents_record_id_content_type`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_derived_contents_record_id_content_type_asset_id` " +
                "ON `derived_contents` (`record_id`, `content_type`, `asset_id`)",
        )
    }
}

/**
 * v8 → v9：新建 `search_history`（M21 搜索优化）。
 *
 * 只做加法：新增独立表，不触碰任何既有列。表结构必须与 [com.zhicun.app.data.local.entity.SearchHistoryEntity]
 * 完全一致（列顺序、NOT NULL、索引名），否则 Room 打开库时的结构校验会失败。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `search_history` (" +
                "`query_key` TEXT NOT NULL, " +
                "`query` TEXT NOT NULL, " +
                "`last_searched_at` INTEGER NOT NULL, " +
                "`search_count` INTEGER NOT NULL, " +
                "PRIMARY KEY(`query_key`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_search_history_last_searched_at` " +
                "ON `search_history` (`last_searched_at`)",
        )
    }
}

/**
 * v9 → v10：新建 `embedding_meta`（开源改造 P1）。
 *
 * ## 为什么是新建表而不是给 `derived_contents` 加列
 * 向量行已经存在且被 `(record_id, content_type, asset_id)` 唯一索引约束；
 * 给同一张表加"只对 EMBEDDING 有意义"的三列，会让摘要/转写行也带上一串 NULL 或空串，
 * 语义变浑、迁移也要改所有读路径。独立表按 `record_id` 一对一，级联删除，干净。
 *
 * ## 回填：把老向量"认领"回来
 * 老版本把模型名写在 `processor_version`（`embed/<model>`）里，迁移时按它回填，
 * `embed_dim = 0`（未知）、`embed_provider_url = ''`（未知来源）。
 * 不回填的话，升级后所有老向量都"没有身份证"，兼容性判断与设置页统计都会把它们
 * 当成"来源不同"，语义检索直接清空 —— 升级一次就丢功能，是最不能接受的回归。
 *
 * 只回填 `processor_version LIKE 'embed/%'` 的行：其它形状的行不是向量模块写的
 * （见 `EmbeddingRepositoryImpl.getByRecordId` 的判断），不该被认领。
 *
 * ## 列定义必须与 [com.zhicun.app.data.local.entity.EmbeddingMetaEntity] 完全一致
 * 外键（CASCADE）、主键、`embed_provider_url` 的 `DEFAULT ''` 任何一处不一致，
 * Room 都会在升级后第一次打开库时抛结构校验失败 —— 用户看到的是"更新后打不开"。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `embedding_meta` (
                `record_id` TEXT NOT NULL,
                `embed_model` TEXT NOT NULL,
                `embed_dim` INTEGER NOT NULL,
                `embed_provider_url` TEXT NOT NULL DEFAULT '',
                `embedded_at` INTEGER NOT NULL,
                PRIMARY KEY(`record_id`),
                FOREIGN KEY(`record_id`) REFERENCES `records`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `embedding_meta` " +
                "(`record_id`, `embed_model`, `embed_dim`, `embed_provider_url`, `embedded_at`) " +
                "SELECT `record_id`, substr(`processor_version`, 7), 0, '', `processed_at` " +
                "FROM `derived_contents` " +
                "WHERE `content_type` = 'EMBEDDING' AND `processor_version` LIKE 'embed/_%'",
        )
    }
}

/** 全部迁移，按起始版本升序；[com.zhicun.app.data.local.AppDatabaseFactory] 统一注册。 */
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
)
