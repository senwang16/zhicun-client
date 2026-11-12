package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.diagnostic.DiagnosticLog
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.CollectionSensitivity
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** 创建合集的结果。拒绝原因交给界面翻译成文案（domain 层不引 Android 资源）。 */
sealed interface CreateCollectionResult {

    data class Created(val collectionId: String) : CreateCollectionResult

    data class Rejected(val reason: Reason) : CreateCollectionResult

    enum class Reason {
        /** 去重后不足两条 —— 合集的意义是"合并"，一条不需要新实体。 */
        TOO_FEW,

        /** 选中的记录里有已经被物理清理的（或 id 写错）。 */
        MISSING_RECORD,

        /** 已删除来源可以留在旧合集里，但不该被新合集选中。 */
        DELETED_MEMBER,

        /** v1 不支持嵌套合集：合集不能作为另一个合集的成员。 */
        NESTED_COLLECTION,
    }
}

/**
 * 把若干已有记录合并为一个合集（M20）。
 *
 * ## 不复制内容
 * 合集是一条新的 `Record`（`RecordType.COLLECTION`），成员关系写进 `collection_members`；
 * 成员的原记录、附件、派生内容一律不动。它自己的 `rawText` 是用户写的**合集说明**。
 *
 * ## 顺序与成员校验
 * - 成员按 `createdAt` 升序排列（先发生的事先读），置顶状态不影响合集内顺序；
 * - 少于两条、含已删除记录、含合集本身都拒绝 —— 这几种情况在界面上也做了禁用，
 *   但用例必须再判一次：多选状态可能来自几秒前的快照。
 *
 * ## 敏感状态
 * 合集在创建时就取未删除成员的并集，并一并扫描合集说明。这样列表徽标与门禁从第一条
 * 记录起就是准的；后续成员变化由 [CollectionRepository.refreshForMemberChange] 刷新。
 *
 * ## 调度失败不影响创建
 * 与 `SaveRecordUseCase` 同一条纪律：合集与成员关系已经落库，调度只是"接下来的事"。
 * 失败只留痕，不返回失败 —— 用户看到"创建失败"会以为成员关系也没写进去。
 */
class CreateCollectionUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val collectionRepository: CollectionRepository,
    private val scheduleProcessing: ScheduleProcessingUseCase,
    private val sensitiveDetector: SensitiveContentDetector,
    private val diagnosticLog: DiagnosticLog,
) {

    suspend operator fun invoke(
        memberRecordIds: List<String>,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
    ): CreateCollectionResult {
        val ids = memberRecordIds.distinct().filter { it.isNotBlank() }
        if (ids.size < MIN_MEMBERS) {
            return CreateCollectionResult.Rejected(CreateCollectionResult.Reason.TOO_FEW)
        }

        // 逐个 getById 而不是 getByIds：后者会过滤归档记录，而归档箱里的记录也可能
        // 被用户主动合并；同时"记录不存在"与"已删除"需要给出不同的拒绝原因。
        val members = ids.map { id ->
            recordRepository.getById(id)
                ?: return CreateCollectionResult.Rejected(
                    CreateCollectionResult.Reason.MISSING_RECORD,
                )
        }
        if (members.any { it.isDeleted }) {
            return CreateCollectionResult.Rejected(CreateCollectionResult.Reason.DELETED_MEMBER)
        }
        if (members.any { it.type == RecordType.COLLECTION }) {
            return CreateCollectionResult.Rejected(
                CreateCollectionResult.Reason.NESTED_COLLECTION,
            )
        }

        val ordered = members.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val nextDescription = description?.trim()?.takeIf { it.isNotEmpty() }
        val scanned = sensitiveDetector.toSensitivityStatus(sensitiveDetector.scan(nextDescription))
        val collection = Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.COLLECTION,
            captureSource = CaptureSource.MANUAL,
            rawText = nextDescription,
            sourceUrl = null,
            sourceTitle = null,
            sourceApp = null,
            createdAt = now,
            updatedAt = now,
            localStatus = LocalStatus.SAVED,
            processingStatus = ProcessingStatus.PENDING,
            sensitivityStatus = CollectionSensitivity.union(
                ordered.map { it.sensitivityStatus } + scanned,
            ),
            recordRevision = 1,
            isDeleted = false,
        )

        collectionRepository.create(
            collection = collection,
            memberRecordIds = ordered.map { it.id },
            now = now,
        )

        try {
            scheduleProcessing(collection)
        } catch (e: CancellationException) {
            // 保存协程被取消时必须原样抛出：把取消吞掉会让调用方以为创建完成、继续走 UI 流程
            throw e
        } catch (e: Exception) {
            diagnosticLog.w(TAG, "合集已创建，但后台任务调度失败 collectionId=${collection.id}", e)
        }

        return CreateCollectionResult.Created(collection.id)
    }

    private companion object {
        const val MIN_MEMBERS = 2
        const val TAG = "CreateCollection"
    }
}
