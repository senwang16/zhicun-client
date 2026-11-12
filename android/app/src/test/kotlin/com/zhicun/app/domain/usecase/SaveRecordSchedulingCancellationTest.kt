package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.file.ExternalAssetImporter
import com.zhicun.app.domain.file.ImportedAsset
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.ProcessingJobRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.worker.ProcessingScheduler
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeDiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M5–M8 独立审核整改回归：**调度阶段的取消信号不能被吞掉**。
 *
 * 整改前 `SaveRecordUseCase` 用 `runCatching { scheduleProcessing(record) }`，
 * 而 `runCatching` 会把 `CancellationException` 一并吃掉 —— 保存协程被取消时，
 * 调用方还会收到"保存成功"，协程取消语义直接失效。
 *
 * 本测试用最小的手写假实现（而不是引用公共 Fake）来钉这两个分支：
 * 取消必须上抛、普通异常必须被吞。
 */
class SaveRecordSchedulingCancellationTest {

    private val recordRepository = RecordingRecordRepository()
    private val assetRepository = RecordingAssetRepository()
    private val diagnosticLog = FakeDiagnosticLog()

    private fun useCase(scheduler: ProcessingScheduler): SaveRecordUseCase = SaveRecordUseCase(
        recordRepository = recordRepository,
        copyAsset = CopyAssetToPrivateDirUseCase(NoopImporter(), assetRepository),
        sensitiveDetector = SensitiveContentDetector(),
        scheduleProcessing = ScheduleProcessingUseCase(
            RecordingJobRepository(),
            scheduler,
            recordRepository,
            FakeAiSettings(),
            FakeAiGatewaySettings(),
        ),
        diagnosticLog = diagnosticLog,
    )

    @Test
    fun Invoke_schedulerThrowsCancellation_rethrowsAndKeepsSavedRecord() = runTest {
        // 用链接而不是纯文本：TEXT_NOTE 在当前映射表里不排任何任务（M11 才接摘要），
        // 那样根本走不到调度这一步，测出来的"没抛异常"是假通过。
        val useCase = useCase(ThrowingScheduler(CancellationException("保存协程被取消")))

        try {
            useCase(RecordInput(text = "https://example.com/article"), CaptureSource.MANUAL)
            fail("取消信号必须原样抛出，不能被 runCatching 吞掉")
        } catch (e: CancellationException) {
            // 期望路径
        }

        // 记录本身在调度之前就已落库：取消只应终止调度，不应回滚已保存的内容
        assertEquals(1, recordRepository.saved.size)
    }

    @Test
    fun Invoke_schedulerThrowsOrdinaryException_stillReturnsSavedResult() = runTest {
        val useCase = useCase(ThrowingScheduler(IllegalStateException("WorkManager 未初始化")))

        val result = useCase(RecordInput(text = "https://example.com/article"), CaptureSource.MANUAL)

        assertNotNull(result.recordId)
        assertEquals(1, recordRepository.saved.size)
        assertTrue("调度失败不该影响保存结果", recordRepository.saved.containsKey(result.recordId))
        // 吞掉异常 ≠ 静默：失败原因必须留痕（AGENTS §2.1，2026-10-22 审核 P2）
        assertTrue("调度失败必须写诊断日志", diagnosticLog.entries.isNotEmpty())
    }

    @Test
    fun Invoke_schedulerThrowsCancellation_doesNotRollBackAssetCopy() = runTest {
        // 附件复制发生在调度之前：取消调度不该让已经落盘的附件消失
        val useCase = useCase(ThrowingScheduler(CancellationException("取消")))

        try {
            useCase(
                RecordInput(
                    text = "带附件的记录",
                    supportedUris = listOf(com.zhicun.app.domain.model.InputUri("content://x/1", "image/png")),
                ),
                CaptureSource.MANUAL,
            )
            fail("应当上抛取消")
        } catch (_: CancellationException) {
            // 期望路径
        }

        assertEquals(1, assetRepository.saved.size)
    }

    // --- 最小假实现 ---

    private class ThrowingScheduler(private val error: Throwable) : ProcessingScheduler {
        override fun enqueue(recordId: String, jobType: JobType) = throw error
        override fun cancel(recordId: String, jobType: JobType) = Unit
    }

    private class RecordingRecordRepository : com.zhicun.app.domain.repository.RecordRepository {
        val saved = linkedMapOf<String, Record>()

        override fun observeAll() = kotlinx.coroutines.flow.flowOf(saved.values.toList())

        override fun observeInbox() = kotlinx.coroutines.flow.flowOf(saved.values.toList())

        override fun observeArchived() = kotlinx.coroutines.flow.flowOf(emptyList<Record>())

        override fun searchArchivedBySubstring(normalized: String, limit: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<Record>())

        override suspend fun getById(id: String): Record? = saved[id]

        /** 本类不测合集；闸门原样返回。 */
        override suspend fun gateRecord(record: Record): Record = record

        override suspend fun getByIds(ids: List<String>): List<Record> =
            ids.mapNotNull { saved[it] }

