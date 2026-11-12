package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.ai.AiGatewaySettings
import com.zhicun.app.domain.ai.CollectionMemberInput
import com.zhicun.app.domain.ai.ProcessingInputText
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.repository.DerivedContentRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.settings.AiSettings
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.domain.worker.ProcessingJobTargets
import com.zhicun.app.domain.worker.ProcessingScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * 用户确认"可以上传"之后，解锁这条记录的云端处理（M11）。
 *
 * ## 为什么单独一个用例，而不是复用 RetryProcessingUseCase
 * 后者会把该类型**所有**任务复位成 QUEUED（图片会连 OCR 一起重跑）。
 * 这里只想打开"上传"这一件事：只排 [ProcessingJobTargets.CLOUD_JOBS] 里的类型，
 * 本地任务保持原状，不因为一次确认而白白重算。
 *
 * ## 敏感标记怎么处理（2026-10-23 QODER P2-6）
 * 确认**不改写** `sensitivityStatus`：PRD §6.4 要求"不因一次确认就把整条记录永久标记为
 * 安全"，疑似标记与风险徽标必须原样保留。确认只记录"用户在第几版内容上点过确认"
 * （`cloudConfirmedRevision = recordRevision`），上传闸门据此放行；内容一旦编辑，
 * `recordRevision` 递增，旧确认自动失效。
 * （现状注明：编辑入口尚未上线，`recordRevision` 目前恒为 1，"编辑后失效"这条路径
 * 是给编辑功能预留的自动保护，编辑落地即生效，无需改这里的代码。）
 *
 * ## 确认范围（2026-10-23 审查 R-2）
 * 只记版本号不够：OCR / 转写是**之后**才落地的派生内容，它们不递增 `recordRevision`，
 * 于是一次确认等于发了一张永久通行证 —— 用户没见过的转写文本照样会被发去摘要。
 * 因此确认时同时记下"此刻存在哪些内容块"（[ProcessingInputText.presentParts]），
 * 外发前要求本次用到的那几块 ⊆ 确认范围。后来才出现的那块会被拦下，
 * 界面重新出现确认横幅，用户看过新内容再点一次即可。
 *
 * ## 云端根本不可用时确认是空动作（2026-10-23 审查 R-3）
 * 开关关着或网关没配置时，这里排出去的任务会被 Worker 重新判闸并置为 `CANCELLED`，
 * 而记录上的 `cloudConfirmed` 已经变真、横幅随之消失 —— 用户点了一次、
 * 界面说"好了"、实际什么都没跑，而且再也点不了第二次。
 * 因此先判**通路**（[CloudUploadGate.cloudPathBlockedReason]，不看敏感状态）：
 * 通路不通就直接返回 false，不写确认、不排任务。
 * 注意不能用 `reasonOf`：那会把"记录敏感"也拦掉，而确认正是为解这一条存在的。
 *
 * 记录侧同步回到 PENDING，界面从"未上传"切到"处理中"，用户能立刻看到反馈。
 *
 * ## 一次点击同时放行网页抓取（2026-10-24 用户反馈）
 * 敏感网页记录此前要让用户点两次：AI 分区"确认上传"、网页分区"允许抓取"。
 * 用户口径是"点一次就代表我知道这是敏感信息、允许出网"，因此这一次确认同时写
 * [com.zhicun.app.domain.repository.RecordRepository.confirmWebFetch]，
 * 并把待抓取的标题/正文预先纳入确认范围 —— 否则抓取回来的正文会因"不在确认范围里"
 * 再次弹出横幅，等于还是要点两次。
 *
 * 云端通路不可用时这条路径仍走不通（下面 [cloudPathUnavailable]），
 * 此时界面保留网页分区自己的"允许抓取"入口 —— 抓取不经 AI 网关，不该被云端配置卡死。
 */
class ConfirmCloudProcessingUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val jobRepository: ProcessingJobRepository,
    private val derivedContentRepository: DerivedContentRepository,
    private val assetRepository: AssetRepository,
    private val collectionRepository: CollectionRepository,
    private val scheduler: ProcessingScheduler,
    private val aiSettings: AiSettings,
    private val gatewaySettings: AiGatewaySettings,
    private val confirmWebFetch: ConfirmWebFetchUseCase,
) {

    /**
     * @return true = 至少排入了一个云端任务；false = 记录不存在、云端通路不可用，
     *   或该类型没有云端任务
     */
    suspend operator fun invoke(
        recordId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (recordId.isBlank()) return false
        if (cloudPathUnavailable()) return false
        val record = recordRepository.getById(recordId) ?: return false

        val cloudJobs = ProcessingJobTargets.of(record.type)
            .filter { it in ProcessingJobTargets.CLOUD_JOBS }
        if (cloudJobs.isEmpty()) return false

        // 先改记录再排任务：界面订阅的是记录状态，这样"点了确认"立刻有反馈；
        // 即使后面 enqueue 失败，下次启动补投还能看到 QUEUED 的行。
        //
        // 只写四列而不是整行回写（2026-10-23 复核 F-8）：这条路径与派生正文的敏感升级
        // （`elevateSensitivity`）可能并发，整行覆盖会把刚升上去的 `suspected` 冲掉，
        // 等于替用户放行了本该拦下的内容。
        val confirmed = recordRepository.confirmCloudProcessing(
            id = recordId,
            revision = record.recordRevision,
            scope = confirmScope(record),
            status = ProcessingStatus.PENDING,
            updatedAt = now,
        )
        if (!confirmed) {
            // 记录在"读出来"与"写确认"之间被删掉了：再排任务只会让 Worker 白跑一趟。
            // domain 层不引 android.util.Log（会让纯 JVM 单测需要 Robolectric），
            // 静默返回 false 即可 —— 调用方与界面都按"没成功"处理。
            return false
        }

        // 同一次点击也放行网页抓取（2026-10-24 用户反馈）：界面上只有一个确认按钮，
        // 用户的心智是"我知悉敏感，允许出网"，不该让他为了同一个页面再点第二次。
        // 抓取任务由 ConfirmWebFetchUseCase 负责（写标记 + 复位/新建 WEBPAGE_EXTRACT），
        // 这里不复制一套入队逻辑。
        if (record.type == RecordType.WEB_LINK &&
            record.sensitivityStatus.isSensitive &&
            !record.webFetchConfirmed
        ) {
            confirmWebFetch(recordId = recordId, now = now)
        }

        cloudJobs.forEach { jobType ->
            val existing = jobRepository.getByRecordAndType(recordId, jobType)

            // 已完成的转写不重跑（2026-10-23 复核 F-1）。
            //
            // 音频附件不可替换：这条记录的转写结果只可能因为"当初被拦下"而不存在，
            // 一旦 DONE 就永远有效。重跑会把同一段音频**再上传一次**，而转写落地后的
            // 派生敏感复查（`DerivedSensitivityGuard`）会清掉刚写入的确认 ——
            // 摘要/向量随即被取消、横幅再次出现，用户每点一次确认就重复上传一次音频，
            // 摘要永远生成不出来。这是"确认"这个动作最不该发生的副作用。
            //
            // 只有转写这样处理：摘要/标签/向量在内容变化后确实需要重算，
            // 而"用户想看新的转写"的正确入口是 RetryProcessingUseCase，那里会复位 DONE。
            if (jobType == JobType.TRANSCRIPTION && existing?.status == JobStatus.DONE) {
                return@forEach
            }

            if (existing == null) {
                jobRepository.insert(newJob(recordId, jobType))
            } else {
                jobRepository.updateStatus(
                    existing.copy(
                        status = JobStatus.QUEUED,
                        attempts = 0,
                        // 确认等于用户重新给了一次完整机会（2026-10-23 复核 F-2）
                        waitRounds = 0,
                        nextRetryAt = null,
                        errorCategory = null,
                        startedAt = null,
                        finishedAt = null,
                    ),
                )
            }
            scheduler.reenqueue(recordId, jobType)
        }

        // 被策略取消的**本地出网**任务也要跟着复活（2026-10-23 R-7）：
        // 网页抓取在记录敏感时被拦下（连 URL 都不发），用户确认后就没有理由再拦。
        // 只认 CANCELLED：DONE 不重跑、QUEUED/RUNNING 已在队列里。
        ProcessingJobTargets.of(record.type)
            .filter { it !in ProcessingJobTargets.CLOUD_JOBS }
            .forEach { jobType ->
                val existing = jobRepository.getByRecordAndType(recordId, jobType) ?: return@forEach
                if (existing.status != JobStatus.CANCELLED) return@forEach
                jobRepository.updateStatus(
                    existing.copy(
                        status = JobStatus.QUEUED,
                        attempts = 0,
                        waitRounds = 0,
                        nextRetryAt = null,
                        errorCategory = null,
                        startedAt = null,
                        finishedAt = null,
                    ),
                )
                scheduler.reenqueue(recordId, jobType)
            }
        return true
    }

    private fun newJob(recordId: String, jobType: JobType) = ProcessingJob(
        id = UUID.randomUUID().toString(),
        recordId = recordId,
        jobType = jobType,
        status = JobStatus.QUEUED,
        attempts = 0,
        maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
    )

    /**
     * 用户这一眼看到的内容块 = 这次确认放行的范围。
     *
     * 用 [ProcessingInputText.presentParts] 而不是在这里自己数一遍：那个函数由
     * "摘要输入"和"向量输入"两条外发路径派生而来，与真正发出去的东西结构上同源。
     * 手写一份"rawText 非空就加 RAW_TEXT…"就等于把同一件事记两遍，
     * 而两遍对不上时没有任何测试会红 —— 那正是 R-2 的漏洞本身。
     */
    private suspend fun confirmScope(record: Record): Set<ContentPart> {
        val derived = derivedContentRepository.getByRecordId(record.id)

        // 合集摘要发的是成员正文（M20），确认范围必须覆盖成员的内容块；
        // 只记合集自己的说明，等于用户看过的与真正发出去的不是同一批内容。
        // 成员音频不算：合集只发文字，不会二次上传音频字节。
        if (record.type == RecordType.COLLECTION) {
            val members = collectionRepository.getMembersWithRecords(record.id).map { member ->
                CollectionMemberInput(
                    record = member.record,
                    derived = derivedContentRepository.getByRecordId(member.record.id),
                )
            }
            return ProcessingInputText.collectionPresentParts(
                collection = record,
                descriptionDerived = derived,
                members = members,
            )
        }

        val hasUploadableAttachment = assetRepository.getByRecordId(record.id)
            .any { it.isUploadable }
        val parts = ProcessingInputText.presentParts(record, derived, hasUploadableAttachment)
        // 网页记录：这次点击同时放行抓取，抓回来的标题/正文就是用户授权的那份内容，
        // 预先写进确认范围（2026-10-24 用户反馈）。不写的话，抓取完成后摘要/向量
        // 会因为"PAGE_TEXT 不在范围里"再次被拦、横幅再弹一次 —— 等于还是要点两次。
        return if (record.type == RecordType.WEB_LINK && record.sensitivityStatus.isSensitive) {
            parts + ContentPart.PAGE_TEXT + ContentPart.SOURCE_TITLE
        } else {
            parts
        }
    }

    /** 只判"云端通路"（开关 + 网关），不判记录是否敏感 —— 确认就是用来解敏感那一条的。 */
    private fun cloudPathUnavailable(): Boolean = CloudUploadGate.cloudPathBlockedReason(
        cloudAiEnabled = aiSettings.cloudAiEnabled,
        gatewayConfigured = gatewaySettings.isConfigured,
    ) != null
}
