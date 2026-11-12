package com.zhicun.app.data.export

import android.content.Context
import android.util.Log
import com.zhicun.app.domain.export.ArchiveAssetEntry
import com.zhicun.app.domain.export.ExportArchiveWriter
import com.zhicun.app.domain.export.ExportPhase
import com.zhicun.app.domain.export.ExportProgress
import com.zhicun.app.domain.export.WrittenArchive
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把清单与附件写成一个 ZIP（M15）。
 *
 * ## 为什么落在 `cacheDir/exports`
 * 导出物是**中间产物**：用户真正要的是"发出去"。放 cache 里系统能在空间不足时回收，
 * 不需要申请存储权限，也不会在用户相册/文件管理器里堆一堆没人管的压缩包。
 * 代价是系统可能提前清掉 —— 所以分享面板必须在导出完成后**立刻**弹出。
 *
 * ## 为什么写之前先清旧包
 * 用户可能一周导好几次，每次都是几十 MB。旧包在新包生成的那一刻就再没有任何引用
 * （重试分享的引用只在 ViewModel 内存里），继续留着只会让明文副本越堆越多 ——
 * 所以每次写入前把整个目录清空，任何时刻最多只存在一个导出包。
 *
 * ## 冷启动时清空
 * 见 [clear]：进程重启后没有任何东西还指向上次生成的包。
 * （2026-11-01 审核 S-4，自海外轨移植：导出包是全部记录的明文副本。）
 *
 * ## 失败时删掉半成品
 * 半个 ZIP 比没有 ZIP 更糟：用户分享出去、对方解压报错，问题会跑到无法排查的地方。
 */
@Singleton
class ZipExportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportArchiveWriter {

    override suspend fun write(
        manifestJson: String,
        assets: List<ArchiveAssetEntry>,
        onProgress: (ExportProgress) -> Unit,
    ): WrittenArchive = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        deleteArchives(dir)

        val target = File(dir, "zhicun-export-${System.currentTimeMillis()}.zip")
        try {
            writeArchive(target, manifestJson, assets, onProgress)
        } catch (e: IOException) {
            // 写坏了就删掉，别留给用户一个打不开的包
            target.delete()
            Log.e(TAG, "写导出包失败，已删除半成品", e)
            throw e
        }

        WrittenArchive(file = target, sizeBytes = target.length())
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        deleteArchives(File(context.cacheDir, EXPORT_DIR))
    }

    private fun writeArchive(
        target: File,
        manifestJson: String,
        assets: List<ArchiveAssetEntry>,
        onProgress: (ExportProgress) -> Unit,
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            // 清单先写：即使后面的附件写坏了，一个能读出"本该有哪些记录"的包也有救
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            assets.forEachIndexed { index, entry ->
                val source = File(entry.sourcePath)
                // 附件可能在"判定存在"与"真正写入"之间被后台清理/系统回收（TOCTOU）。
                // 单个附件丢失不该让整包失败 —— 更不该因此删掉已经写好的包
                // （原文才是最难重建的东西）。
                if (!source.exists()) {
                    Log.w(TAG, "附件在导出过程中丢失，已跳过：${entry.entryPath}")
                } else {
                    try {
                        source.inputStream().use { input ->
                            zip.putNextEntry(ZipEntry(entry.entryPath))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                    } catch (e: IOException) {
                        // 读一半失败：把当前条目收口后继续写下一个（不中断整包）
                        Log.w(TAG, "附件写入失败，已跳过：${entry.entryPath}", e)
                        runCatching { zip.closeEntry() }
                    }
                }
                onProgress(ExportProgress(ExportPhase.WRITING_ASSETS, index + 1, assets.size))
            }
        }
    }

    /** 清空导出目录。删不掉只记日志：下次冷启动/下次导出还会再试，不值得因此打断用户操作。 */
    private fun deleteArchives(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) Log.w(TAG, "导出包删不掉：${file.name}")
        }
    }

    private companion object {
        const val TAG = "ZipExportWriter"
        const val EXPORT_DIR = "exports"
        const val MANIFEST_ENTRY = "manifest.json"
    }
}
