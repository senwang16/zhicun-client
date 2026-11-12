package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType

/**
 * 派生内容仓储（M7 起使用）。
 *
 * 派生内容（OCR 文字、网页正文、摘要…）是**可重建**的：处理成功时覆盖同类型旧结果，
 * 处理失败时记录 `errorMessage`，但永远不写 `Record.rawText`。
 */
interface DerivedContentRepository {

    suspend fun getByRecordId(recordId: String): List<DerivedContent>

    /** 记录级派生内容（`assetId == null`）；附件级结果不走这个入口。 */
    suspend fun getByRecordAndType(recordId: String, type: DerivedType): DerivedContent?

    /**
     * 某类型的全部派生内容，排除已软删除的记录（M17 搜索重排用：标题命中信号）。
     * 标题每条记录至多一条，个人库规模下全量读取毫秒级；别用它读 `PAGE_TEXT`——
     * 那会把所有正文一次性拉进内存。
     */
    suspend fun getByType(type: DerivedType): List<DerivedContent>

    /** 同 `recordId + contentType + assetId` 只有一条：重复处理覆盖旧值，不堆积历史。 */
    suspend fun upsert(content: DerivedContent)

    /**
     * 删除某条记录里指定类型的派生内容（2026-10-26 原文编辑后使其失效）。
     *
     * 只删被原文变化推翻的那几类（摘要/标签/向量/标题），OCR、转写与网页正文
     * 来自附件或源站，与用户改字无关，必须保留。
     */
    suspend fun deleteByRecordAndTypes(recordId: String, types: Collection<DerivedType>)

    suspend fun deleteByIds(ids: Collection<String>)

    /** 删除某个附件的全部派生内容（删除单个附件时，结果已无来源）。 */
    suspend fun deleteByAssetId(assetId: String)

    /**
     * 多附件处理收尾：清掉"本记录该类型下已不属于任何当前附件"的行（含老格式 `asset_id=''`）。
     * [keepAssetIds] 为空时退化为删除该类型全部行。
     */
    suspend fun deleteAssetRowsNotIn(
        recordId: String,
        type: DerivedType,
        keepAssetIds: Collection<String>,
    )
}
