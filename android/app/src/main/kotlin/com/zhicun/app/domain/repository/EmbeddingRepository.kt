package com.zhicun.app.domain.repository

import com.zhicun.app.domain.ai.Embedding

/**
 * 已落库的向量（附带它是由哪个模型、哪家供应商算出来的）。
 *
 * @param providerKey 产出向量的服务地址（官方网关 / 供应商 baseUrl）；
 *   空串 = 未知（升级前落库的老向量），兼容性判断按通配处理
 */
data class StoredEmbedding(
    val recordId: String,
    val model: String,
    val vector: FloatArray,
    val providerKey: String = "",
) {
    // data class + FloatArray：比较与哈希必须手写，否则比的是数组引用
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredEmbedding) return false
        return recordId == other.recordId &&
            model == other.model &&
            providerKey == other.providerKey &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = recordId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + providerKey.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

/**
 * 向量空间标识（模型 + 来源）。两项都相同，向量才可以互相比较。
 */
data class EmbeddingSpace(
    val model: String,
    val providerKey: String,
)

/**
 * 向量存储（M12，T-003 决策：复用 `derived_contents`，`contentType = EMBEDDING`）。
 *
 * ## 为什么没有单独的向量表
 * 派生内容表上的 `(record_id, content_type)` 唯一索引已经给出了两个必需性质：
 * 同一记录只有一行向量（**幂等**天然成立，重复处理覆盖而不是堆积）、
 * 主键是 `recordId:EMBEDDING`（与 ARCHITECTURE §4.3 的"派生内容主键"约定一致）。
 * 为此新建一张表和一次数据库迁移，换来的只有"列类型是 BLOB 而不是 TEXT"这一点好处，
 * 不值得。
 *
 * ## 为什么按 model 取而不是取全部
 * 不同模型（或同一模型的不同版本）产出的向量空间不同，维度可能刚好相同 ——
 * 混着算余弦会比"没有语义检索"更糟：结果看起来有排序，实际上是噪声。
 *
 * ## v10 起还要看 providerKey（开源改造 P1）
 * 同名模型在不同供应商/中转站上是不同部署。向量本体仍在派生内容表，
 * `embedding_meta` 只补"维度 / 来源 / 时间"这三项身份证信息。
 */
interface EmbeddingRepository {

    /**
     * 覆盖写：`recordId` 已有向量时替换（幂等）。
     *
     * @param providerKey 当前 Embedder 的来源标识；与向量一起落库，供之后判断可比性
     */
    suspend fun upsert(recordId: String, embedding: Embedding, now: Long, providerKey: String = "")

    /**
     * 取指定向量空间（模型 + 来源）的全部向量，**不含已软删除/已归档的记录**。
     *
     * 全量取回是刻意的：本模块用内存暴力余弦（见 `SemanticIndexRepositoryImpl`），
     * 个人知识库量级下一次搜索扫全表比维护索引更简单也更快。
     *
     * @param providerKey 空串 = 不限制来源（兼容没有来源信息的老向量）
     */
    suspend fun getAll(model: String, providerKey: String = ""): List<StoredEmbedding>

    /**
     * 取单条记录的向量（M18 相关推荐用：先知道"自己是谁"，才能找"谁跟它像"）。
     * 没有向量（未处理完 / 本地模式）返回 null —— 调用方据此隐藏推荐分区。
     */
    suspend fun getByRecordId(recordId: String): StoredEmbedding?

    /**
     * 使用**其它**向量空间、暂时进不了语义检索的可检索记录数（开源改造 P1）。
     *
     * 设置页据此提示"X 条需要重新处理"。与 [getAll] 的过滤条件互补：
     * 这里数出来的就是那边被排除掉的那些（判据只能有一份，否则会出现
     * "设置页说 3 条待重建、语义检索其实一条没漏"的假警报）。
     */
    suspend fun countIncompatible(model: String, providerKey: String): Int

    /**
     * 待重建的记录 id（批量重建用）。
     *
     * @param limit 一次最多取多少条：调用方按批处理，避免把上万条 id 读进内存
     */
    suspend fun findIncompatibleRecordIds(model: String, providerKey: String, limit: Int): List<String>

    /**
     * 最近一次写入的向量空间（模型 + 来源）。
     *
     * 官方模式下客户端不知道网关当前部署的是哪个模型（模型名由服务端决定、随响应回显），
     * 只能由"最近一次实际写入的向量"反推。没有任何向量时返回 null —— 此时谈不上重建。
     */
    suspend fun latestSpace(): EmbeddingSpace?
}