package com.zhicun.app.data.export

import android.content.Context
import com.zhicun.app.domain.export.ArchiveAssetEntry
import com.zhicun.app.domain.export.ExportPhase
import com.zhicun.app.domain.export.ExportProgress
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 写 ZIP（M15）—— 真的压缩、真的落盘。
 *
 * 用例层用的是假 writer，所以"包到底能不能打开、里面是不是真有那些文件"
 * 只能在这里验证。三条不可退让的点：
 *
 * 1. 清单必须**第一个**写入（后面的附件写坏了，一个能读出"本该有哪些记录"的包也有救）；
 * 2. 写失败必须删掉半成品（半个 ZIP 分享出去，问题会跑到无法排查的地方）；
 * 3. 每次写入前清空旧包（导出包是全部记录的明文副本，任何时刻最多留一个）。
 */
@RunWith(RobolectricTestRunner::class)
class ZipExportWriterTest {

    private lateinit var context: Context
    private lateinit var writer: ZipExportWriter
    private lateinit var sources: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        writer = ZipExportWriter(context)
        sources = File(context.cacheDir, "test-sources").apply { mkdirs() }
    }

    private fun sourceFile(name: String, content: String): File =
        File(sources, name).apply { writeText(content) }

    private fun exportDir(): File = File(context.cacheDir, "exports")

    /** 解出 ZIP 里的条目（保持写入顺序）。 */
    private fun readEntries(archive: File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                result[entry.name] = zip.getInputStream(entry).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    fun Write_putsManifestFirstThenAssets() = runBlocking {
        val png = sourceFile("a.png", "PNG-BYTES")
        val audio = sourceFile("b.m4a", "AUDIO-BYTES")

        val archive = writer.write(
            manifestJson = """{"schemaVersion":1}""",
            assets = listOf(
                ArchiveAssetEntry("assets/r1/a.png", png.path),
                ArchiveAssetEntry("assets/r1/b.m4a", audio.path),
            ),
        )

        assertTrue(archive.file.exists())
        assertTrue("体积要如实回报，界面要显示", archive.sizeBytes > 0)

        val entries = readEntries(archive.file)
        assertEquals(
            "清单必须是第一个条目",
            listOf("manifest.json", "assets/r1/a.png", "assets/r1/b.m4a"),
            entries.keys.toList(),
        )
        assertEquals("""{"schemaVersion":1}""", entries["manifest.json"])
        assertEquals("PNG-BYTES", entries["assets/r1/a.png"])
        assertEquals("AUDIO-BYTES", entries["assets/r1/b.m4a"])
    }

    @Test
    fun Write_withNoAssetsStillProducesReadableArchive() = runBlocking {
        val archive = writer.write(manifestJson = "{}", assets = emptyList())

        assertEquals(listOf("manifest.json"), readEntries(archive.file).keys.toList())
    }

    @Test
    fun Write_reportsProgressPerAsset() = runBlocking {
        val a = sourceFile("a.png", "A")
        val b = sourceFile("b.png", "B")
        val progress = mutableListOf<ExportProgress>()

        writer.write(
            manifestJson = "{}",
            assets = listOf(
                ArchiveAssetEntry("assets/r1/a.png", a.path),
                ArchiveAssetEntry("assets/r1/b.png", b.path),
            ),
            onProgress = { progress += it },
        )

        assertEquals(listOf(1, 2), progress.map { it.done })
        assertTrue(progress.all { it.phase == ExportPhase.WRITING_ASSETS })
        assertTrue(progress.all { it.total == 2 })
    }

    @Test
    fun Write_usesDeterministicNameAndExtension() = runBlocking {
        val archive = writer.write(manifestJson = "{}", assets = emptyList())

        assertTrue(archive.file.name, archive.file.name.startsWith("zhicun-export-"))
        assertTrue(archive.file.name, archive.file.name.endsWith(".zip"))
        assertEquals("必须落在 cacheDir/exports", exportDir().absolutePath, archive.file.parentFile?.absolutePath)
    }

    // ------------------------------------------------------------ 失败路径

    /**
     * 附件源文件不见了：**跳过该附件，整包仍然成功**（2026-11-05 移植海外轨 BUG-04）。
     *
     * 旧语义是"整个导出失败 + 删掉半成品包"，但它把**原文**一起扔了 ——
     * 原文才是最难重建的东西，而缺失的那个附件可能只是被系统清理过。
     * 清单先写、附件逐个跳过，用户至少能拿回全部文字内容。
     */
    @Test
    fun Write_missingAsset_isSkippedWithoutFailingArchive() = runBlocking {
        val missing = File(sources, "not-there.png").path

        val archive = writer.write(
            manifestJson = "{}",
            assets = listOf(ArchiveAssetEntry("assets/r1/missing.png", missing)),
        )

        assertTrue("整包必须成功产出", archive.file.exists())
        val entries = readEntries(archive.file)
        assertEquals("清单必须在", setOf("manifest.json"), entries.keys)
        assertTrue("缺失附件不应写进包", "assets/r1/missing.png" !in entries.keys)
    }

    /** 一个附件缺失、另一个正常：正常那个必须照常写进包，不能因同批缺失而丢。 */
    @Test
    fun Write_missingAsset_doesNotDropSiblings() = runBlocking {
        val good = sourceFile("good.png", "GOOD")
        val missing = File(sources, "not-there.png").path

        val archive = writer.write(
            manifestJson = "{}",
            assets = listOf(
                ArchiveAssetEntry("assets/r1/missing.png", missing),
                ArchiveAssetEntry("assets/r1/good.png", good.path),
            ),
        )

        val entries = readEntries(archive.file)
        assertEquals(setOf("manifest.json", "assets/r1/good.png"), entries.keys)
        assertEquals("GOOD", entries.getValue("assets/r1/good.png"))
    }

    // ------------------------------------------------------------ 旧包清理

    /**
     * 每次写入前清空导出目录（2026-11-01 审核 S-4，自海外轨移植）。
     *
     * 导出包是全部记录的明文副本；旧包在新包生成的那一刻就再没有任何引用，
     * 留着只会让明文副本越堆越多。任何时刻最多只存在一个导出包。
     */
    @Test
    fun Write_removesPreviousArchives_evenIfFresh() = runBlocking {
        val dir = exportDir().apply { mkdirs() }
        val previous = File(dir, "zhicun-export-previous.zip").apply { writeText("previous") }

        val archive = writer.write(manifestJson = "{}", assets = emptyList())

        assertFalse("旧包必须清掉", previous.exists())
        assertTrue(archive.file.exists())
        assertEquals(
            "目录里只该剩刚写出的那一个包",
            listOf(archive.file.name),
            dir.listFiles()?.map { it.name },
        )
    }

    @Test
    fun Clear_removesEverythingInExportDir() = runBlocking {
        val dir = exportDir().apply { mkdirs() }
        val first = File(dir, "zhicun-export-a.zip").apply { writeText("a") }
        val second = File(dir, "zhicun-export-b.zip").apply { writeText("b") }

        writer.clear()

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertTrue("目录里不该再有导出包", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun Clear_withoutExportDir_doesNotThrow() = runBlocking {
        assertFalse(exportDir().exists())

        writer.clear()
    }
}
