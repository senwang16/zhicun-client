package com.zhicun.app.domain.export

import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.FakeAssetRepository
import com.zhicun.app.fake.FakeCollectionRepository
import com.zhicun.app.fake.FakeDerivedContentRepository
import com.zhicun.app.fake.FakeExportArchiveWriter
import com.zhicun.app.fake.FakeLocalFileStore
import com.zhicun.app.fake.FakeRecordRepository
import com.zhicun.app.fake.FakeTagRepository
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导出用例（M15）。
 *
 * 导出是**不可逆的单向门**：包一旦发给别人就收不回来。所以这里钉住的是三条底线：
 *
 * 1. 绝不能把已删除的记录打进包（用户以为删掉了，结果在导出包里）；
 * 2. 单个附件文件丢失不能让整次导出失败（原文才是最重建不了的东西），
 *    但必须在清单与结果里如实记账，不能悄悄跳过；
 * 3. 写完必须把文件交出去 —— 包留在 cacheDir 里，系统随时可能回收。
 */
class ExportRecordsUseCaseTest {

    private lateinit var records: FakeRecordRepository
    private lateinit var assets: FakeAssetRepository
    private lateinit var tags: FakeTagRepository
    private lateinit var derived: FakeDerivedContentRepository
    private lateinit var collections: FakeCollectionRepository
    private lateinit var files: FakeLocalFileStore
    private lateinit var writer: FakeExportArchiveWriter

    @Before
    fun setUp() {
        records = FakeRecordRepository()
        assets = FakeAssetRepository()
        tags = FakeTagRepository()
        derived = FakeDerivedContentRepository()
        collections = FakeCollectionRepository()
        files = FakeLocalFileStore()
        writer = FakeExportArchiveWriter()
    }

    private fun useCase() = ExportRecordsUseCase(
        recordRepository = records,
        assetRepository = assets,
        tagRepository = tags,
        derivedContentRepository = derived,
        collectionRepository = collections,
        fileStore = files,
        writer = writer,
    )

    /** 直接写 `stored`：`observeAll` 就是读它，顺序即导出顺序。 */
    private fun given(vararg recordList: Record) {
        records.stored.value = recordList.toList()
    }

    private fun manifest(): ExportManifest {
        val json = requireNotNull(writer.manifestJson) { "writer 没有被调用，清单不存在" }
        return EXPORT_JSON.decodeFromString(ExportManifest.serializer(), json)
    }

    // ------------------------------------------------------------ 范围

    /**
     * 最关键的一条：已软删除的记录绝不能进包。
     * 假实现的 `observeAll` 故意**不**过滤软删除，这样测的是用例自己的防护
     * （真实仓储虽然也过滤了，但两层都在才算稳）。
     */
    @Test
    fun Export_skipsSoftDeletedRecords() = runBlocking {
        given(
            testRecord(id = "keep"),
            testRecord(id = "gone", isDeleted = true),
        )

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(1, result.recordCount)
        assertEquals(listOf("keep"), manifest().records.map { it.id })
    }

    @Test
    fun Export_emptyDatabaseStillWritesAManifest() = runBlocking {
        given()

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(0, result.recordCount)
        assertEquals(0, result.assetCount)
        assertTrue("没有记录也必须产出一个可读的包", writer.manifestJson != null)
        assertEquals(emptyList<ExportRecord>(), manifest().records)
    }

    // ------------------------------------------------------------ 清单内容

    @Test
    fun Export_manifestCarriesVersionTimeAndCounts() = runBlocking {
        given(testRecord(id = "r1"))

        useCase()(appVersion = "1.2.3", now = 1_700_000_000_000L)

        val manifest = manifest()
        assertEquals(ExportSchema.VERSION, manifest.schemaVersion)
        assertEquals(1_700_000_000_000L, manifest.exportedAt)
        assertEquals("1.2.3", manifest.appVersion)
        assertEquals(1, manifest.recordCount)
        assertFalse(manifest.includeDerivedContent)
    }

    @Test
    fun Export_includesTagsOfEachRecord() = runBlocking {
        given(testRecord(id = "r1"))
        tags.addUserTag("r1", "会议", now = 10L)

        useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(listOf("会议"), manifest().records.single().tags.map { it.name })
    }

    // ------------------------------------------------------------ 标签来源 × 派生开关

