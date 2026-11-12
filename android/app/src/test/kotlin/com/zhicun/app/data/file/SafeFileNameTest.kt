package com.zhicun.app.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 落盘文件名生成规则。
 *
 * 外部文件名不可信：可能带路径穿越（`../../x`）、控制字符、超长 emoji。
 * 这些用例钉住"永远只会在目标目录里、永远有合法扩展名"这两条不变量。
 */
@RunWith(RobolectricTestRunner::class)
class SafeFileNameTest {

    @Test
    fun SafeFileName_keepsNormalNameAndExtension() {
        assertEquals("photo.jpg", ExternalAssetImporterImpl.safeFileName("photo.jpg", "image/jpeg"))
    }

    @Test
    fun SafeFileName_stripsPathTraversal() {
        val name = ExternalAssetImporterImpl.safeFileName("../../etc/passwd", "text/plain")

        assertFalse(name.contains("/"))
        assertFalse("不能出现向上跳目录", name.contains(".."))
        assertTrue(name.endsWith(".txt"))
    }

    @Test
    fun SafeFileName_chineseName_isPreservedWithExtension() {
        val name = ExternalAssetImporterImpl.safeFileName("我的截图.png", "image/png")

        // 中文属于 letter，保留可读性；扩展名来自原文件名，类型仍能识别
        assertEquals("我的截图.png", name)
    }

    @Test
    fun SafeFileName_emojiAndSymbols_areReplaced() {
        val name = ExternalAssetImporterImpl.safeFileName("🖼️截图 v2 (1).png", "image/png")

        assertFalse("emoji/括号不应进入文件名，实际 $name", name.contains("🖼"))
        assertFalse(name.contains("("))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun SafeFileName_missingExtension_derivesFromMime() {
        val name = ExternalAssetImporterImpl.safeFileName("download", "application/pdf")

        assertEquals("download.pdf", name)
    }

    @Test
    fun SafeFileName_noNameAtAll_fallsBackToAssetAndBin() {
        val name = ExternalAssetImporterImpl.safeFileName(null, "application/unknown-type")

        assertEquals("asset.bin", name)
    }

    @Test
    fun SafeFileName_veryLongName_isTruncated() {
        val name = ExternalAssetImporterImpl.safeFileName("a".repeat(200) + ".txt", "text/plain")

        assertTrue("文件名主体应被截断，实际 $name", name.length <= 50)
        assertTrue(name.endsWith(".txt"))
    }

    @Test
    fun SafeFileName_weirdExtension_isDropped() {
        val name = ExternalAssetImporterImpl.safeFileName("file.verylongextension", "image/png")

        // 超长扩展名不可信，改用 MIME 推导
        assertEquals("file.png", name)
    }
}
