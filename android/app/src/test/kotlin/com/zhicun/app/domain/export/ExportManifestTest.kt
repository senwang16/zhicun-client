package com.zhicun.app.domain.export

import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出清单的结构（M15）。
 *
 * 清单是导出包的**唯一入口**：读取方（可能是另一个 App、也可能是一年后的自己）
 * 先读它、再按 `entryPath` 找文件。因此这里钉的不是"能序列化"，而是三条硬约束：
 *
 * 1. 版本号必须始终写出来（缺了它就等于没有版本号，将来无法按版本分流）；
 * 2. 枚举一律存字符串（改枚举名不该让旧导出文件失效）；
 * 3. "文件在不在"只有一条判据（清单说了谎，包里那个文件永远不会被发现）。
 */
class ExportManifestTest {

    private fun encode(manifest: ExportManifest): String =
        EXPORT_JSON.encodeToString(ExportManifest.serializer(), manifest)

    private fun decode(json: String): ExportManifest =
        EXPORT_JSON.decodeFromString(ExportManifest.serializer(), json)

    private fun manifest(vararg records: ExportRecord) = ExportManifest(
        exportedAt = 1_700_000_000_000L,
        appVersion = "0.1.0",
        includeDerivedContent = false,
        recordCount = records.size,
        assetCount = records.sumOf { it.assets.size },
        missingAssetCount = records.sumOf { r -> r.assets.count { !it.filePresent } },
        records = records.toList(),
    )

    // ------------------------------------------------------------ 版本与结构

    @Test
    fun Manifest_schemaVersionIsAlwaysWritten() {
        // schemaVersion 有默认值：不开 encodeDefaults 会被整段省略。
        // 这里写死数字（而不是引用 ExportSchema.VERSION）：升版本时这条断言必须一起改，
        // 提醒作者"旧导出文件还有人在读，别静默改结构"。
        val json = encode(manifest())

        assertTrue(json, json.contains("\"schemaVersion\": 3"))
    }