    /**
     * 回归（M16 审核 P1-1）：AI 标签与派生内容受**同一个**开关控制。
     *
     * 取消勾选"同时导出 AI 摘要、标签与识别出的文字"却在包里带着模型猜出来的分类，
     * 是用户明确选择排除的信息流出去 —— 而导出是收不回来的单向门。
     */
    @Test
    fun Export_aiTagsOmittedWhenDerivedContentOff() = runBlocking {
        given(testRecord(id = "r1"))
        tags.addUserTag("r1", "自己的", now = 10L)
        tags.replaceAiTags("r1", listOf("会议"), now = 10L)

        // 默认 includeDerivedContent = false
        useCase()(appVersion = "0.1.0", now = 100L)

        val exported = manifest().records.single().tags
        assertEquals("用户自己打的标签必须留下", listOf("自己的"), exported.map { it.name })
        assertEquals(listOf("USER"), exported.map { it.source })
    }

    @Test
    fun Export_aiTagsIncludedWhenDerivedContentOn() = runBlocking {
        given(testRecord(id = "r1"))
        tags.addUserTag("r1", "自己的", now = 10L)
        tags.replaceAiTags("r1", listOf("会议"), now = 10L)

        useCase()(
            options = ExportOptions(includeDerivedContent = true),
            appVersion = "0.1.0",
            now = 100L,
        )

        val exported = manifest().records.single().tags
        assertEquals(setOf("自己的", "会议"), exported.map { it.name }.toSet())
        assertEquals(
            "清单要能让读取方分辨哪些是模型猜的",
            setOf("USER", "AI"),
            exported.map { it.source }.toSet(),
        )
    }

    // ------------------------------------------------------------ 派生内容开关

    @Test
    fun Export_derivedOmittedByDefault() = runBlocking {
        given(testRecord(id = "r1"))
        derived.upsert(testDerived(recordId = "r1", contentType = DerivedType.SUMMARY))

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(1, result.recordCount)
        assertTrue("默认不导出 AI 派生内容", manifest().records.single().derived.isEmpty())
    }

    @Test
    fun Export_derivedIncludedWhenAsked() = runBlocking {
        given(testRecord(id = "r1"))
        derived.upsert(testDerived(recordId = "r1", content = "这是一段摘要"))

        useCase()(options = ExportOptions(includeDerivedContent = true), appVersion = "0.1.0", now = 100L)

        val manifest = manifest()
        assertTrue(manifest.includeDerivedContent)
        assertEquals("这是一段摘要", manifest.records.single().derived.single().content)
    }

    // ------------------------------------------------------------ 合集成员

    /**
     * 合集导出**只带成员引用**（`recordId` + `position` + `addedAt`），不复制内容：
     * 读取方在包里按 id 找得到那条记录，找不到就说明成员缺失。
     * 顺序必须与合集里的阅读顺序一致，否则导入方重建的合集是乱的。
     */
    @Test
    fun Export_collectionCarriesOrderedMemberReferences() = runBlocking {
        given(
            testRecord(id = "c1", type = RecordType.COLLECTION),
            testRecord(id = "m1"),
            testRecord(id = "m2"),
        )
        collections.members["c1"] = mutableListOf(
            CollectionMember("c1", "m2", position = 1, addedAt = 20L, snapshotFingerprint = null),
            CollectionMember("c1", "m1", position = 0, addedAt = 10L, snapshotFingerprint = null),
        )

        useCase()(appVersion = "0.1.0", now = 100L)

        val exported = manifest().records
        val collection = exported.single { it.id == "c1" }
        assertEquals(
            listOf("m1", "m2"),
            collection.collectionMembers.map { it.recordId },
        )
        assertEquals(listOf(0, 1), collection.collectionMembers.map { it.position })
        assertEquals(listOf(10L, 20L), collection.collectionMembers.map { it.addedAt })

        // 非合集记录不该背成员引用
        assertTrue(exported.filter { it.id != "c1" }.all { it.collectionMembers.isEmpty() })
    }

    /**
     * 已删除或不在包里的成员必须从引用中剔除：
     * 保留一个指向"包里不存在"的 id，导入方只能拿到悬空引用。
     * 已删除成员的内容仍会随其记录本身被排除，这是产品口径（导出不含已删记录）。
     */
    @Test
    fun Export_collectionDropsDeletedAndMissingMembers() = runBlocking {
        given(
            testRecord(id = "c1", type = RecordType.COLLECTION),
            testRecord(id = "m1"),
            testRecord(id = "gone", isDeleted = true),
        )
        collections.members["c1"] = mutableListOf(
            CollectionMember("c1", "m1", position = 0, addedAt = 10L, snapshotFingerprint = null),
            CollectionMember("c1", "gone", position = 1, addedAt = 20L, snapshotFingerprint = null),
            CollectionMember("c1", "never-saved", position = 2, addedAt = 30L, snapshotFingerprint = null),
        )

        useCase()(appVersion = "0.1.0", now = 100L)

        val collection = manifest().records.single { it.id == "c1" }
        assertEquals(listOf("m1"), collection.collectionMembers.map { it.recordId })
    }

