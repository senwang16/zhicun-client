package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.worker.ProcessingScheduler
import java.util.UUID
import javax.inject.Inject

/**
 * 用户允许"抓取这个网页"（2026-10-23 复核 F-5，决策 D-1/D-5）。
 *
 * ## 它只授权一件事
 * 向源站请求那个 URL。**不排任何云端任务**、**不看云端开关与网关配置** ——
 * 抓取不经 AI 网关，正文回到本机后要不要发去摘要，是另一道门（`CloudUploadGate.reasonOf`）
 * 的事。
 *
 * ## 现在的调用方（2026-10-24 用户反馈）
 * 详情页的敏感确认只有**一个按钮**：云端通路可用时由
 * [ConfirmCloudProcessingUseCase] 调一次本用例，一次点击同时放行上传与抓取；
 * 云端通路不可用时，网页分区仍保留"允许抓取此网页"的独立入口，单独调本用例。
 *
 * ## 为什么不能复用 ConfirmCloudProcessingUseCase
 * 那个用例先判云端**通路**（开关 + 网关），通路不通就返回 false；这条路径恰恰要求
 * 通路不通时也能确认 —— 否则默认无网关的安装包里，敏感网页永远抓不下来，
 * 而界面还写着"确认上传后可以重新提取"。两者的前置条件正好相反，合并不出来。
 *
 * ## 只复活网页抓取任务
 * 状态复位口径与重试一致（`attempts = 0`、`waitRounds = 0`），但只动 `WEBPAGE_EXTRACT`：
 * 摘要/向量此刻仍可能被敏感闸门挡着，顺手排上去只会被 Worker 再取消一次，
 * 并在任务表里留下一串无意义的 CANCELLED。
 */
class ConfirmWebFetchUseCase @Inject constructor(
    private val recordRepository: RecordRepository,
    private val jobRepository: ProcessingJobRepository,
    private val scheduler: ProcessingScheduler,
) {

    /**
     * @return true = 已写入允许标记并（重新）排入了网页抓取任务；
     *   false = 记录不存在、不是网页记录、内容不敏感（没有可放行的东西），或写入时记录已被删除
     */
    suspend operator fun invoke(
        recordId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (recordId.isBlank()) return false
        val record = recordRepository.getById(recordId) ?: return false
        // 只有网页记录需要抓取；非敏感记录本来就不受这道闸约束，没有"放行"可做
        if (record.type != RecordType.WEB_LINK) return false
        if (!record.sensitivityStatus.isSensitive) return false
        // 已允许过就没必要重复写一次：无差异的 UPDATE 也会触发 FTS 同步触发器整行重建索引
        if (record.webFetchConfirmed) return true

        val confirmed = recordRepository.confirmWebFetch(
            id = recordId,
            revision = record.recordRevision,
            updatedAt = now,
        )
        if (!confirmed) {
            // 读出来与写下去之间被删掉了：再排任务只会让 Worker 白跑一趟。
            // domain 层不引 android.util.Log（会让纯 JVM 单测需要 Robolectric）。
            return false
        }

        // 顺序与其它用例一致：先落库再入队。反过来的话，enqueue 成功而写库失败时会留下
        // "任务在跑、记录上却写着没允许"的矛盾状态，Worker 会把这一趟判成 Blocked。
        val existing = jobRepository.getByRecordAndType(recordId, JobType.WEBPAGE_EXTRACT)
        if (existing == null) {
            jobRepository.insert(
                ProcessingJob(
                    id = UUID.randomUUID().toString(),
                    recordId = recordId,
                    jobType = JobType.WEBPAGE_EXTRACT,
                    status = JobStatus.QUEUED,
                    attempts = 0,
                    maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
                ),
            )
        } else {
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
        }
        scheduler.reenqueue(recordId, JobType.WEBPAGE_EXTRACT)
        return true
    }
}
