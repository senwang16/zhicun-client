package com.zhicun.app.data.file

import android.util.Log
import com.zhicun.app.domain.maintenance.LocalFileStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LocalFileStore] 的实现（M15）。
 *
 * ## 为什么全部走 `File` 的返回值而不是抛异常
 * 这三个动作的共同点是"失败很常见且不需要用户处理"：文件被别的进程占着、
 * 目录被系统清了、只读挂载。抛异常只会让调用方写一堆 try-catch，
 * 而它们能做的处理无非是"记为失败，继续下一条"。
 *
 * ## `delete` 的语义
 * 返回 true 表示"文件已经不在了"——本来就不存在也算成功。清理是幂等动作，
 * 第二次点"清理"不该因为它上次已经删过而报错。
 */
@Singleton
class AndroidLocalFileStore @Inject constructor() : LocalFileStore {

    override fun exists(path: String): Boolean = try {
        path.isNotBlank() && File(path).exists()
    } catch (e: SecurityException) {
        // 路径被系统保护 / 已脱离沙箱：按"不存在"处理，导出时标为缺失
        Log.w(TAG, "检查文件存在性失败 file=${LogSanitizer.path(path)}", e)
        false
    }

    override fun sizeBytes(path: String): Long = try {
        val length = File(path).length()
        if (length > 0) length else 0L
    } catch (e: SecurityException) {
        Log.w(TAG, "读取文件大小失败 file=${LogSanitizer.path(path)}", e)
        0L
    }

    override fun delete(path: String): Boolean = try {
        // 空路径下 `File("")` 指向**进程工作目录**，删它属于误伤。
        // 数据库里的 localPath 若因某次迁移变成空串，这里必须当"没什么可删的"，
        // 而不是去删一个碰巧是目录的路径。返回值仍是 true：清理是幂等的。
        if (path.isBlank()) {
            true
        } else {
            val file = File(path)
            !file.exists() || file.delete()
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "删除文件失败 file=${LogSanitizer.path(path)}", e)
        false
    }

    private companion object {
        const val TAG = "LocalFileStore"
    }
}
