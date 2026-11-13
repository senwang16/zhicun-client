package com.zhicun.app.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.CollectionMemberInput
import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Enrichment
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.ai.SummaryPolicy
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.TagRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.AppLanguage
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessorResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 摘要 + 标签（M11）：本地拼正文 → 网关 enrich → 写 TITLE / SUMMARY / TAGS 派生内容 + 标签行。
 *
 * ## 为什么在执行时再看一次敏感状态
 * 调度侧（`ScheduleProcessingUseCase`）已经拦过"疑似敏感不排云端任务"。这里再挡一次是
 * **纵深防御**：任务可能来自启动补投（`RecoverPendingJobsUseCase`）、也可能来自用户点重试，
 * 这些路径不经过保存流程。上传是不可撤销的动作，宁可多判一次。
 *
 * ## 为什么"没有正文"分两种结论
 * - 上游任务（OCR / 网页提取 / 转写）还在排队或运行 → **等待上游**：30s 后再看，
 *   不消耗重试次数（转写单次可达 120 秒，按普通失败计数会耗尽预算）；
 * - 上游已经终态 → 再看**它到底是怎么结束的**（2026-11-05 审核 CN-02）：
 *   `FAILED` ⇒ 终态失败，让用户在任务表里看见；`DONE`/`CANCELLED` 或压根没有这条任务
 *   ⇒ 这条记录本来就没有正文，落空摘要 + 成功。
 *
 * **等待的判据是"上游还在跑"，不是"拼出来的正文空不空"**（2026-11-05 用户实测修正）。
 * 此前只在正文为空时才等，于是"图片 + 一句备注"的记录会立刻带着那句备注去问模型：
 * 整页 OCR 文字那时还没生成，模型如实返回空摘要，用户看到的就是"识别出满屏字，却什么都没整理"。
 *
 * 但"**确实没有正文**"不是失败：只挂了一个 PDF 的 `FILE` 记录、纯风景照（OCR 成功但零文字）、
 * 静音录音（转写成功但空文本）、超过 5 分钟按 `TranscriptionPolicy` 跳过的音频，
 * 都落一条空摘要 + `Success`，界面据此显示"没有可摘要的文字"。
 * 2026-10-24 之前这些被混成同一个 `Permanent` 失败：用户上传 PDF 后 `EMBED` 成功、
 * `SUMMARIZE` 永久失败，界面聚合出 `PARTIAL`，显示成"失败"。
 * 2026-11-05 之前判据又退回"记录类型静态查表"（IMAGE / VOICE 恒为'该有正文'），
 * 于是无字图片与静音录音同样永久挂红，与 `EmbedWorker` 对同一事实的结论相反。
 *
 * ## 空摘要什么时候算成功
 * **只有"这条记录本来就没有正文"时才算成功**（FILE 无文本层、无字图片、静音录音、合集成员全空）：
 * 此时落一条空摘要 + `Success`，界面据此显示"没有可摘要的文字"。
 *
 * 反之，**有正文却没拿到摘要 = 失败**（2026-11-05 线上回归）：那种情况下游要能看见、
 * 用户要能重跑。别把它改回 `Success` —— 详见 `perform()` 末尾的注释。
 */