    /**
     * 归档记录照样进导出包，但必须带 `archivedAt`（2026-10-24 用户裁决）。
     *
     * 导出是**备份**语义：用户换手机时不能因为"我把它收起来了"就少一批数据。
     * 而归档在 App 里表现为"搜不到、不参与相似推荐"，读取方需要这个字段才能还原同样的视图。
     */
    @Test
    fun Manifest_archivedRecordCarriesArchivedAt() {
        val archived = buildExportRecord(
            record = testRecord(id = "r1", archivedAt = 1_700_000_000_000L),
            tags = emptyList(),
            assets = emptyList(),
            derived = emptyList(),
        ) { true }

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), archived)

        assertTrue(json, json.contains("\"archivedAt\": 1700000000000"))
        assertEquals(1_700_000_000_000L, archived.archivedAt)
    }

    /**
     * 合集成员只写引用（id + 顺序 + 入组时间），不复制成员内容。
     *
     * 这是 v3 的核心约定：清单变大、内容重复都不该发生；读取方按 `recordId`
     * 去包里找对应记录即可。
     */
    @Test
    fun Manifest_collectionMembersStoreReferencesOnly() {
        val collection = buildExportRecord(
            record = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = "合集说明"),
            tags = emptyList(),
            assets = emptyList(),
            derived = emptyList(),
            filePresent = { true },
            collectionMembers = listOf(
                ExportCollectionMember(recordId = "m1", position = 0, addedAt = 10L),
                ExportCollectionMember(recordId = "m2", position = 1, addedAt = 20L),
            ),
        )

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), collection)

        assertTrue(json, json.contains("\"recordId\": \"m1\""))
        assertTrue(json, json.contains("\"position\": 1"))
        assertEquals("合集说明", collection.rawText)
        assertEquals(2, collection.collectionMembers.size)
    }

    /** 未归档的记录不该出现这个字段：`explicitNulls = false` 会整段省略，包更小也更好读。 */
    @Test
    fun Manifest_activeRecordOmitsArchivedAt() {
        val active = buildExportRecord(
            record = testRecord(id = "r1"),
            tags = emptyList(),
            assets = emptyList(),
            derived = emptyList(),
        ) { true }

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), active)

        assertFalse(json, json.contains("archivedAt"))
    }

    @Test
    fun Manifest_roundTripPreservesEveryField() {
        val record = buildExportRecord(
            record = testRecord(id = "r1", sourceUrl = "https://example.com", sourceTitle = "标题"),
            tags = listOf(Tag("t1", "会议", TagSource.USER, null, 5L)),
            assets = listOf(testAsset(id = "a1", recordId = "r1")),
            derived = listOf(testDerived(recordId = "r1")),
        ) { true }
        val original = manifest(record)

        val restored = decode(encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun Manifest_enumValuesAreWrittenAsNames() {
        val record = buildExportRecord(
            record = testRecord(
                type = RecordType.VOICE_NOTE,
                captureSource = CaptureSource.FLOATING,
                sensitivityStatus = SensitivityStatus.SUSPECTED,
            ),
            tags = emptyList(),
            assets = emptyList(),
            derived = listOf(testDerived(contentType = DerivedType.SUMMARY)),
        ) { true }

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), record)

        // 存名字而不是序号：将来往枚举中间插一个值，旧文件依然能读
        assertTrue(json, json.contains("\"type\": \"VOICE_NOTE\""))
        assertTrue(json, json.contains("\"captureSource\": \"FLOATING\""))
        assertTrue(json, json.contains("\"sensitivityStatus\": \"SUSPECTED\""))
        assertTrue(json, json.contains("\"contentType\": \"SUMMARY\""))
    }

    @Test
    fun Manifest_nullRawTextIsOmittedEntirely() {
        val record = buildExportRecord(
            record = testRecord(rawText = null),
            tags = emptyList(),
            assets = emptyList(),
            derived = emptyList(),
        ) { true }

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), record)

        assertFalse("null 字段省略，文件更小也更好读", json.contains("rawText"))
    }

    /** 派生内容失败时 content 是 null，但 errorMessage 必须留下 —— 那是唯一的线索。 */
    @Test
    fun Manifest_derivedErrorIsKeptEvenWithoutContent() {
        val record = buildExportRecord(
            record = testRecord(),
            tags = emptyList(),
            assets = emptyList(),
            derived = listOf(testDerived(content = null, errorMessage = "云端返回 502")),
        ) { true }

        val json = EXPORT_JSON.encodeToString(ExportRecord.serializer(), record)

        assertFalse(json, json.contains("\"content\""))
        assertTrue(json, json.contains("云端返回 502"))
    }

    // ------------------------------------------------------------ 附件路径

    @Test
    fun AssetEntryPath_usesAssetIdAndMimeExtension() {
        val asset = testAsset(id = "a1", recordId = "r1", mimeType = "image/png")

        assertEquals("assets/r1/a1.png", assetEntryPath("r1", asset))
    }

    /** 扩展名推不出来时不带扩展名：宁可没有，也不要写一个错的让解压工具猜。 */
    @Test
    fun AssetEntryPath_unknownMimeHasNoExtension() {
        val asset = testAsset(id = "a1", recordId = "r1", mimeType = "application/x-weird")

        assertEquals("assets/r1/a1", assetEntryPath("r1", asset))
    }

    @Test
    fun AssetExtension_mapsKnownTypesCaseInsensitively() {
        assertEquals(".jpg", assetExtension("image/jpeg"))
        assertEquals(".jpg", assetExtension("IMAGE/JPEG"))
        assertEquals(".webp", assetExtension("image/webp"))
        assertEquals(".pdf", assetExtension("application/pdf"))
        assertEquals(".m4a", assetExtension("audio/mp4"))
        assertEquals(".amr", assetExtension("audio/amr"))
        assertEquals("", assetExtension(""))
    }

    // ------------------------------------------------------------ 附件可用性

    /**
     * 清单里的 `filePresent` 决定读取方要不要去包里找文件。
     * 若它说 false 而实际写进了包，那个文件就是永远找不到的死内容。
     */
    @Test
    fun IsAssetAvailable_requiresSavedStatusAndExistingFile() {
        val saved = testAsset(saveStatus = AssetSaveStatus.SAVED)
        val failed = testAsset(saveStatus = AssetSaveStatus.FAILED)
        val missing = testAsset(saveStatus = AssetSaveStatus.MISSING)

        assertTrue(isAssetAvailable(saved) { true })
        assertFalse("文件不在：不能声明可用", isAssetAvailable(saved) { false })
        assertFalse("落盘失败：打进去的是坏文件", isAssetAvailable(failed) { true })
        assertFalse(isAssetAvailable(missing) { true })
    }

    @Test
    fun BuildExportRecord_missingStatusWinsOverExistingFile() {
        val record = buildExportRecord(
            record = testRecord(id = "r1"),
            tags = emptyList(),
            assets = listOf(testAsset(id = "a1", recordId = "r1", saveStatus = AssetSaveStatus.MISSING)),
            derived = emptyList(),
        ) { true }

        assertFalse(record.assets.single().filePresent)
    }

    /** 文件缺失也要在清单里留一条：用户得知道"曾经有这么个附件"。 */
    @Test
    fun BuildExportRecord_keepsAssetEntryWhenFileIsGone() {
        val record = buildExportRecord(
            record = testRecord(id = "r1"),
            tags = emptyList(),
            assets = listOf(testAsset(id = "a1", recordId = "r1", fileSize = 8192L)),
            derived = emptyList(),
        ) { false }

        val asset = record.assets.single()
        assertEquals("assets/r1/a1.png", asset.entryPath)
        assertEquals(8192L, asset.fileSize)
        assertFalse(asset.filePresent)
    }

    // ------------------------------------------------------------ 内容保真

    /** 导出的意义就是"拿到我当初存的东西"，任何清洗都是数据损失。 */
    @Test
    fun BuildExportRecord_copiesRawTextVerbatim() {
        val raw = "  开头有空格\n\n结尾有换行  \n"

        val record = buildExportRecord(
            record = testRecord(rawText = raw),
            tags = emptyList(),
            assets = emptyList(),
            derived = emptyList(),
        ) { true }

        assertEquals(raw, record.rawText)
    }

    @Test
    fun BuildExportRecord_keepsTagSourceSoUserCanTellWhoWroteIt() {
        val record = buildExportRecord(
            record = testRecord(),
            tags = listOf(
                Tag("t1", "我加的", TagSource.USER, null, 1L),
                Tag("t2", "AI 猜的", TagSource.AI, 0.82, 2L),
            ),
            assets = emptyList(),
            derived = emptyList(),
        ) { true }

        assertEquals(listOf("USER", "AI"), record.tags.map { it.source })
        assertEquals(listOf("我加的", "AI 猜的"), record.tags.map { it.name })
    }

    @Test
    fun ExportProgress_fractionGuardsAgainstZeroTotal() {
        assertEquals(0f, ExportProgress(ExportPhase.COLLECTING, done = 0, total = 0).fraction)
        assertEquals(0.5f, ExportProgress(ExportPhase.WRITING_ASSETS, done = 2, total = 4).fraction)
        // 越界不能出现 >1 的进度，否则进度条会画到框外
        assertEquals(1f, ExportProgress(ExportPhase.WRITING_ASSETS, done = 9, total = 4).fraction)
    }
}
