package com.zhicun.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.domain.model.DerivedType

/**
 * 派生内容表读写。
 *
 * 插入使用 REPLACE：同一 `record_id + content_type + asset_id` 只保留最新结果
 * （表上有唯一索引，重跑处理时覆盖旧值而不是堆积历史）。
 */
@Dao
interface DerivedContentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(content: DerivedContentEntity)

    /**
     * 一条记录的全部派生内容，按附件顺序分组返回。
     *
     * 排序跟着 `assets.order_index` 走：详情页按文件分段展示时，直接按这个顺序切段，
     * 界面不必再自己拿附件表去对齐。记录级行（asset_id = ''）join 不到附件，
     * `COALESCE` 把它们排在 0 组，与第一份附件相邻。
     */
    @Query(
        """
        SELECT d.* FROM derived_contents d
        LEFT JOIN assets a ON a.id = d.asset_id
        WHERE d.record_id = :recordId
        ORDER BY COALESCE(a.order_index, 0) ASC, d.content_type ASC, d.asset_id ASC
        """,
    )
    suspend fun getByRecordId(recordId: String): List<DerivedContentEntity>

    /**
     * 记录级派生内容（`asset_id = ''`）。
     *
     * 必须过滤空串：OCR / 转写改成附件级之后，同一条记录会有多条同类型行，
     * 不带条件的 `LIMIT 1` 取到哪条全看查询计划，`SummarizeWorker` 判断
     * "摘要是否已存在"会随机失效或误判。
     */
    @Query(
        "SELECT * FROM derived_contents " +
            "WHERE record_id = :recordId AND content_type = :type AND asset_id = '' LIMIT 1",
    )
    suspend fun getByRecordIdAndType(recordId: String, type: DerivedType): DerivedContentEntity?

    /** 某个附件自己的派生内容（v8：OCR / 转写按附件存）。 */
    @Query("SELECT * FROM derived_contents WHERE asset_id = :assetId")
    suspend fun getByAssetId(assetId: String): List<DerivedContentEntity>

    /**
     * 删除某条记录里指定类型的派生内容（2026-10-26 原文编辑后使其失效）。
     *
     * 用 DELETE 而不是写空串：界面靠"有没有这一行"区分"处理过但没内容"与"还没处理"，
     * 写空串会让刚被编辑作废的摘要显示成"没有可摘要的文字内容"，与真实状态不符。
     */
    @Query("DELETE FROM derived_contents WHERE record_id = :recordId AND content_type IN (:types)")
    suspend fun deleteByRecordAndTypes(recordId: String, types: List<DerivedType>)

    @Query("DELETE FROM derived_contents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** 删除某个附件的全部派生内容（删除单个附件时用）。 */
    @Query("DELETE FROM derived_contents WHERE asset_id = :assetId")
    suspend fun deleteByAssetId(assetId: String)

    /**
     * 删除"本记录该类型下、assetId 不在保留列表里"的行（多附件处理收尾）。
     *
     * 同时清掉老版本遗留的 `asset_id = ''` 行：它们不属于任何当前附件，
     * 留着会让详情页同时显示一份旧结果和一份新结果。
     * 调用方保证 [keepAssetIds] 非空（没有附件时不该跑这个任务）。
     */
    @Query(
        """
        DELETE FROM derived_contents
        WHERE record_id = :recordId AND content_type = :type AND asset_id NOT IN (:keepAssetIds)
        """,
    )
    suspend fun deleteAssetRowsNotIn(
        recordId: String,
        type: DerivedType,
        keepAssetIds: List<String>,
    )

    /**
     * 取某类型的全部派生内容，**只包含可被检索的记录**（M12 向量检索用）。
     *
     * 用 `INNER JOIN records` 而不是只查派生表：软删除的记录留在库里等清理，
     * 但绝不能出现在检索结果里（用户已经删掉的东西又冒出来是最扎眼的一类 bug）。
     *
     * 归档同样排除（2026-10-24 用户裁决）：归档的语义是"不想再看到它"，所以它既不该
     * 出现在搜索结果里，也不该被算作"相关记录"。归档是**可逆**的，因此这里只过滤查询、
     * 不删向量 —— 取消归档后不需要重新调模型算一遍。
     *
     * 方法名里的 `Searchable` 就是这个判据：**能不能出现在检索结果里**，
     * 而不是"数据库里有没有它"。叫 `ExcludingDeleted` 会漏掉归档这半边，名字就开始撒谎了。
     */
    @Query(
        """
        SELECT d.* FROM derived_contents d
        INNER JOIN records r ON r.id = d.record_id
        WHERE d.content_type = :type AND r.is_deleted = 0 AND r.archived_at IS NULL
        """,
    )
    suspend fun getAllByTypeSearchable(type: DerivedType): List<DerivedContentEntity>

    /**
     * 取"与当前向量空间兼容"的全部向量行（开源改造 P1）。
     *
     * 三条过滤缺一不可：
     * 1. `processor_version` 必须精确等于当前模型 —— 换模型后旧向量不可比；
     * 2. 记录未删除、未归档（与 [getAllByTypeSearchable] 同一条纪律）；
     * 3. **供应商来源兼容**：有 meta 且来源不同时排除；没有 meta（升级前的老行）
     *    或来源为空串时按通配放行，否则升级一次就把语义检索清空（见 `EmbeddingMetaEntity`）。
     *
     * `:providerKey = ''` 表示调用方自己也不知道当前来源（例如相关推荐只拿到了老向量），
     * 此时只按模型过滤 —— 传一个空值却被当成"来源必须为空"是灾难性的。
     */
    @Query(
        """
        SELECT d.* FROM derived_contents d
        INNER JOIN records r ON r.id = d.record_id
        LEFT JOIN embedding_meta m ON m.record_id = d.record_id
        WHERE d.content_type = :type AND r.is_deleted = 0 AND r.archived_at IS NULL
          AND d.processor_version = :expectedVersion
          AND (
            :providerKey = ''
            OR m.embed_provider_url IS NULL
            OR m.embed_provider_url = ''
            OR m.embed_provider_url = :providerKey
          )
        """,
    )
    suspend fun getSearchableEmbeddings(
        type: DerivedType,
        expectedVersion: String,
        providerKey: String,
    ): List<DerivedContentEntity>

    /**
     * 统计"向量与当前模型/供应商不兼容"的可检索记录数（设置页「向量状态」）。
     *
     * 与 [getSearchableEmbeddings] 的排除条件**必须互补**：两处各写一份判据，
     * 迟早出现"设置页说 3 条待重建、实际语义检索一条都没漏"的假警报（或反过来）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM derived_contents d
        INNER JOIN records r ON r.id = d.record_id
        LEFT JOIN embedding_meta m ON m.record_id = d.record_id
        WHERE d.content_type = :type AND r.is_deleted = 0 AND r.archived_at IS NULL
          AND (
            d.processor_version != :expectedVersion
            OR (
              :providerKey != ''
              AND m.embed_provider_url IS NOT NULL
              AND m.embed_provider_url != ''
              AND m.embed_provider_url != :providerKey
            )
          )
        """,
    )
    suspend fun countIncompatibleEmbeddings(
        type: DerivedType,
        expectedVersion: String,
        providerKey: String,
    ): Int

    /** 待重建的记录 id（批量重建用；按未删除、未归档的可检索集合取）。 */
    @Query(
        """
        SELECT d.record_id FROM derived_contents d
        INNER JOIN records r ON r.id = d.record_id
        LEFT JOIN embedding_meta m ON m.record_id = d.record_id
        WHERE d.content_type = :type AND r.is_deleted = 0 AND r.archived_at IS NULL
          AND (
            d.processor_version != :expectedVersion
            OR (
              :providerKey != ''
              AND m.embed_provider_url IS NOT NULL
              AND m.embed_provider_url != ''
              AND m.embed_provider_url != :providerKey
            )
          )
        ORDER BY d.processed_at ASC
        LIMIT :limit
        """,
    )
    suspend fun findIncompatibleEmbeddingRecordIds(
        type: DerivedType,
        expectedVersion: String,
        providerKey: String,
        limit: Int,
    ): List<String>
}
