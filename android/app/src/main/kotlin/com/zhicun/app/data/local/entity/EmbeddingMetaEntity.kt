package com.zhicun.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 向量元信息表（开源改造 P1，DB v10）。
 *
 * ## 为什么需要一张表，而不是继续复用 `processor_version`
 * `derived_contents.processor_version` 已经存了模型名（`embed/<model>`），
 * 但它回答不了另外三个问题：
 * - **维度**是多少（换模型后维度变了，旧向量仍能算余弦，只是算出噪声）；
 * - **哪来的**（同名模型在不同供应商/中转站上是不同部署，向量不可比）；
 * - **什么时候算的**（重建进度与"这条是不是刚换模型前算的"）。
 *
 * 向量本体仍然留在 `derived_contents`（T-003 决策），这张表只是它的"身份证"。
 * 一行对应一条记录（`record_id` 主键），与 `records` 级联删除。
 *
 * ## `embed_dim = 0` 表示"未知"
 * 迁移时无法得知历史向量的维度（老版本没记），填 0 而不是猜一个。
 * 0 只用于展示与排查，**不参与兼容性判断** —— 兼容性看模型与供应商。
 *
 * ## `embed_provider_url` 空串表示"未知来源"
 * 老向量没有来源信息。空串在兼容性判断里是**通配**：同名模型即视为可比，
 * 否则升级后所有老向量会一起被排除，语义检索直接清空 —— 那是比"可能混了一个中转站"
 * 更糟的结果。换供应商时新写入的向量都带来源，之后的比较才是严格的。
 */
@Entity(
    tableName = "embedding_meta",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EmbeddingMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,

    /** 产出向量的模型标识（`BAAI/bge-m3`、`text-embedding-3-small`）。 */
    @ColumnInfo(name = "embed_model")
    val embedModel: String,

    /** 向量维度；`0` = 未知（升级前落库的老向量）。 */
    @ColumnInfo(name = "embed_dim")
    val embedDim: Int,

    /** 产出向量的服务地址（官方网关地址 / 供应商 baseUrl）；空串 = 未知。 */
    @ColumnInfo(name = "embed_provider_url", defaultValue = "''")
    val embedProviderUrl: String,

    @ColumnInfo(name = "embedded_at")
    val embeddedAt: Long,
)