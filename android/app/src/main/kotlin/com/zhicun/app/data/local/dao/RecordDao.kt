package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import kotlinx.coroutines.flow.Flow

/**
 * 记录表读写。
 *
 * 注意 [getById] **不**过滤 `is_deleted`：详情页需要能取到已软删除的行以判断状态，
 * 列表查询统一走 [observeAll]（已过滤删除项）。
 */
@Dao
interface RecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: RecordEntity)

    @Update
    suspend fun update(record: RecordEntity)

    @Query("SELECT * FROM records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RecordEntity?

    /**
     * 单条记录流（M7）：详情页订阅它，后台把处理状态改成 DONE 时界面自动刷新。
     * 同样不过滤 `is_deleted`，理由见 [getById]。
     */
    @Query("SELECT * FROM records WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<RecordEntity?>

    /**
     * 全部未删除记录，按收集时间倒序。
     *
     * **包含已归档的**：导出（M15）与语义索引都依赖"用户存过的全部东西"。
     * 归档只影响收件箱视图，不影响数据归属 —— 见 [observeInbox]。
     */
    @Query("SELECT * FROM records WHERE is_deleted = 0 ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    /**
     * 收件箱列表（M16）：未删除 **且未归档**，置顶优先，其余按收集时间倒序。
     *
     * 排序放在 SQL 里而不是 ViewModel：列表可能上千条，内存排序会让首帧抖动，
     * 而且"置顶优先"是列表语义，不该由每个调用方各自实现一遍。
     */
    @Query(
        """
        SELECT * FROM records
        WHERE is_deleted = 0 AND archived_at IS NULL
        ORDER BY is_pinned DESC, created_at DESC
        """,
    )
    fun observeInbox(): Flow<List<RecordEntity>>

    /** 归档列表（M16）：未删除且已归档，最近归档的在前。 */
    @Query(
        """
        SELECT * FROM records
        WHERE is_deleted = 0 AND archived_at IS NOT NULL
        ORDER BY archived_at DESC
        """,
    )
    fun observeArchived(): Flow<List<RecordEntity>>

    /**
     * 收藏 / 取消收藏（M16）。
     *
     * 带 `is_deleted = 0` 守卫：已删除的记录不该被整理动作改动 —— 它随时会被 M15 清理掉，
     * 改它的标记没有意义，还会让"删除后又被改"成为难以解释的现象。
     *
     * @return 受影响行数（0 = 记录不存在或已删除）
     */
    @Query("UPDATE records SET is_favorite = :favorite, updated_at = :updatedAt WHERE id = :id AND is_deleted = 0")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long): Int

    /** 置顶 / 取消置顶（M16）。语义与守卫同上。 */
    @Query("UPDATE records SET is_pinned = :pinned, updated_at = :updatedAt WHERE id = :id AND is_deleted = 0")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Int

    /**
     * 批量收藏 / 取消收藏（2026-10-24：整理模式支持批量收藏）。
     *
     * 与 [setArchived] 同构：一条 `UPDATE ... IN (...)` 而不是逐条更新 ——
     * 多选十条会触发十次 Flow 发射，列表会闪十下。
     *
     * 与单条的 [setFavorite] 并存而不是让调用方循环：批量入口必须是一次写入，
     * 否则"十条里第三条失败"会留下半成功状态，而界面只能报一个笼统的失败。
     *
     * @return 受影响行数（已删除的记录不计入）
     */
    @Query("UPDATE records SET is_favorite = :favorite, updated_at = :updatedAt WHERE id IN (:ids) AND is_deleted = 0")
    suspend fun setFavoriteBatch(ids: List<String>, favorite: Boolean, updatedAt: Long): Int

    /**
     * 批量归档 / 取消归档（M16）。
     *
     * `archivedAt = null` 表示取消归档。批量走一条 `UPDATE ... IN (...)`，
     * 不逐条更新：多选归档十条记录时，逐条会触发十次 Flow 发射，列表会闪十下。
     *
     * @return 受影响行数
     */
    @Query("UPDATE records SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id IN (:ids) AND is_deleted = 0")
    suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long): Int

    @Query("UPDATE records SET is_deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    /**
     * 批量标记删除（2026-10-24 用户可见的删除功能）。
     *
     * 一条 `UPDATE ... IN (...)` 而不是逐条调用 [softDelete]：与 [setArchived] 同一个理由，
     * 逐条会触发 N 次 Flow 发射，删十条列表就闪十下。
     *
     * 不带 `is_deleted = 0` 守卫：重复标记是幂等的，而且写多一遍 `updated_at`
     * 不会造成任何可见后果 —— 但一定要在**清理之前**完成，清理的护栏依赖这个标记。
     *
     * @return 受影响行数（不存在的 id 不计入）
     */
    @Query("UPDATE records SET is_deleted = 1, updated_at = :updatedAt WHERE id IN (:ids)")
    suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int

    @Query("UPDATE records SET processing_status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProcessingStatus(id: String, status: ProcessingStatus, updatedAt: Long)

    /**
     * 重算记录类型（2026-10-29 删除单个附件）。
     *
     * 只写 `type` 与 `updated_at`：调用方手里是删除前的记录快照，
     * 整行回写会把并发的敏感升级 / 处理状态覆盖回旧值。
     *
     * @return 受影响行数；0 = 记录不存在或已删除
     */
    @Query("UPDATE records SET type = :type, updated_at = :updatedAt WHERE id = :id AND is_deleted = 0")
    suspend fun updateType(id: String, type: RecordType, updatedAt: Long): Int

    /**
     * 用户手动修改原文（2026-10-26 详情页编辑）。
     *
     * 只写内容相关的列，**不整行回写**：调用方手里的记录可能是几百毫秒前读到的，
     * 期间后台任务可能刚把状态或敏感标记改掉。
     *
     * 同时清空三份确认：确认绑定的是内容版本，版本号递增后旧确认本来就失效，
     * 但留着旧值会让后续排查看到"确认过第 N 版"的死数据（与 `elevateSensitivity` 同一处理）。
     *
     * `expectedRevision` 做乐观并发控制：两次编辑并发时只有一次能落库，
     * 另一次返回 0，调用方据此提示"内容已被修改"而不是静默覆盖别人的修改。
     *
     * FTS 索引由 `FtsSyncCallback` 的 `AFTER UPDATE OF raw_text` 触发器自动维护，
     * 这里不需要额外写索引。
     *
     * @return 受影响行数；0 = 记录不存在、已删除，或版本已被其它写入推进
     */
    @Query(
        """
        UPDATE records
        SET raw_text = :rawText,
            record_revision = record_revision + 1,
            sensitivity_status = :sensitivityStatus,
            cloud_confirmed_revision = NULL,
            cloud_confirmed_scope = NULL,
            web_fetch_confirmed_revision = NULL,
            updated_at = :updatedAt
        WHERE id = :id
          AND is_deleted = 0
          AND record_revision = :expectedRevision
        """,
    )
    suspend fun updateRawText(
        id: String,
        rawText: String?,
        sensitivityStatus: SensitivityStatus,
        expectedRevision: Int,
        updatedAt: Long,
    ): Int

    /**
     * 记录"用户确认可以上传"（2026-10-23 复核 F-8）。
     *
     * ## 为什么必须是列级 UPDATE，而不是整行 `@Update`
     * 确认与 [elevateSensitivity]（派生正文复查升敏感）是两条独立路径，可能并发：
     * 前者拿的是几毫秒前读到的记录快照，整行回写会把对方刚写的 `sensitivity_status`
     * 覆盖回旧值 —— 而这一列恰恰决定"要不要拦下上传"。覆盖的后果不是显示错乱，
     * 是**刚判定为敏感的内容被放行**，且不可撤销。列级 UPDATE 从结构上碰不到敏感列。
     *
     * ## 范围为空时写 NULL
     * `null` 表示"这次确认没有覆盖任何内容块"，与"从未确认"在数据上是同一形态
     * （版本号才是开关）。传空串会让 [com.zhicun.app.domain.model.ContentPart.decode]
     * 与"确认过但范围为空"这两种状态看起来不一样，白白多一种状态。
     *
     * @return 影响行数；0 = 记录已被删除（调用方据此不要再排任务）
     */
    @Query(
        """
        UPDATE records
        SET cloud_confirmed_revision = :revision,
            cloud_confirmed_scope = :scope,
            processing_status = :status,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun confirmCloudProcessing(
        id: String,
        revision: Int,
        scope: String?,
        status: ProcessingStatus,
        updatedAt: Long,
    ): Int

    /**
     * 记录"用户允许抓取这个网页"（2026-10-23 复核 F-5）。
     *
     * 与 [confirmCloudProcessing] 同一条理由用列级 UPDATE：这条路径同样可能与派生正文的
     * 敏感复查并发，整行回写会把 `sensitivity_status` 冲掉。
     *
     * **不改** `processing_status`：允许抓取只是放行一次出网请求，任务状态由任务表聚合
     * （[recomputeProcessingStatus]）说了算 —— 在这里顺手写"处理中"会在任务没能入队时
     * 留下一条永远转圈的记录。
     *
     * @return 影响行数；0 = 记录已被删除
     */
    @Query(
        """
        UPDATE records
        SET web_fetch_confirmed_revision = :revision,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun confirmWebFetch(id: String, revision: Int, updatedAt: Long): Int

    /**
     * 按任务表聚合出记录的处理状态（2026-10-23 QODER P2-1）。
     *
     * 一条记录并行挂多个任务（图片 = OCR + 摘要 + 向量），任何单个任务都无权
     * 独自决定整条记录的状态 —— 此前"每个任务各写各的"，最后完成的任务覆盖一切：
     * 摘要失败、向量成功，详情页就永远"正在生成摘要"。改为在 SQL 里对任务集合
     * 聚合：判断与更新在同一条语句内，SQLite 串行化写入，并发任务不会互相踩。
     *
     * 优先级从上到下（短路求值）：
     * 1. 有 RUNNING → PROCESSING；2. 有 QUEUED → PENDING（还在排队，处理中是瞬时态）；
     * 3. 有 FAILED 且有 DONE → PARTIAL（部分成果）；4. 只有 FAILED → FAILED；
     * 5. 有 CANCELLED（策略性不上传）→ LOCAL_ONLY；6. 全部 DONE 且云端当前可用
     * （[cloudActive]）→ DONE；7. 没有任务或云端不可用 → LOCAL_ONLY。
     */
    @Query(
        """
        UPDATE records SET
            processing_status = CASE
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id AND pj.status = 'RUNNING')
                    THEN 'PROCESSING'
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id AND pj.status = 'QUEUED')
                    THEN 'PENDING'
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id AND pj.status = 'FAILED')
                    AND EXISTS(SELECT 1 FROM processing_jobs pj
                               WHERE pj.record_id = records.id AND pj.status = 'DONE')
                    THEN 'PARTIAL'
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id AND pj.status = 'FAILED')
                    THEN 'FAILED'
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id AND pj.status = 'CANCELLED')
                    THEN 'LOCAL_ONLY'
                WHEN EXISTS(SELECT 1 FROM processing_jobs pj
                            WHERE pj.record_id = records.id) AND :cloudActive
                    THEN 'DONE'
                ELSE 'LOCAL_ONLY'
            END,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun recomputeProcessingStatus(id: String, cloudActive: Boolean, updatedAt: Long)

    /**
     * 敏感命中升级（2026-10-22 QODER P1-1）：派生正文（OCR / 转写 / 网页正文）复查命中后，
     * 把记录升为 `SUSPECTED`，同时**作废已有的云端确认**（新发现的风险使旧确认失效，
     * PRD §6.4"外发时针对实际外发内容再次检查"）。只动这两列，不覆盖整行其它字段 ——
     * 调用时拿到上下文的代码可能持有陈旧记录，整行覆盖会把并发改动冲掉。
     *
     * `sensitivity_status` 只会往"更严重"走：已经是 `CONFIRMED_SENSITIVE` 的行保持原值，
     * 不能被一次派生正文命中就"降级"成 `SUSPECTED`（2026-10-23 审查 R-9）。
     *
     * 确认范围（`cloud_confirmed_scope`）跟着版本号一起清掉（2026-10-23 R-2）：
     * 只清版本号会留下一份"看起来还确认过什么"的死数据；两者同生同灭，
     * 后续接手的人不必再判断"这行到底算不算确认过"。
     *
     * `WHERE` 里排除"已经无需改动"的行：任务多轮重试会反复走到这里，
     * 无差异的 UPDATE 也会触发 FTS 同步触发器整行重写索引（见 FtsSyncCallback）。
     */
    @Query(
        """
        UPDATE records
        SET sensitivity_status = CASE
                WHEN sensitivity_status = 'CONFIRMED_SENSITIVE' THEN 'CONFIRMED_SENSITIVE'
                ELSE 'SUSPECTED'
            END,
            cloud_confirmed_revision = NULL,
            cloud_confirmed_scope = NULL,
            updated_at = :updatedAt
        WHERE id = :id
          AND NOT (sensitivity_status IN ('SUSPECTED', 'CONFIRMED_SENSITIVE')
                   AND cloud_confirmed_revision IS NULL
                   AND cloud_confirmed_scope IS NULL)
        """,
    )
    suspend fun elevateSensitivity(id: String, updatedAt: Long)

    /**
     * 合集成员变化后刷新合集的敏感状态，并作废它的云端确认（M20）。
     *
     * 只写三列：合集自身的其它字段（说明、标题、整理状态）与这次变化无关，
     * 整行回写会覆盖并发的编辑。`web_fetch_confirmed_revision` 不在这里清 ——
     * 合集没有网页抓取任务，那一列对它没有意义。
     *
     * 敏感并集由调用方（`CollectionRepositoryImpl`）按未删除成员算出，
     * 这里只负责落库，避免在 SQL 里重复一遍"哪些成员参与"的判据。
     */
    @Query(
        """
        UPDATE records
        SET sensitivity_status = :status,
            cloud_confirmed_revision = NULL,
            cloud_confirmed_scope = NULL,
            updated_at = :updatedAt
        WHERE id = :id
          AND is_deleted = 0
        """,
    )
    suspend fun updateSensitivityAndClearConfirmation(
        id: String,
        status: SensitivityStatus,
        updatedAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM records WHERE is_deleted = 0")
    suspend fun countActive(): Int

    /**
     * 找一条正文归一化后与 [text] 完全一致的未删除记录（2026-10-27，PRD v1.2 §2.4）。
     *
     * ## 跨来源
     * 不再按 `capture_source` 过滤：无论那条记录来自分享、手动新建还是悬浮录音，
     * 只要正文一样就是重复。用户在别处存过同一段文字，再从剪贴板点一次，
     * 不该多出一条。
     *
     * ## 归一化只做两件事
     * 统一换行符（`\r\n` / `\r` → `\n`）与去首尾空白。SQL 的 `TRIM(X, Y)` 第二个参数
     * 是"要剥掉的字符集合"：空格 / 制表 / 换行之外还带上全角空格（U+3000），
     * 与 Kotlin `trim()` 在中国用户最常遇到的白空格上对齐。
     * **不**删除 URL 参数、**不**压缩中间空格：判据仍是"完全一样"，用 `=` 而不是 `LIKE`。
     *
     * 该表达式必须与 `ClipboardText.dedupKey` 保持一致，改一处必须改另一处。
     *
     * 已删除（`is_deleted = 1`）的记录不算：用户删掉它就是要它不存在，
     * 这时再复制一次同样的内容应当能重新收藏。
     *
     * @param text 已由 `ClipboardText.dedupKey` 归一化的正文（`RecordRepositoryImpl` 负责）
     * @return 命中的记录 id；`null` = 没有重复
     */
    @Query(
        """
        SELECT id FROM records
        WHERE is_deleted = 0
          AND raw_text IS NOT NULL
          AND TRIM(
                REPLACE(REPLACE(raw_text, char(13) || char(10), char(10)), char(13), char(10)),
                char(9) || char(10) || char(13) || char(32) || char(12288)
              ) = :text
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    suspend fun findIdByNormalizedText(text: String): String?

    /**
     * 按处理状态取记录 id（2026-10-23 审查 R-3：开关重开后要找回"仅本地"的记录补投）。
     *
     * 只返回 id：调用方紧接着用 `getById` 拿完整记录 —— 那时才判门禁。
     * 已删除的记录不返回：本地门禁放行也不该为一条删掉的记录上传内容。
     */
    @Query("SELECT id FROM records WHERE is_deleted = 0 AND processing_status = :status")
    suspend fun idsByProcessingStatus(status: ProcessingStatus): List<String>

    /**
     * 子串检索（M10）：命中 `raw_text` / `source_title` / `source_url`、派生内容 `content` 与标签名。
     *
     * 派生内容与标签都用 `EXISTS` 子查询而不是 `LEFT JOIN + DISTINCT`：
     * 一条记录可能有多条派生内容（OCR/转写/摘要）、多个标签，JOIN 会放大行数，
     * 而 `SELECT DISTINCT` 之后 SQLite 不允许 `ORDER BY` 未出现在结果列里的字段。
     *
     * `pattern` 由 [com.zhicun.app.domain.search.SearchQuery.likePattern] 生成（含两侧 `%`），
     * 转义字符固定为反斜杠，两处必须一起改。
     */
    @Query(
        """
        SELECT * FROM records AS r
        WHERE r.is_deleted = 0
          AND r.archived_at IS NULL
          AND (
            r.raw_text LIKE :pattern ESCAPE '\'
            OR r.source_title LIKE :pattern ESCAPE '\'
            OR r.source_url LIKE :pattern ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM derived_contents AS d
                WHERE d.record_id = r.id AND d.content LIKE :pattern ESCAPE '\'
            )
            OR EXISTS (
                SELECT 1 FROM record_tag_cross_ref AS x
                INNER JOIN tags AS t ON t.id = x.tag_id
                WHERE x.record_id = r.id AND t.name LIKE :pattern ESCAPE '\'
            )
          )
        ORDER BY r.created_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchBySubstring(pattern: String, limit: Int): List<RecordEntity>

    /**
     * 归档箱内搜索（2026-10-24）：只命中**已归档**记录，命中字段与 [searchBySubstring] 完全一致。
     *
     * ## 为什么是独立方法而不是给 [searchBySubstring] 加参数
     * 两者的过滤条件是互补的（`IS NULL` vs `IS NOT NULL`），加参数迟早会在某处把
     * 默认值传反，表现为"归档的记录出现在主搜索里"或"归档箱搜出未归档记录"。
     *
     * ## 为什么没有 FTS 路径
     * 归档记录被 FTS 触发器**刻意排除**在索引之外（`FtsSyncCallback`，归档退出检索的
     * 结构性保证）——所以这里只能 LIKE 全表扫描。归档箱是低频入口、量级是"个人归档"，
     * 几百条的全扫描在 SQLite 上是毫秒级，够用；为此把归档记录放回 FTS 索引，
     * 反而要给主搜索的每一条查询都补过滤，得不偿失。
     *
     * 返回 Flow：搜索结果保持活性 —— 用户在搜索中恢复/删除一条记录时，
     * 结果列表自动跟着变，不需要手动刷新。
     *
     * `pattern` 同样由 `SearchQuery.likePattern` 生成，`ESCAPE '\'` 两处必须一起改。
     */
    @Query(
        """
        SELECT * FROM records AS r
        WHERE r.is_deleted = 0
          AND r.archived_at IS NOT NULL
          AND (
            r.raw_text LIKE :pattern ESCAPE '\'
            OR r.source_title LIKE :pattern ESCAPE '\'
            OR r.source_url LIKE :pattern ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM derived_contents AS d
                WHERE d.record_id = r.id AND d.content LIKE :pattern ESCAPE '\'
            )
            OR EXISTS (
                SELECT 1 FROM record_tag_cross_ref AS x
                INNER JOIN tags AS t ON t.id = x.tag_id
                WHERE x.record_id = r.id AND t.name LIKE :pattern ESCAPE '\'
            )
          )
        ORDER BY r.created_at DESC
        LIMIT :limit
        """,
    )
    fun searchArchivedBySubstring(pattern: String, limit: Int): Flow<List<RecordEntity>>

    /**
     * 按 id 批量取（M10 搜索：先检索出 id，再一次性取实体）。
     *
     * 保持 `is_deleted = 0` **且 `archived_at IS NULL`**：检索用的是可能滞后的 id 列表，
     * 期间被软删除或被归档的记录不应再出现在结果里。
     *
     * 这两条与 FTS 触发器 / 向量召回的过滤是同一条判据的纵深防御：候选列表可能来自
     * 几毫秒前的快照，而用户完全可以在那一刻归档或删除。
     */
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND archived_at IS NULL AND id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<RecordEntity>
}
