package com.zhicun.app.data.file

import com.zhicun.app.domain.file.AssetTooLargeException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 流 → 文件的复制内核（纯 JVM，无 Android 依赖，可直接单测）。
 *
 * 三件事一起做，避免为了算哈希把文件读两遍：
 * 1. 边写边累计字节数，超过 [maxBytes] 立即中止并删掉半截文件；
 * 2. 边写边喂 SHA-256（供 M6 之后判断"同一附件是否已处理过"）；
 * 3. 只有完整读完才返回，中途异常时目标文件已被清理，不留垃圾。
 */
internal object FileCopy {

    data class Outcome(val sizeBytes: Long, val sha256: String)

    private const val BUFFER_SIZE = 64 * 1024

    fun copy(input: InputStream, target: File, maxBytes: Long): Outcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw AssetTooLargeException(
                            limitBytes = maxBytes,
                            message = "附件超过 ${maxBytes / 1024 / 1024} MB 上限，已中止复制",
                        )
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (e: Exception) {
            // 失败必须清理半截文件：否则用户会看到"文件存在但打不开"，比没有更糟
            target.delete()
            throw e
        }
        return Outcome(sizeBytes = total, sha256 = digest.digest().toHexString())
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }
}
