package com.zhicun.app.data.repository

import android.util.Log
import com.zhicun.app.domain.repository.DraftRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于文件的草稿实现（[DraftRepository]）。
 *
 * 为什么用裸文件而不是 DataStore 或 Room：
 * - 草稿只有"一段文本 + 一个槽位"，为一个槽位引入 DataStore 依赖、或给 Room 加表并处理
 *   schema 迁移，代价都大于收益；
 * - 裸 `File` 让这个类可以在**纯 JVM** 下测试（传入临时目录即可），不需要 Robolectric。
 *
 * 写入用「临时文件 + rename」：直接覆盖写时若进程被杀，会留下半截文本，
 * 而 rename 在同一文件系统内是原子的，读到的要么是旧草稿要么是新草稿。
 */
class FileDraftRepository(
    private val draftFile: File,
) : DraftRepository {

    override suspend fun loadText(): String? = withContext(Dispatchers.IO) {
        if (!draftFile.exists()) return@withContext null
        try {
            draftFile.readText()
                .take(MAX_CHARS)
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // 草稿读不出来不该阻塞新建流程：当作没有草稿，但必须留痕
            Log.w(TAG, "读取草稿失败 file=${draftFile.absolutePath}", e)
            null
        }
    }

    override suspend fun saveText(text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            clearInternal()
            return@withContext
        }
        try {
            draftFile.parentFile?.mkdirs()
            val temp = File(draftFile.parentFile, "${draftFile.name}.tmp")
            temp.writeText(text.take(MAX_CHARS))
            if (!temp.renameTo(draftFile)) {
                // rename 失败（例如目标被占用）时退回直接写，宁可非原子也不能丢草稿
                Log.w(TAG, "草稿 rename 失败，退回直接写入 file=${draftFile.absolutePath}")
                draftFile.writeText(text.take(MAX_CHARS))
                temp.delete()
            }
        } catch (e: Exception) {
            // 草稿是"锦上添花"：写失败不能让用户正在编辑的内容受影响
            Log.w(TAG, "写入草稿失败 file=${draftFile.absolutePath}", e)
        }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { clearInternal() }

    private fun clearInternal() {
        if (draftFile.exists() && !draftFile.delete()) {
            Log.w(TAG, "草稿文件删除失败 file=${draftFile.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "DraftRepository"

        /** 草稿上限：足够放一屏长文，又不会让"每次按键都写盘"变得昂贵。 */
        const val MAX_CHARS = 100_000
    }
}
