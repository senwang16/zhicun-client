package com.zhicun.app.fake

import com.zhicun.app.domain.export.ArchiveAssetEntry
import com.zhicun.app.domain.export.ExportArchiveWriter
import com.zhicun.app.domain.export.ExportPhase
import com.zhicun.app.domain.export.ExportProgress
import com.zhicun.app.domain.export.WrittenArchive
import java.io.File
import kotlinx.coroutines.CompletableDeferred

/**
 * 写 ZIP 的假实现（M15）。
 *
 * 只记录"被要求写什么"，不真的压缩 —— 真压缩由一个单独的 Robolectric 测试覆盖
 * （`ZipExportWriterTest`）。这样导出用例的断言读的是**清单内容**，
 * 不用为了断言去解压一个临时文件。
 */
class FakeExportArchiveWriter(
    private val archive: File = File("/tmp/zhicun-export.zip"),
    private val sizeBytes: Long = 4096L,
) : ExportArchiveWriter {

    /** 收到的清单 JSON 原文。 */
    var manifestJson: String? = null

    /** 收到的附件条目。 */
    var assets: List<ArchiveAssetEntry> = emptyList()

    /** 被调用次数：用来断言"导出进行中时重复点击被忽略"。 */
    var writeCount: Int = 0

    /** 置为非 null 时 [write] 抛该异常。 */
    var failWith: Throwable? = null

    /** 置为非 null 时 [write] 会等它完成：用来制造"导出进行中"的中间态。 */
    var gate: CompletableDeferred<Unit>? = null

    /** [clear] 被调用次数（冷启动清空导出目录）。 */
    var clearCount: Int = 0

    override suspend fun clear() {
        clearCount++
    }

    override suspend fun write(
        manifestJson: String,
        assets: List<ArchiveAssetEntry>,
        onProgress: (ExportProgress) -> Unit,
    ): WrittenArchive {
        // 先计数再等待：卡在 gate 上时也要能看出"写操作已经被调用过一次"
        writeCount++
        gate?.await()
        failWith?.let { throw it }

        this.manifestJson = manifestJson
        this.assets = assets

        // 与真实实现一致：逐个附件上报写入进度
        assets.forEachIndexed { index, _ ->
            onProgress(ExportProgress(ExportPhase.WRITING_ASSETS, index + 1, assets.size))
        }

        return WrittenArchive(file = archive, sizeBytes = sizeBytes)
    }
}
