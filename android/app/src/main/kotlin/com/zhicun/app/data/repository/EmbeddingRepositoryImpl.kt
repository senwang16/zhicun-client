package com.zhicun.app.data.repository

import com.zhicun.app.data.local.dao.DerivedContentDao
import com.zhicun.app.data.local.dao.EmbeddingMetaDao
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.EmbeddingMetaEntity
import com.zhicun.app.data.worker.DerivedContentKeys
import com.zhicun.app.domain.ai.Embedding
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.EmbeddingSpace
import com.zhicun.app.domain.repository.StoredEmbedding
import com.zhicun.app.domain.search.VectorMath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EmbeddingRepository] 的 Room 实现（M12；v10 起读写 `embedding_meta`）。
 *
 * ## 编码方式
 * 向量以 `Base64(小端 float32)` 存在 `derived_contents.content` 里，
 * 模型标识存在 `processor_version`（形如 `embed/<model>`）。
 * 维度 / 供应商 / 写入时间写进 `embedding_meta`（v10 新增）。
 *
 * 为什么模型标识仍然留在 `processor_version`：它是历史约定，且与"派生内容由哪条链路产生"
 * 是同一件事（摘要也有自己的版本号）。改成只存 meta 表会让"读一条向量"必须 join 两张表，
 * 而现在只有"判断可比性"才需要 join。
 *
 * ## 坏行的处理
 * 解码失败（库被改坏、维度超上限）时**跳过该条并继续**：一条坏向量不该让
 * 整个语义检索不可用。这里不打日志 —— data 层只负责"能不能用"，
 * 是否值得留痕由调用方决定（`SearchRecordsUseCase` 会把降级原因带回界面与日志）。
 */
@Singleton
class EmbeddingRepositoryImpl @Inject constructor(
    private val derivedContentDao: DerivedContentDao,
    private val embeddingMetaDao: EmbeddingMetaDao,
) : EmbeddingRepository {

    override suspend fun upsert(
        recordId: String,
        embedding: Embedding,
        now: Long,
        providerKey: String,
    ) {
        if (embedding.isEmpty) return

        derivedContentDao.upsert(
            DerivedContentEntity(
                // 主键固定为 recordId:EMBEDDING —— 与 ARCHITECTURE §4.3 的派生内容约定一致，
                // 重复处理会 REPLACE 同一行，天然幂等
                id = DerivedContentKeys.idFor(recordId, DerivedType.EMBEDDING),
                recordId = recordId,
                contentType = DerivedType.EMBEDDING,
                content = VectorMath.encode(embedding.vector),
                processorVersion = processorVersionOf(embedding.model),
                processedAt = now,
                errorMessage = null,
            ),
        )
        // 向量先写、meta 后写：进程在两次写之间被杀时，留下的是"有向量、没身份证"，
        // 兼容性判断把缺失的 meta 当通配（可能多召回几条），而不是"这些向量直接消失"。
        embeddingMetaDao.upsert(
            EmbeddingMetaEntity(
                recordId = recordId,
                embedModel = embedding.model,
                embedDim = embedding.dimensions,
                embedProviderUrl = providerKey,
                embeddedAt = now,
            ),
        )
    }

    override suspend fun getAll(model: String, providerKey: String): List<StoredEmbedding> {
        val expectedVersion = processorVersionOf(model)
        return derivedContentDao
            .getSearchableEmbeddings(DerivedType.EMBEDDING, expectedVersion, providerKey)
            .asSequence()
            .mapNotNull { entity -> toStored(entity, model, providerKey) }
            .toList()
    }

    override suspend fun getByRecordId(recordId: String): StoredEmbedding? {
        val entity = derivedContentDao.getByRecordIdAndType(recordId, DerivedType.EMBEDDING)
            ?: return null
        // processorVersion 形如 "embed/<model>"；前缀丢失说明这行不是本模块写的，按坏数据处理
        val model = entity.processorVersion.removePrefix(VERSION_PREFIX)
            .takeIf { it != entity.processorVersion && it.isNotBlank() }
            ?: return null
        val meta = embeddingMetaDao.getByRecordId(recordId)
        return toStored(entity, model, meta?.embedProviderUrl.orEmpty())
    }

    private fun toStored(
        entity: DerivedContentEntity,
        model: String,
        providerKey: String,
    ): StoredEmbedding? {
        val vector = VectorMath.decode(entity.content) ?: return null
        return StoredEmbedding(
            recordId = entity.recordId,
            model = model,
            vector = vector,
            providerKey = providerKey,
        )
    }

    override suspend fun countIncompatible(model: String, providerKey: String): Int =
        derivedContentDao.countIncompatibleEmbeddings(
            type = DerivedType.EMBEDDING,
            expectedVersion = processorVersionOf(model),
            providerKey = providerKey,
        )

    override suspend fun findIncompatibleRecordIds(
        model: String,
        providerKey: String,
        limit: Int,
    ): List<String> = derivedContentDao.findIncompatibleEmbeddingRecordIds(
        type = DerivedType.EMBEDDING,
        expectedVersion = processorVersionOf(model),
        providerKey = providerKey,
        limit = limit,
    )

    override suspend fun latestSpace(): EmbeddingSpace? = embeddingMetaDao.getLatest()?.let { meta ->
        EmbeddingSpace(model = meta.embedModel, providerKey = meta.embedProviderUrl)
    }

    private fun processorVersionOf(model: String): String = "$VERSION_PREFIX$model"

    private companion object {
        const val VERSION_PREFIX = "embed/"
    }
}