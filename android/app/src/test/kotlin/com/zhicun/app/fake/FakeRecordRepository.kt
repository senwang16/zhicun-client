package com.zhicun.app.fake

import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.RecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 手写的记录仓储假实现（不引 mock 框架）。
 *
 * 用假实现而不是 mock：`observeAll` 需要一个**能持续发射**的 Flow，
 * mock 的 `thenReturn(flowOf(...))` 只能返回一次性流，测不出"数据变化后 UI 状态跟着变"。
 * 假实现只实现接口，不做任何额外逻辑，不会掩盖被测代码里的问题。
 */
class FakeRecordRepository(
    records: List<Record> = emptyList(),
) : RecordRepository {

    val stored: MutableStateFlow<List<Record>> = MutableStateFlow(records)

    /** 置为非 null 时，[observeAll] 发射错误，用于测 Error 分支。 */
    var failWith: Throwable? = null

    /** 置为非 null 时，[save] 抛该异常，用于测"保存失败要保留输入"（M5）。 */
    var saveFailure: Throwable? = null

    /** 置为非 null 时，[setArchived] 抛该异常，用于测批量归档失败（M16）。 */
    var archiveFailure: Throwable? = null

    /** 置为非 null 时，批量 [setFavorite] 抛该异常，用于测批量收藏失败（2026-10-24）。 */
    var favoriteFailure: Throwable? = null

    /** 置为非 null 时，批量 [softDeleteMany] 抛该异常，用于测删除失败（2026-10-24）。 */
    var deleteFailure: Throwable? = null

    /**
     * [updateRawText] 读到当前记录之后、写入之前回调（2026-10-26 并发编辑测试用）。
     *
     * 真实世界里这条窗口会被后台任务或另一次编辑占用；没有钩子的话，
     * "版本已被推进就不该覆盖"这条守卫在测试里永远走不到。
     */
    var beforeUpdateRawText: (() -> Unit)? = null

    /**
     * 软删除后的回调（2026-10-24 删除流程测试用）。
     *
     * 删除用例同时依赖 [RecordRepository]（写标记）与 `MaintenanceRepository`（读待清理列表），
     * 而这两个假实现各有各的存储。没有这个钩子的话，用例"软删除"之后，清理那一侧看不到任何
     * 待清理记录，测试会一路绿灯但什么都没验证。
     */
    var onSoftDelete: ((List<String>) -> Unit)? = null

    /**
     * 聚合记录状态时读取的任务状态（QODER P2-1）。
     *
     * 真实实现用一条 SQL 直接读 `processing_jobs`；假实现拿不到任务表，由测试把
     * [FakeProcessingJobRepository] 接进来。默认空列表 = 没有任务。
     */
    var jobStatusProvider: (String) -> List<JobStatus> = { emptyList() }

    /** 详情页测试用：id → 记录。 */
    val byId: MutableMap<String, Record> = records.associateBy { it.id }.toMutableMap()

    /**
     * 合集实时敏感并集（M20）；null = 未接入合集关系，`gateRecord` 原样返回。
     *
     * 真实实现读 `collection_members` 现算并集，假实现没有那张表 ——
     * 测试必须显式给出"当前成员并集是什么"，否则"调度/重试/执行三处闸门都用了实时值"
     * 这条很难被验证。接 [FakeCollectionRepository] 的辅助函数见文件末尾。
     */
    var collectionSensitivityProvider: (suspend (String) -> SensitivityStatus)? = null

    override fun observeAll(): Flow<List<Record>> {
        val failure = failWith
        if (failure != null) {
            return flow { throw failure }
        }
        return stored
    }

    override suspend fun getById(id: String): Record? = byId[id]

    /**
     * 与 `RecordRepositoryImpl.gateRecord` 同语义：合集取**实时**成员并集，其余类型原样返回。
     *
     * 未接 [collectionSensitivityProvider] 时原样返回 —— 不能让假实现自己猜一个并集，
     * 否则测试会误以为"闸门用了实时值"，而生产实现可能根本没算。
     */
    override suspend fun gateRecord(record: Record): Record {
        if (record.type != RecordType.COLLECTION) return record
        val provider = collectionSensitivityProvider ?: return record
        return record.copy(sensitivityStatus = provider(record.id))
    }

    /**
     * 收件箱视图（M16）：未删除 + 未归档，置顶优先，其余按收集时间倒序。
     *
     * 过滤与排序**在这里也照样做一遍**，而不是直接把 `stored` 交出去 ——
     * 真实实现里这件事由 SQL 完成，如果假实现偷懒，"归档后记录仍在收件箱"
     * 这类 bug 就永远不会在测试里暴露。
     */
    override fun observeInbox(): Flow<List<Record>> {
        // `failWith` 表达的是"整个仓储坏了"，不是"某一个查询坏了"：
        // 只让 observeAll 失败会让"收件箱读库失败"这条用例假绿（GetRecordsUseCase 现在走的是 observeInbox）
        failWith?.let { failure -> return flow { throw failure } }
        return stored.map { list ->
            list.filterNot { it.isDeleted }
                .filter { it.archivedAt == null }
                .sortedWith(
                    compareByDescending<Record> { it.isPinned }.thenByDescending { it.createdAt },
                )
        }
    }

    override fun observeArchived(): Flow<List<Record>> {
        failWith?.let { failure -> return flow { throw failure } }
        return stored.map { list ->
            list.filterNot { it.isDeleted }
                .filter { it.archivedAt != null }
                .sortedByDescending { it.archivedAt }
        }
    }

    /**
     * 与 `RecordDao.searchArchivedBySubstring` 同语义：只命中已归档、未删除的记录。
     *
     * 假实现只覆盖记录自身字段（正文/标题/URL）的 `contains`：派生内容与标签
     * 存在别的假仓储里，`Record` 上没有 —— 它们的 LIKE 命中由
     * `RecordRepositoryImplTest` 对真 SQLite 验证，这里不假装能测。
     */
    override fun searchArchivedBySubstring(normalized: String, limit: Int): Flow<List<Record>> {
        if (normalized.isBlank() || limit <= 0) return flow { emit(emptyList()) }
        failWith?.let { failure -> return flow { throw failure } }
        return stored.map { list ->
            list.filterNot { it.isDeleted }
                .filter { it.archivedAt != null }
                .filter { record ->
                    listOfNotNull(record.rawText, record.sourceTitle, record.sourceUrl)
                        .any { it.contains(normalized, ignoreCase = true) }
                }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }
    }

    /**
     * 批量取（M10）：故意**不**保证与入参同序 —— 真实 SQL 的实现也不保证，
     * 用例必须自己按入参顺序重排，假实现不能替它把这个 bug 遮住。
     */
    override suspend fun getByIds(ids: List<String>): List<Record> {
        val wanted = ids.toSet()
        return stored.value.filter { it.id in wanted }
    }

    /** 详情页订阅用：返回能持续发射的 Flow（不是一次性 `flowOf`）。 */
    override fun observeById(id: String): Flow<Record?> = stored.map { list ->
        list.firstOrNull { it.id == id } ?: byId[id]
    }

    override suspend fun save(record: Record) {
        saveFailure?.let { throw it }
        byId[record.id] = record
        stored.value = listOf(record) + stored.value
    }

    override suspend fun update(record: Record) {
        byId[record.id] = record
        stored.value = stored.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun softDelete(id: String, updatedAt: Long) {
        stored.value = stored.value.filterNot { it.id == id }
        byId.remove(id)
        onSoftDelete?.invoke(listOf(id))
    }

    /**
     * 返回值按"真的存在过的 id"算，不能用 `ids.size` 糊过去：
     * 生产实现返回的是 SQL 的受影响行数，删除报告里"删成了几条"就靠它。
     */
    override suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int {
        deleteFailure?.let { throw it }
        val existing = ids.filter { it in byId }
        existing.forEach { byId.remove(it) }
        val targets = existing.toSet()
        stored.value = stored.value.filterNot { it.id in targets }
        onSoftDelete?.invoke(existing)
        return existing.size
    }

    /**
     * 注意：`stored` 与 `byId` 必须同步更新。
     * 曾经只改 `stored`，导致断言 `byId[...].processingStatus` 永远读到旧值，
     * 看上去像"生产代码没回写状态"，实际是假实现漏了一路。
     */
    override suspend fun updateProcessingStatus(id: String, status: ProcessingStatus, updatedAt: Long) {
        stored.value = stored.value.map {
            if (it.id == id) it.copy(processingStatus = status, updatedAt = updatedAt) else it
        }
        byId[id]?.let { byId[id] = it.copy(processingStatus = status, updatedAt = updatedAt) }
    }

    /** 与 `RecordDao.updateType` 同语义：只改 type / updatedAt，已删除记录不写。 */
    override suspend fun updateType(id: String, type: RecordType, updatedAt: Long): Boolean =
        mutate(id) { it.copy(type = type, updatedAt = updatedAt) }

    /**
     * 与 `RecordDao.updateRawText` 同语义：版本必须匹配、递增版本、作废三份确认。
     *
     * 必须在假实现里照抄版本校验：如果图省事无条件写入，"两次编辑并发时后者覆盖前者"
     * 这个真实风险就永远不会在测试里暴露。
     */
    override suspend fun updateRawText(
        id: String,
        rawText: String?,
        sensitivityStatus: SensitivityStatus,
        expectedRevision: Int,
        updatedAt: Long,
    ): Boolean {
        beforeUpdateRawText?.invoke()
        val current = stored.value.firstOrNull { it.id == id } ?: byId[id] ?: return false
        if (current.isDeleted || current.recordRevision != expectedRevision) return false
        val updated = current.copy(
            rawText = rawText,
            recordRevision = current.recordRevision + 1,
            sensitivityStatus = sensitivityStatus,
            cloudConfirmedRevision = null,
            cloudConfirmedScope = emptySet(),
            webFetchConfirmedRevision = null,
            updatedAt = updatedAt,
        )
        stored.value = stored.value.map { if (it.id == id) updated else it }
        byId[id] = updated
        return true
    }

    /**
     * 与 `RecordDao.confirmCloudProcessing` 同语义：**只**写确认四列（复核 F-8）。
     *
     * 特意不做成 `copy(整行)`：真实实现若退化成整行回写，假实现照抄整行的话
     * "并发升级被覆盖"这个 bug 在测试里永远看不见。
     */
    override suspend fun confirmCloudProcessing(
        id: String,
        revision: Int,
        scope: Set<ContentPart>,
        status: ProcessingStatus,
        updatedAt: Long,
    ): Boolean {
        val current = stored.value.firstOrNull { it.id == id } ?: byId[id] ?: return false
        val updated = current.copy(
            cloudConfirmedRevision = revision,
            cloudConfirmedScope = scope,
            processingStatus = status,
            updatedAt = updatedAt,
        )
        stored.value = stored.value.map { if (it.id == id) updated else it }
        byId[id] = updated
        return true
    }

    /**
     * 与 `RecordDao.confirmWebFetch` 同语义：**只**写抓取确认列与 `updatedAt`（复核 F-5）。
     *
     * 与上面的云端确认一样只改列、不整行回写，也不动 `processingStatus` ——
     * 真实实现若顺手改了状态，假实现照抄会把"允许抓取后一直转圈"的 bug 藏起来。
     */
    override suspend fun confirmWebFetch(id: String, revision: Int, updatedAt: Long): Boolean {
        val current = stored.value.firstOrNull { it.id == id } ?: byId[id] ?: return false
        val updated = current.copy(webFetchConfirmedRevision = revision, updatedAt = updatedAt)
        stored.value = stored.value.map { if (it.id == id) updated else it }
        byId[id] = updated
        return true
    }

    /** 与 `RecordDao.idsByProcessingStatus` 同语义：未删除 + 指定状态。 */
    override suspend fun idsByProcessingStatus(status: ProcessingStatus): List<String> =
        stored.value.filter { !it.isDeleted && it.processingStatus == status }.map { it.id }

    /**
     * 与 `RecordDao.findIdByNormalizedText` 同语义：未删除 + 正文归一化后完全相同，取最新一条。
     *
     * 必须照抄归一化规则（换行统一 + 去首尾空白）与"跨来源"：假实现若图省事用
     * `startsWith`、忽略来源或忽略换行差异，真实 SQL 与用例之间的偏差就永远测不出来。
     */
    override suspend fun findDuplicate(text: String): String? {
        val key = normalizeForDedup(text)
        return stored.value
            .filter {
                !it.isDeleted && it.rawText != null &&
                    normalizeForDedup(it.rawText) == key
            }
            .maxByOrNull { it.createdAt }
            ?.id
    }

    private fun normalizeForDedup(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    /**
     * 与 `RecordDao.recomputeProcessingStatus` 同优先级的聚合（QODER P2-1）。
     *
     * 必须在假实现里照抄一遍规则：如果这里图省事直接写 DONE，"
     * 摘要失败但向量成功"这类只会在真实 SQL 里出现的问题就永远不会被测出来。
     */
    override suspend fun recomputeProcessingStatus(id: String, cloudActive: Boolean, updatedAt: Long) {
        val statuses = jobStatusProvider(id)
        val status = when {
            JobStatus.RUNNING in statuses -> ProcessingStatus.PROCESSING
            JobStatus.QUEUED in statuses -> ProcessingStatus.PENDING
            JobStatus.FAILED in statuses && JobStatus.DONE in statuses -> ProcessingStatus.PARTIAL
            JobStatus.FAILED in statuses -> ProcessingStatus.FAILED
            JobStatus.CANCELLED in statuses -> ProcessingStatus.LOCAL_ONLY
            statuses.isNotEmpty() && cloudActive -> ProcessingStatus.DONE
            else -> ProcessingStatus.LOCAL_ONLY
        }
        updateProcessingStatus(id, status, updatedAt)
    }

    /**
     * 与 `RecordDao.elevateSensitivity` 同语义：只升级，不洗白，并作废旧确认。
     *
     * 已经是 `CONFIRMED_SENSITIVE` 的行保持原值 —— 真实 SQL 用 CASE 做到了这一点，
     * 假实现必须照抄，否则"敏感升级把已确认风险降级"这种 bug 在测试里永远看不见
     * （2026-10-23 审查 R-9）。
     */
    override suspend fun elevateSensitivity(id: String, updatedAt: Long) {
        val elevated: (Record) -> Record = {
            it.copy(
                sensitivityStatus = if (it.sensitivityStatus == SensitivityStatus.CONFIRMED_SENSITIVE) {
                    SensitivityStatus.CONFIRMED_SENSITIVE
                } else {
                    SensitivityStatus.SUSPECTED
                },
                cloudConfirmedRevision = null,
                // 确认范围与版本号同生同灭（R-2）：只清一个会留下"看着还确认过"的死数据
                cloudConfirmedScope = emptySet(),
                updatedAt = updatedAt,
            )
        }
        stored.value = stored.value.map { if (it.id == id) elevated(it) else it }
        byId[id]?.let { byId[id] = elevated(it) }
    }

    // ------------------------------------------------------------ 整理（M16）

    override suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long): Boolean =
        mutate(id) { it.copy(isFavorite = favorite, updatedAt = updatedAt) }

    /** 与 `RecordDao.setFavoriteBatch` 同语义：批量、已删除的不算。 */
    override suspend fun setFavorite(ids: List<String>, favorite: Boolean, updatedAt: Long): Int {
        favoriteFailure?.let { throw it }
        if (ids.isEmpty()) return 0
        var affected = 0
        ids.forEach { id ->
            if (mutate(id) { it.copy(isFavorite = favorite, updatedAt = updatedAt) }) affected++
        }
        return affected
    }

    override suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Boolean =
        mutate(id) { it.copy(isPinned = pinned, updatedAt = updatedAt) }

    override suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long): Int {
        archiveFailure?.let { throw it }
        if (ids.isEmpty()) return 0
        var affected = 0
        ids.forEach { id ->
            if (mutate(id) { it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }) affected++
        }
        return affected
    }

    /**
     * 两路视图同步改：`stored` 管列表，`byId` 管详情。
     * 只改一路会让断言读到旧值，看上去像生产代码没回写（M13 踩过）。
     *
     * @return false = 记录不存在或已删除（与真实实现的 `is_deleted = 0` 守卫一致）
     */
    private fun mutate(id: String, transform: (Record) -> Record): Boolean {
        val current = stored.value.firstOrNull { it.id == id } ?: byId[id] ?: return false
        if (current.isDeleted) return false
        val updated = transform(current)
        stored.value = stored.value.map { if (it.id == id) updated else it }
        byId[id] = updated
        return true
    }
}

/** 让假记录仓储能读到假任务表，用于验证状态聚合（QODER P2-1）。 */
fun FakeRecordRepository.aggregateJobsFrom(
    jobs: FakeProcessingJobRepository,
): FakeRecordRepository = apply {
    jobStatusProvider = { recordId ->
        jobs.jobs.values.filter { it.recordId == recordId }.map { it.status }
    }
}

/** 让 `gateRecord` 读到合集成员关系，用于验证合集闸门用的是实时并集（M20）。 */
fun FakeRecordRepository.gateFrom(
    collections: FakeCollectionRepository,
): FakeRecordRepository = apply {
    collectionSensitivityProvider = { collectionId -> collections.effectiveSensitivity(collectionId) }
}
