package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.CollectionMemberInput
import com.zhicun.app.domain.ai.Embedder
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.EmbeddingRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 语义向量（M12）：拼正文 → 网关 embed → 存 `derived_contents`（`EMBEDDING`）。
 *
 * ## 为什么"没向量"不算失败
 * 摘要那一路的经验在这里同样成立（见 `SummarizeWorker`）：短文本、纯符号、上游 OCR 失败
 * 都可能拿不到可向量化的文字。若判失败，记录会挂上"处理失败"——而记录本身完全正常，
 * 只是这条记录进不了语义检索而已。因此：
 * - 上游正文任务还在排队/运行 → **可重试**（等它跑完就有正文）；
 * - 上游已终态、确实没有文字 → **成功但什么都不写**（等价于"这条不参与语义检索"）；
 * - 网关返回空向量 → 同上，成功但不写。
 *
 * 记录状态由 `ProcessingJobExecutor` 按时任务集合聚合（2026-10-23 起），EMBED 判失败
 * 会让聚合结果变成 FAILED/PARTIAL，因此"没有正文"仍按成功处理。
 *
 * ## 幂等
 * 两层：`ProcessingJobExecutor` 遇到 DONE/CANCELLED 直接跳过（所以重复调度不会重复调用
 * 网关）；即使真的重跑，落库主键固定为 `recordId:EMBEDDING`，覆盖同一行而不是新增一行。
 */
@HiltWorker
class EmbedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
    private val derivedContentRepository: DerivedContentRepository,
    private val collectionRepository: CollectionRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val jobRepository: ProcessingJobRepository,
    private val embedder: Embedder,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        // 与摘要同一条门禁：上传不可撤销，补投/重试等路径都可能绕过调度侧判断
        CloudUploadGate.blockReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
            record = record,
        )?.let { reason ->
            return ProcessorResult.Blocked(reason)
        }

        val isCollection = record.type == RecordType.COLLECTION
        // 与 SummarizeWorker 同一条判据（2026-11-05 用户实测）：上游还在产正文就先等，
        // **不看拼出来的正文空不空**。否则"图片 + 一句备注"会拿那句备注算向量，
        // 整页 OCR 文字永远进不来 —— 而这种错比摘要空更难发现：检索只是悄悄地不准。
        val pending = if (isCollection) {
            pendingCollectionUpstream(record.id)
        } else {
            pendingUpstreamJob(record.id)
        }
        if (pending != null) {
            // 等待不消耗重试次数，理由同 SummarizeWorker（QODER P2-2）
            return ProcessorResult.WaitUpstream("等待上游任务 $pending 产出正文")
        }

        val derived = derivedContentRepository.getByRecordId(record.id)
        val input = if (isCollection) {
            ProcessingInputText.collectionEmbeddingInput(
                collection = record,
                descriptionDerived = derived,
                members = collectionMemberInputs(record.id),
            )
        } else {
            ProcessingInputText.embeddingInput(record = record, derived = derived)
        }

        if (input.text.isBlank()) {
            // 上游已终态：确实没有可向量化的文字，跳过不算失败
            Log.i(TAG, "没有可向量化的正文，跳过 recordId=${record.id}")
            return ProcessorResult.Success
        }

        // 同摘要：外发前用新鲜记录再判一次闸（R-6 TOCTOU），并校验这一次用到的内容块都在确认范围内（R-2）
        executor.recheckBeforeUpload(record.id, input.parts)?.let { return ProcessorResult.Blocked(it) }

        val embedding = embedder.embed(input.text.take(MAX_INPUT_CHARS))
        if (embedding.isEmpty) {
            Log.w(TAG, "网关未返回向量，跳过 recordId=${record.id} model=${embedding.model}")
            return ProcessorResult.Success
        }

        embeddingRepository.upsert(record.id, embedding, System.currentTimeMillis(), embedder.providerKey)
        Log.i(
            TAG,
            "向量已写入 recordId=${record.id} 维度=${embedding.dimensions} model=${embedding.model}",
        )
        return ProcessorResult.Success
    }

    /** 合集成员（含已删除）→ 摘要输入所需的记录 + 派生内容。 */
    private suspend fun collectionMemberInputs(collectionId: String): List<CollectionMemberInput> =
        collectionRepository.getMembersWithRecords(collectionId).map { member ->
            CollectionMemberInput(
                record = member.record,
                derived = derivedContentRepository.getByRecordId(member.record.id),
            )
        }

    /**
     * 合集是否还有未删除成员的上游任务在跑。
     *
     * 合集自己不产正文，只查它自己的任务表永远是"没有上游"，会把"成员 OCR 还在跑"
     * 误判成"确实没有可向量化的文字"。
     */
    private suspend fun pendingCollectionUpstream(collectionId: String): String? {
        collectionRepository.getMembersWithRecords(collectionId)
            .filterNot { it.record.isDeleted }
            .forEach { member ->
                pendingUpstreamJob(member.record.id)?.let { return it }
            }
        return null
    }

    /** 上游是否还有会产生正文的任务在跑；返回任务名便于写进错误原因。 */
    private suspend fun pendingUpstreamJob(recordId: String): String? =
        UPSTREAM_JOBS.firstOrNull { jobType ->
            val job = jobRepository.getByRecordAndType(recordId, jobType) ?: return@firstOrNull false
            job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING
        }?.name

    companion object {
        /** 送去向量化的正文上限：与摘要取同一量级，再长对语义表达没有边际收益。 */
        const val MAX_INPUT_CHARS: Int = 4_000

        /** 会产生正文的上游任务，按优先级排列（与 `SummarizeWorker` 保持一致）。 */
        val UPSTREAM_JOBS: List<JobType> = listOf(
            JobType.WEBPAGE_EXTRACT,
            JobType.OCR,
            JobType.TRANSCRIPTION,
        )

        private const val TAG = "EmbedWorker"
    }
}
