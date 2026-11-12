package com.zhicun.app.data.file

import com.zhicun.app.domain.file.AssetTooLargeException
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 复制内核测试（纯 JVM）。
 *
 * 这块逻辑是"附件能不能存住"的最后一道关：体积上限必须真的拦住、
 * 失败时不能留下半截文件、哈希必须和实际内容一致。
 */
class FileCopyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun target(): File = temporaryFolder.newFile("out.bin")

    @Test
    fun Copy_normalStream_writesAllBytesAndHash() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = target()

        val outcome = FileCopy.copy(ByteArrayInputStream(payload), file, maxBytes = 1024 * 1024)

        assertEquals(4096L, outcome.sizeBytes)
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payload.toSha256(), outcome.sha256)
    }

    @Test
    fun Copy_emptyStream_producesEmptyFileWithEmptyHash() {
        val file = target()

        val outcome = FileCopy.copy(ByteArrayInputStream(ByteArray(0)), file, maxBytes = 1024)

        assertEquals(0L, outcome.sizeBytes)
        assertEquals(0L, file.length())
        assertEquals(ByteArray(0).toSha256(), outcome.sha256)
    }

    @Test
    fun Copy_exceedsLimit_throwsAndDeletesPartialFile() {
        val file = target()
        val payload = ByteArray(2048)

        val error = runCatching {
            FileCopy.copy(ByteArrayInputStream(payload), file, maxBytes = 1024)
        }.exceptionOrNull()

        assertTrue("应抛 AssetTooLargeException，实际 $error", error is AssetTooLargeException)
        assertEquals(1024L, (error as AssetTooLargeException).limitBytes)
        assertFalse("失败后不能留下半截文件", file.exists())
    }

    @Test
    fun Copy_exactlyAtLimit_succeeds() {
        val file = target()
        val payload = ByteArray(1024)

        val outcome = FileCopy.copy(ByteArrayInputStream(payload), file, maxBytes = 1024)

        assertEquals(1024L, outcome.sizeBytes)
        assertTrue(file.exists())
    }

    private fun ByteArray.toSha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}
