package com.zhicun.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zhicun.app.data.local.converter.Converters
import com.zhicun.app.data.local.dao.AssetDao
import com.zhicun.app.data.local.dao.CollectionDao
import com.zhicun.app.data.local.dao.DerivedContentDao
import com.zhicun.app.data.local.dao.EmbeddingMetaDao
import com.zhicun.app.data.local.dao.MaintenanceDao
import com.zhicun.app.data.local.dao.ProcessingJobDao
import com.zhicun.app.data.local.dao.RecordDao
import com.zhicun.app.data.local.dao.RecordFtsDao
import com.zhicun.app.data.local.dao.SearchHistoryDao
import com.zhicun.app.data.local.dao.TagDao
import com.zhicun.app.data.local.entity.AssetEntity
import com.zhicun.app.data.local.entity.CollectionMemberEntity
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.EmbeddingMetaEntity
import com.zhicun.app.data.local.entity.ProcessingJobEntity
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.data.local.entity.RecordTagCrossRef
import com.zhicun.app.data.local.entity.SearchHistoryEntity
import com.zhicun.app.data.local.entity.TagEntity

/**
 * 智存本地数据库（ARCHITECTURE.md §6.3：Room 是搜索与内容的单一真源）。
 *
 * 全文检索虚表 `record_fts` 不在 `entities` 中 —— 它需要挂同步触发器、且后端是 FTS4
 * （Android 系统 SQLite 无 FTS5，见 [FtsSyncCallback] 注释），
 * 由 [FtsSyncCallback] 以原生 SQL 创建并保持与 records 表同步。
 *
 * **必须是单实例**：多实例会各自持有连接与回调状态。生产侧由 Hilt 以 @Singleton 提供，
 * 测试侧用 [AppDatabaseFactory.createInMemory]。
 *
 * ## 版本历史
 * - v1：M2 建库（records / assets / derived_contents / processing_jobs / tags / cross_ref）
 * - v2：M16 给 records 补 `is_favorite` / `is_pinned` / `archived_at`（整理能力）
 * - v3：给 records 补 `cloud_confirmed_revision`（敏感记录的云端上传确认绑定内容版本，
 *   2026-10-23 QODER P2-6；确认不再改写 `sensitivity_status`）
 * - v4：records 补 `cloud_confirmed_scope`（确认覆盖**哪些内容块**，R-2）；
 *   processing_jobs 补 `wait_rounds`（等上游的轮次上限，R-4）
 * - v5：records 补 `web_fetch_confirmed_revision`（网页抓取的**独立**确认：抓取只向源站
 *   取页面，与云端 AI 无关，2026-10-23 复核 F-5 / 决策 D-5）
 * - v6：records 补录音采集时间窗 `capture_started_at` / `capture_ended_at` /
 *   `capture_duration_ms`（PRD v1.2 §4.2：开始时间、结束时间、实际录音时长）
 * - v7：新建 `collection_members`（M20 合集：成员关系、顺序与生成快照；不复制成员内容）
 * - v8：多附件：`assets` 补 `file_name`（原始文件名，仅展示）/ `order_index`（选择顺序）；
 *   `derived_contents` 补 `asset_id`（空串 = 记录级），唯一索引扩到三列，
 *   让 OCR / 转写可以按附件各存一条
 * - v9：新建 `search_history`（M21 搜索优化：最近搜索，只存本机、只存查询串）
 * - v10：新建 `embedding_meta`（开源改造 P1：记录每条向量的模型、维度、供应商来源），
 *   老向量按 `processor_version` 回填"模型已知、来源未知"
 * - v11：`processing_jobs` 补 `output_language`（派发时的语言快照，可空）；
 *   `derived_contents` 补 `output_language`（默认 `zh`，历史 AI 结果不重生成，P11-07）
 *
 * **升版本必须同时写迁移**（`data/local/migration/Migrations.kt`）并注册进
 * [AppDatabaseFactory]。禁止 `fallbackToDestructiveMigration()` —— 那会让用户升级一次
 * 就丢掉全部记录，而本 App 没有云端备份。
 */
@Database(
    entities = [
        RecordEntity::class,
        AssetEntity::class,
        DerivedContentEntity::class,
        ProcessingJobEntity::class,
        TagEntity::class,
        RecordTagCrossRef::class,
        CollectionMemberEntity::class,
        SearchHistoryEntity::class,
        EmbeddingMetaEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao

    abstract fun assetDao(): AssetDao

    abstract fun derivedContentDao(): DerivedContentDao

    abstract fun processingJobDao(): ProcessingJobDao

    abstract fun tagDao(): TagDao

    /** M21：最近搜索（本机、去重、超限裁剪）。 */
    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun recordFtsDao(): RecordFtsDao

    /** M20：合集成员关系（顺序、快照指纹、反查所属合集）。 */
    abstract fun collectionDao(): CollectionDao

    /** M15：清理标记删除的记录（物理删除，集中在一处以免误调）。 */
    abstract fun maintenanceDao(): MaintenanceDao

    /** v10：向量元信息（模型/维度/供应商/时间），与派生内容表里的向量本体成对读写。 */
    abstract fun embeddingMetaDao(): EmbeddingMetaDao

    companion object {
        const val DATABASE_NAME: String = "zhicun.db"
    }
}