    // ------------------------------------------------------------ 附件与缺失

    @Test
    fun Export_writesAssetsThatAreOnDisk() = runBlocking {
        given(testRecord(id = "r1"))
        val asset = testAsset(id = "a1", recordId = "r1", mimeType = "image/png")
        assets.save(asset)
        files.presentPaths += asset.localPath

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(1, result.assetCount)
        assertEquals(0, result.missingAssetCount)
        val entry = writer.assets.single()
        assertEquals("assets/r1/a1.png", entry.entryPath)
        assertEquals("源路径必须是磁盘上的绝对路径", asset.localPath, entry.sourcePath)
        assertTrue(manifest().records.single().assets.single().filePresent)
    }

    /**
     * 附件文件丢了不能阻断导出：清单里标 `filePresent=false`、计数带回界面。
     * "导出 20 条、其中 2 个附件已丢失" 远比 "导出失败，一条都拿不到" 有用。
     */
    @Test
    fun Export_missingAssetFileIsCountedAndNotWritten() = runBlocking {
        given(testRecord(id = "r1"))
        val present = testAsset(id = "a1", recordId = "r1")
        val gone = testAsset(id = "a2", recordId = "r1", mimeType = "image/jpeg")
        assets.save(present)
        assets.save(gone)
        files.presentPaths += present.localPath

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertEquals(2, result.assetCount)
        assertEquals(1, result.missingAssetCount)
        assertEquals(listOf("assets/r1/a1.png"), writer.assets.map { it.entryPath })

        // 清单要保留这条附件、并如实说明文件不在
        val exported = manifest().records.single().assets
        assertEquals(2, exported.size)
        assertEquals(listOf(true, false), exported.map { it.filePresent })
    }

    /**
     * 回归：落盘状态为 MISSING、但文件其实还在磁盘上（例如迁移过程留下的怪状态）。
     * 清单说"不可用"，就绝不能把它写进包 —— 否则包里多出一个清单从不提及的文件。
     */
    @Test
    fun Export_assetNotSavedIsNeverWrittenEvenIfFileExists() = runBlocking {
        given(testRecord(id = "r1"))
        val odd = testAsset(id = "a1", recordId = "r1", saveStatus = AssetSaveStatus.MISSING)
        assets.save(odd)
        files.presentPaths += odd.localPath

        val result = useCase()(appVersion = "0.1.0", now = 100L)

        assertTrue("清单说不可用，包里就不能有", writer.assets.isEmpty())
        assertEquals(1, result.missingAssetCount)
        assertFalse(manifest().records.single().assets.single().filePresent)
    }

    // ------------------------------------------------------------ 进度

    @Test
    fun Export_reportsProgressForEveryRecord() = runBlocking {
        given(testRecord(id = "r1"), testRecord(id = "r2"), testRecord(id = "r3"))
        val progress = mutableListOf<ExportProgress>()

        useCase()(appVersion = "0.1.0", now = 100L, onProgress = { progress += it })

        val collecting = progress.filter { it.phase == ExportPhase.COLLECTING }
        assertEquals("起始 + 每条一次", listOf(0, 1, 2, 3), collecting.map { it.done })
        assertTrue("总数要让界面能算百分比", collecting.all { it.total == 3 })
        assertTrue("最后要走到收尾阶段", progress.last().phase == ExportPhase.FINALIZING)
    }

    // ------------------------------------------------------------ 失败

    /** 写包失败必须上抛，由界面提示重试；**不能**返回一个"成功但没文件"的结果。 */
    @Test
    fun Export_writerFailurePropagates() = runBlocking {
        given(testRecord(id = "r1"))
        writer.failWith = IOException("磁盘满")

        val error = runCatching { useCase()(appVersion = "0.1.0", now = 100L) }.exceptionOrNull()

        assertTrue("写包失败必须上抛，不能被吞掉", error is IOException)
    }
}