        override fun observeById(id: String) = kotlinx.coroutines.flow.flowOf(saved[id])

        override suspend fun save(record: Record) {
            saved[record.id] = record
        }

        /** 本类不测确认上传；接口要求实现，保持"写回四列"的真实语义。 */
        override suspend fun confirmCloudProcessing(
            id: String,
            revision: Int,
            scope: Set<com.zhicun.app.domain.model.ContentPart>,
            status: com.zhicun.app.domain.model.ProcessingStatus,
            updatedAt: Long,
        ): Boolean = saved[id]?.let {
            saved[id] = it.copy(
                cloudConfirmedRevision = revision,
                cloudConfirmedScope = scope,
                processingStatus = status,
                updatedAt = updatedAt,
            )
            true
        } ?: false

        /** 本类不测抓取确认；接口要求实现，保持"只写抓取列"的真实语义。 */
        override suspend fun confirmWebFetch(id: String, revision: Int, updatedAt: Long): Boolean =
            saved[id]?.let {
                saved[id] = it.copy(webFetchConfirmedRevision = revision, updatedAt = updatedAt)
                true
            } ?: false

        override suspend fun update(record: Record) {
            saved[record.id] = record
        }

        // 原文编辑不在本用例范围：接口要求实现，返回 false 表示没有写入
        override suspend fun updateRawText(
            id: String,
            rawText: String?,
            sensitivityStatus: com.zhicun.app.domain.model.SensitivityStatus,
            expectedRevision: Int,
            updatedAt: Long,
        ): Boolean = false

        override suspend fun softDelete(id: String, updatedAt: Long) {
            saved.remove(id)
        }

        override suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int {
            ids.forEach(saved::remove)
            return ids.size
        }

        override suspend fun updateProcessingStatus(
            id: String,
            status: com.zhicun.app.domain.model.ProcessingStatus,
            updatedAt: Long,
        ) {
            saved[id]?.let { saved[id] = it.copy(processingStatus = status, updatedAt = updatedAt) }
        }

        override suspend fun updateType(
            id: String,
            type: com.zhicun.app.domain.model.RecordType,
            updatedAt: Long,
        ): Boolean {
            val current = saved[id] ?: return false
            saved[id] = current.copy(type = type, updatedAt = updatedAt)
            return true
        }

        // 本用例不跑任务执行器；聚合与敏感升级给最小实现即可
        override suspend fun recomputeProcessingStatus(id: String, cloudActive: Boolean, updatedAt: Long) = Unit

        override suspend fun idsByProcessingStatus(
            status: com.zhicun.app.domain.model.ProcessingStatus,
        ): List<String> = emptyList()

        // 保存链路不做去重（2026-10-24）：去重只发生在剪贴板收录入口
        override suspend fun findDuplicate(text: String): String? = null

        override suspend fun elevateSensitivity(id: String, updatedAt: Long) = Unit

        // 本用例只关心保存/调度，整理接口给最小实现即可（真实的整理行为由 FakeRecordRepository 覆盖）
        override suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long) = false

        override suspend fun setFavorite(
            ids: List<String>,
            favorite: Boolean,
            updatedAt: Long,
        ): Int = 0

        override suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long) = false

        override suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long) = 0
    }

    private class RecordingJobRepository : ProcessingJobRepository {
        val jobs = linkedMapOf<String, ProcessingJob>()

        override suspend fun insert(job: ProcessingJob) {
            jobs[job.id] = job
        }

        override suspend fun getByRecordAndType(recordId: String, jobType: JobType): ProcessingJob? =
            jobs.values.firstOrNull { it.recordId == recordId && it.jobType == jobType }

        override suspend fun getByStatuses(statuses: List<JobStatus>): List<ProcessingJob> =
            jobs.values.filter { it.status in statuses }

        override suspend fun updateStatus(job: ProcessingJob) {
            jobs[job.id] = job
        }

        override suspend fun updateStatusIfRunning(job: ProcessingJob, expectedAttempts: Int): Boolean {
            val current = jobs[job.id] ?: return false
            if (current.status != JobStatus.RUNNING || current.attempts != expectedAttempts) return false
            jobs[job.id] = job
            return true
        }
    }

    private class RecordingAssetRepository : AssetRepository {
        val saved = mutableListOf<Asset>()

        override suspend fun getByRecordId(recordId: String): List<Asset> =
            saved.filter { it.recordId == recordId }

        override suspend fun save(asset: Asset) {
            saved += asset
        }

        override suspend fun updateSaveStatus(id: String, status: AssetSaveStatus) = Unit

        override suspend fun deleteById(id: String) = Unit

        override suspend fun getRecordIdsWithMultipleMedia(): List<String> = emptyList()
    }

    private class NoopImporter : ExternalAssetImporter {
        override suspend fun import(
            recordId: String,
            source: com.zhicun.app.domain.model.InputUri,
        ): ImportedAsset = ImportedAsset(
            localPath = "/tmp/$recordId/file",
            mimeType = source.mimeType ?: "application/octet-stream",
            sizeBytes = 1L,
            sha256 = null,
        )
    }
}
