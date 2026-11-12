package com.zhicun.app.service.share

import android.content.Intent
import android.net.Uri
import com.zhicun.app.R
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.repository.AssetRepositoryImpl
import com.zhicun.app.data.repository.ProcessingJobRepositoryImpl
import com.zhicun.app.data.repository.RecordRepositoryImpl
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.repository.RecordRepository
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.CopyAssetToPrivateDirUseCase
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 分享接收编排的端到端测试（Text → 解析 → SaveRecordUseCase → 真实 Room → 反馈文案）。
 *
 * 为什么不测 Activity 本身：`@AndroidEntryPoint` 的 Activity 需要 Hilt 测试环境
 * （`hilt-android-testing` + `HiltTestApplication`）才能被 Robolectric 启动。
 * 这里把编排逻辑抽到 [ShareIntakeCoordinator]，用真实 Room 覆盖了除"Toast + finish"
 * 之外的全部路径；Activity 只剩 15 行模板代码（见 M4 完成说明·已知限制 1）。
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var recordRepository: RecordRepositoryImpl
    private lateinit var instance: ShareIntakeCoordinator

    private val packageManager get() = RuntimeEnvironment.getApplication().packageManager

    @Before
    fun setUp() {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        recordRepository = RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        )
        instance = coordinatorWith(recordRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun coordinatorWith(repository: RecordRepository): ShareIntakeCoordinator {
        val useCase = SaveRecordUseCase(
            recordRepository = repository,
            copyAsset = CopyAssetToPrivateDirUseCase(FakeAssetImporter(), AssetRepositoryImpl(database.assetDao())),
            sensitiveDetector = SensitiveContentDetector(),
            scheduleProcessing = ScheduleProcessingUseCase(
                ProcessingJobRepositoryImpl(database.processingJobDao()),
                FakeProcessingScheduler(),
                RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        ),
                FakeAiSettings(),
                FakeAiGatewaySettings(),
            ),
            diagnosticLog = FakeDiagnosticLog(),
        )
        return ShareIntakeCoordinator(useCase)
    }

    private fun textIntent(text: String, subject: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }

    @Test
    fun Handle_sharedUrl_savesWebLinkAndReportsSaved() = runBlocking {
        val feedback = instance.handle(textIntent("https://example.com/post"), packageManager, null)

        assertEquals(R.string.share_saved, feedback.messageRes)
        val record = recordRepository.observeAll().first().single()
        assertEquals(RecordType.WEB_LINK, record.type)
        assertEquals("https://example.com/post", record.sourceUrl)
        assertEquals(ProcessingStatus.PENDING, record.processingStatus)
    }

    @Test
    fun Handle_sharedPlainText_savesSharedTextWithSubjectAsTitle() = runBlocking {
        val feedback = instance.handle(textIntent("随手记一句", subject = "备忘"), packageManager, null)

        assertEquals(R.string.share_saved, feedback.messageRes)
        val record = recordRepository.observeAll().first().single()
        assertEquals(RecordType.SHARED_TEXT, record.type)
        assertEquals("备忘", record.sourceTitle)
    }

    @Test
    fun Handle_sensitiveText_marksRecordSuspected() = runBlocking {
        instance.handle(textIntent("配置 password: hunter2hunter2"), packageManager, null)

        val record = recordRepository.observeAll().first().single()
        assertEquals(SensitivityStatus.SUSPECTED, record.sensitivityStatus)
    }

    @Test
    fun Handle_unsupportedMime_reportsUnsupportedAndSavesNothing() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/archive.zip"))
        }

        val feedback = instance.handle(intent, packageManager, null)

        assertEquals(R.string.share_unsupported_type, feedback.messageRes)
        assertTrue("不支持的分享不应写库", recordRepository.observeAll().first().isEmpty())
    }

    @Test
    fun Handle_emptyIntent_reportsNothingReceived() = runBlocking {
        val feedback = instance.handle(Intent(Intent.ACTION_SEND).apply { type = "text/plain" }, packageManager, null)

        assertEquals(R.string.share_nothing_received, feedback.messageRes)
    }

    @Test
    fun Handle_saveThrows_reportsSaveFailedInsteadOfCrashing() = runBlocking {
        val failing = object : RecordRepository {
            override fun observeAll(): Flow<List<Record>> = flowOf(emptyList())
            override fun observeInbox(): Flow<List<Record>> = flowOf(emptyList())
            override fun observeArchived(): Flow<List<Record>> = flowOf(emptyList())
            override fun searchArchivedBySubstring(normalized: String, limit: Int): Flow<List<Record>> = flowOf(emptyList())
            override suspend fun getById(id: String): Record? = null
            override suspend fun gateRecord(record: Record): Record = record
            override suspend fun getByIds(ids: List<String>): List<Record> = emptyList()
            override fun observeById(id: String): Flow<Record?> = flowOf(null)
            override suspend fun save(record: Record) = throw IllegalStateException("磁盘已满")
            override suspend fun update(record: Record) = Unit
            override suspend fun softDelete(id: String, updatedAt: Long) = Unit
            override suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int = 0
            override suspend fun updateProcessingStatus(
                id: String,
                status: ProcessingStatus,
                updatedAt: Long,
            ) = Unit
            override suspend fun updateType(
                id: String,
                type: com.zhicun.app.domain.model.RecordType,
                updatedAt: Long,
            ): Boolean = false
            override suspend fun updateRawText(
                id: String,
                rawText: String?,
                sensitivityStatus: com.zhicun.app.domain.model.SensitivityStatus,
                expectedRevision: Int,
                updatedAt: Long,
            ): Boolean = false
            override suspend fun confirmCloudProcessing(
                id: String,
                revision: Int,
                scope: Set<com.zhicun.app.domain.model.ContentPart>,
                status: ProcessingStatus,
                updatedAt: Long,
            ): Boolean = false
            override suspend fun confirmWebFetch(
                id: String,
                revision: Int,
                updatedAt: Long,
            ): Boolean = false
            override suspend fun recomputeProcessingStatus(
                id: String,
                cloudActive: Boolean,
                updatedAt: Long,
            ) = Unit
            override suspend fun elevateSensitivity(id: String, updatedAt: Long) = Unit
            override suspend fun idsByProcessingStatus(
                status: ProcessingStatus,
            ): List<String> = emptyList()
            // 分享链路不做去重（2026-10-24）：用户主动分享同一条内容 = 要再存一份
            override suspend fun findDuplicate(text: String): String? = null
            override suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long) = false

            override suspend fun setFavorite(
                ids: List<String>,
                favorite: Boolean,
                updatedAt: Long,
            ): Int = 0
            override suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long) = false
            override suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long) = 0
        }

        val feedback = coordinatorWith(failing).handle(textIntent("内容"), packageManager, null)

        assertEquals(R.string.share_save_failed, feedback.messageRes)
    }
}
