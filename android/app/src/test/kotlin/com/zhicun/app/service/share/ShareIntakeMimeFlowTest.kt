package com.zhicun.app.service.share

import android.content.Intent
import android.net.Uri
import com.zhicun.app.R
import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.repository.AssetRepositoryImpl
import com.zhicun.app.data.repository.ProcessingJobRepositoryImpl
import com.zhicun.app.data.repository.RecordRepositoryImpl
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.sensitive.SensitiveContentDetector
import com.zhicun.app.domain.usecase.CopyAssetToPrivateDirUseCase
import com.zhicun.app.domain.usecase.SaveRecordUseCase
import com.zhicun.app.domain.usecase.ScheduleProcessingUseCase
import com.zhicun.app.fake.FakeAiGatewaySettings
import com.zhicun.app.fake.FakeAiSettings
import com.zhicun.app.fake.FakeAssetImporter
import com.zhicun.app.fake.FakeDiagnosticLog
import com.zhicun.app.fake.FakeProcessingScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「通配声明类型回查真实类型」的**端到端**验证（M4 审核发现的缺口）。
 *
 * 解析层的单测只能证明 `InputUri.mimeType` 被填对了，但审核指出的后果发生在下游两处：
 * 记录分类（`Record.type`）与 Asset 落库（`assets.mime_type`）。所以这里从
 * `SharePayloadParser` 一路跑到真实 Room，断言最终落库的值。
 *
 * 全链路：Intent(`image` 前缀) → MimeTypeLookup → RecordInput → SharedContentClassifier
 * → SaveRecordUseCase → CopyAssetToPrivateDirUseCase → Room
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeMimeFlowTest {

    private lateinit var database: AppDatabase
    private lateinit var recordRepository: RecordRepositoryImpl
    private lateinit var assetRepository: AssetRepositoryImpl
    private lateinit var importer: FakeAssetImporter
    private lateinit var coordinator: ShareIntakeCoordinator

    private val imageUri = Uri.parse("content://media/external/images/77")

    @Before
    fun setUp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        database = AppDatabaseFactory.createInMemory(context)
        recordRepository = RecordRepositoryImpl(
            database.recordDao(),
            database.collectionDao(),
            SensitiveContentDetector(),
        )
        assetRepository = AssetRepositoryImpl(database.assetDao())
        // resolvedMime 留空：模拟"导入器也查不到类型"，逼出"保存 Asset 时只能用解析阶段结果"的场景
        importer = FakeAssetImporter()
        coordinator = ShareIntakeCoordinator(
            SaveRecordUseCase(
                recordRepository = recordRepository,
                copyAsset = CopyAssetToPrivateDirUseCase(importer, assetRepository),
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
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun imageIntent(declaredMime: String) = Intent(Intent.ACTION_SEND).apply {
        type = declaredMime
        putExtra(Intent.EXTRA_STREAM, imageUri)
    }

    @Test
    fun WildcardImageShare_savedAsImageRecordWithRealMimeOnAsset() = runBlocking {
        val feedback = coordinator.handle(
            intent = imageIntent("image/*"),
            packageManager = null,
            referrerPackage = null,
            mimeTypeLookup = MimeTypeLookup { "image/png" },
        )

        val record = recordRepository.observeAll().first().single()
        val asset = assetRepository.getByRecordId(record.id).single()

        assertEquals("记录类型应为图片", RecordType.IMAGE, record.type)
        assertEquals("image/png", asset.mimeType)
        assertEquals(R.string.share_saved_with_assets, feedback.messageRes)
    }

    @Test
    fun ConcreteImageShare_keepsDeclaredTypeOnAsset() = runBlocking {
        // 对照组：发送方给了具体类型时行为不变，且不依赖 lookup
        coordinator.handle(
            intent = imageIntent("image/webp"),
            packageManager = null,
            referrerPackage = null,
            mimeTypeLookup = null,
        )

        val record = recordRepository.observeAll().first().single()
        val asset = assetRepository.getByRecordId(record.id).single()

        assertEquals(RecordType.IMAGE, record.type)
        assertEquals("image/webp", asset.mimeType)
    }

    @Test
    fun WildcardImageShare_unknownRealType_fallsBackToOctetStreamWithoutCrashing() = runBlocking {
        // 真实类型查不到时：记录仍然保存（用户最关心"内容不留丢"），类型退化为 FILE + octet-stream
        coordinator.handle(
            intent = imageIntent("image/*"),
            packageManager = null,
            referrerPackage = null,
            mimeTypeLookup = MimeTypeLookup { null },
        )

        val record = recordRepository.observeAll().first().single()
        val asset = assetRepository.getByRecordId(record.id).single()

        assertEquals(RecordType.FILE, record.type)
        assertEquals("application/octet-stream", asset.mimeType)
    }
}