@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    executor: ProcessingJobExecutor,
    private val derivedContentRepository: DerivedContentRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    private val jobRepository: ProcessingJobRepository,
    private val enricher: ContentEnricher,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
) : BaseProcessingWorker(appContext, params, executor) {

    override suspend fun perform(record: Record): ProcessorResult {
        // 调度侧已经拦过；这里再挡一次（M14 起同时覆盖"用户关掉了云端 AI"与网关未配置）
        CloudUploadGate.blockReason(
            cloudAiEnabled = aiSettings.cloudAiEnabled,
            gatewayConfigured = gatewaySettings.isConfigured,
            record = record,
        )?.let { reason ->
            // 不是"处理失败"，是"刻意不上传"：终态 CANCELLED 是为了不让它反复重试消耗流量与令牌
            return ProcessorResult.Blocked(reason)
        }

        val isCollection = record.type == RecordType.COLLECTION
        // 输出语言在派发时已快照到任务行（P11-07）：Worker 不再读偏好 ——
        // 否则"排完队之后用户切了语言"会让同一次处理在自动重试里换成另一种语言。
        val language = jobLanguage(record.id)
        // 上游还在产正文就先让出这一轮（2026-11-05 用户实测）：判据是"上游跑没跑完"，
        // 不是"拼出来的正文空不空"。否则"图片 + 一句备注"会立刻拿那句备注去问模型 ——
        // OCR 文字这时还没生成，模型只能如实返回空摘要，界面上就是"有文字却没整理"。
        // 等待不消耗重试次数，且受 `MAX_WAIT_ROUNDS` 约束，不会无限等下去。
        val pending = if (isCollection) {
            pendingCollectionUpstream(record.id)
        } else {
            pendingUpstreamJob(record.id)
        }
        if (pending != null) {
            // 等待不消耗重试次数：转写可能长达 120 秒，按普通失败计数必然耗尽 3 次预算（QODER P2-2）
            return ProcessorResult.WaitUpstream("等待上游任务 $pending 产出正文")
        }

        val derived = derivedContentRepository.getByRecordId(record.id)
        val input = if (isCollection) {
            ProcessingInputText.collectionSummaryInput(
                collection = record,
                descriptionDerived = derived,
                members = collectionMemberInputs(record.id),
            )
        } else {
            ProcessingInputText.summaryInput(record = record, derived = derived)
        }
        if (input.text.isBlank()) {
            // 上游已终态。两种可能必须分开（2026-11-05 审核 CN-02 / GL-01）：
            // ① 上游**真的失败**了（OCR / 网页提取 / 转写 FAILED）→ 终态失败，让它在任务表里显形；
            // ② 上游全部正常跑完，只是没产出文字（纯风景照、静音录音、超 5 分钟按策略不转写）
            //    → "这条记录本来就没有正文"，落空摘要 + 成功。
            //
            // 此前只看记录类型静态查表（IMAGE / VOICE 恒为"该有正文"），把 ② 也判成
            // `Permanent("没有可用于摘要的正文")`：无字图片与静音录音永久挂红，
            // 用户点多少次"重新生成"都是同一个结果，而 `EmbedWorker` 对同一事实判的是成功。
            val failedUpstream = upstreamFailure(record.id)
            return if (!isCollection && failedUpstream != null) {
                ProcessorResult.Permanent("没有可用于摘要的正文（上游任务 $failedUpstream 失败）")
            } else {
                // 本来就不会有正文（FILE / 成员全是空内容 / 上游跑完但没字）：落一条空摘要即可
                persistEmptySummary(record, language)
                markCollectionGenerated(record)
                ProcessorResult.Success
            }
        }

        // 读派生正文之后、发请求之前再判一次：兄弟任务可能刚把记录升为敏感，
        // 或者刚产出了一块用户还没确认过的内容（R-2 / R-6）
        executor.recheckBeforeUpload(record.id, input.parts)?.let { return ProcessorResult.Blocked(it) }

        val enrichment = enricher.enrich(input.text.take(MAX_INPUT_CHARS), language)
        persist(record, enrichment, language)
        markCollectionGenerated(record)
        if (enrichment.summary.isNullOrBlank() && SummaryPolicy.requiresSummary(input.text)) {
            // 有正文、两家上游却都没给出摘要（2026-11-05 线上）——**不能算成功**。
            //
            // 算成功会同时坏两件事：界面显示"没有可摘要的文字内容"（像是记录本来就没内容，
            // 实际有），而且 `RecordDetailDerived` 的 showRetry 只认 failed/blocked，
            // 于是用户连重试入口都没有，只能删掉重建。网关侧已改为"缺摘要就换下一家"，
            // 走到这里说明两家都失手了，如实报失败让用户能重跑。
            //
            // 但"短到不需要摘要"的正文（SummaryPolicy）例外：空是提示词允许的合法结果，
            // 判失败会让「买牛奶」这类备忘永久挂红、点多少次都一样。
            //
            // 已经写入的标题与标签保留：用户重试成功后会被覆盖，重试失败也不至于比之前更空。
            return ProcessorResult.Permanent("上游没有返回摘要")
        }
        return ProcessorResult.Success
    }

    /**
     * 派发时快照的输出语言（P11-07）。
     *
     * 历史行（v11 之前）没有该列值 → 按中文，与服务端"缺省 `zh`"和迁移回填保持一致；
     * 任务行缺失（理论上不会发生，执行器已先查过）同样回落到中文，不抛异常中断处理。
     */
    private suspend fun jobLanguage(recordId: String): AppLanguage =
        AppLanguage.fromCode(
            jobRepository.getByRecordAndType(recordId, JobType.SUMMARIZE)?.outputLanguage,
        )

    /** 合集的所有成员（含已删除）——是否参与由 `ProcessingInputText` 统一判断。 */
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
     * 合集自己不产正文，正文全来自成员。只查合集自己的任务表永远查不到东西，
     * 于是"成员 OCR 还在跑"会被误判成"上游已终态、可以落空摘要"。
     */
    private suspend fun pendingCollectionUpstream(collectionId: String): String? {
        collectionRepository.getMembersWithRecords(collectionId)
            .filterNot { it.record.isDeleted }
            .forEach { member ->
                pendingUpstreamJob(member.record.id)?.let { return it }
            }
        return null
    }

    /** 摘要成功后刷新成员快照：过期横幅随重新生成消失（已删除成员也写，见仓储 KDoc）。 */
    private suspend fun markCollectionGenerated(record: Record) {
        if (record.type != RecordType.COLLECTION) return
        collectionRepository.markGenerated(record.id, System.currentTimeMillis())
    }

    /**
     * 上游正文任务里**真的失败**的那个（没有则 null）。
     *
     * 与 [pendingUpstreamJob] 互补：那个回答"还在跑吗"，这个回答"跑完但没字，是失败还是本来就没字"。
     * 判据必须看**任务行的实际状态**，不能看记录类型静态查表（2026-11-05 审核 CN-02）：
     * 纯风景照的 OCR 会以 `Success` 收尾并写入空串，静音录音的转写同理，超 5 分钟按
     * `TranscriptionPolicy` 跳过的音频也是 `Success`。这些都属于"合法无文字"。
     *
     * 只认 `FAILED`：`CANCELLED` 是"按策略不处理"（云端关闭 / 疑似敏感），
     * 不是上游出错，同样不该让摘要挂红。
     */
    private suspend fun upstreamFailure(recordId: String): String? =
        UPSTREAM_JOBS.firstOrNull { jobType ->
            jobRepository.getByRecordAndType(recordId, jobType)?.status == JobStatus.FAILED
        }?.name

    /** 上游是否还有会产生正文的任务在跑；返回任务名便于写进错误原因。 */
    private suspend fun pendingUpstreamJob(recordId: String): String? =
        UPSTREAM_JOBS.firstOrNull { jobType ->
            val job = jobRepository.getByRecordAndType(recordId, jobType) ?: return@firstOrNull false
            job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING
        }?.name

    /**
     * 没有正文时落一条空摘要。
     *
     * 只在**还没有**摘要时写：这个分支在重试时也可能走到，覆盖已有摘要等于把用户看到的
     * 内容抹掉。空串而不是 null，是因为"有没有这一行"就是界面区分
     * "处理了但没内容" / "还没处理"的唯一判据。
     */
    private suspend fun persistEmptySummary(record: Record, language: AppLanguage) {
        if (derivedContentRepository.getByRecordAndType(record.id, DerivedType.SUMMARY) != null) return
        upsert(record.id, DerivedType.SUMMARY, "", System.currentTimeMillis(), language)
    }

    private suspend fun persist(record: Record, enrichment: Enrichment, language: AppLanguage) {
        val now = System.currentTimeMillis()

        // 网页提取已经写了页面标题：那是站点自己的标题，比模型生成的短标题更准，
        // 因此只在没有标题时才补一个（M8 的产物优先级高于 M11）。
        if (enrichment.title != null &&
            derivedContentRepository.getByRecordAndType(record.id, DerivedType.TITLE) == null
        ) {
            upsert(record.id, DerivedType.TITLE, enrichment.title, now, language)
        }

        // 空摘要也写：界面靠"有没有这一行"区分"没处理"和"处理了但没内容"
        upsert(record.id, DerivedType.SUMMARY, enrichment.summary.orEmpty(), now, language)

        val tags = enrichment.tags
            .map { it.trim().take(MAX_TAG_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS)

        // TAGS 派生内容存"模型原始回答"，标签表存"可查询的索引"。
        // 两者都写：前者便于排查"模型当年给了什么"，后者供检索与展示。
        upsert(record.id, DerivedType.TAGS, tags.joinToString(TAG_SEPARATOR), now, language)
        tagRepository.replaceAiTags(record.id, tags, now)

        Log.i(
            TAG,
            "摘要完成 recordId=${record.id} 语言=${language.code} " +
                "摘要长度=${enrichment.summary?.length ?: 0} 标签数=${tags.size}",
        )
    }

    private suspend fun upsert(
        recordId: String,
        type: DerivedType,
        content: String,
        now: Long,
        language: AppLanguage,
    ) {
        derivedContentRepository.upsert(
            DerivedContent(
                id = DerivedContentKeys.idFor(recordId, type),
                recordId = recordId,
                contentType = type,
                content = content,
                processorVersion = PROCESSOR_VERSION,
                processedAt = now,
                errorMessage = null,
                // 标题 / 摘要 / 标签都是生成类结果（R-I18N-08），记下生成时语言；
                // 原文、OCR、转写不经过这里，因此永远不带语言标记。
                outputLanguage = language.code,
            ),
        )
    }

    companion object {
        /** 送去摘要的正文上限：再长对摘要质量没有边际收益，只增加时延与费用。 */
        internal const val MAX_INPUT_CHARS = 4_000

        /** PRD §6.3：每条记录最多 5 个 AI 标签。 */
        internal const val MAX_TAGS = 5

        /** 单标签长度上限：模型偶尔会吐出一整句，截断后仍可用。 */
        internal const val MAX_TAG_LENGTH = 24

        /** TAGS 派生内容的分隔符（换行便于人工阅读，也避免与标签内的逗号冲突）。 */
        internal const val TAG_SEPARATOR = "\n"

        /** 会产生正文的上游任务，按优先级排列。 */
        internal val UPSTREAM_JOBS = listOf(
            JobType.WEBPAGE_EXTRACT,
            JobType.OCR,
            JobType.TRANSCRIPTION,
        )

        /** 处理链路版本：换模型或换提示词时改这里，便于将来批量重跑。 */
        internal const val PROCESSOR_VERSION = "gateway-enrich/v1"

        private const val TAG = "SummarizeWorker"
    }
}
