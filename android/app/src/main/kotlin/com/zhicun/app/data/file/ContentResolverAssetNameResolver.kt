package com.zhicun.app.data.file

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.zhicun.app.domain.file.AssetNameResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AssetNameResolver] 的 Android 实现：`ContentResolver.query(OpenableColumns.DISPLAY_NAME)`。
 *
 * 走 IO 线程：查询要跨进程访问 provider，云盘类 document provider 可能几十毫秒。
 * 任何异常都降级为 `null` —— 名字只用于展示，拿不到就回退序号，不该拦住选择。
 */
@Singleton
class ContentResolverAssetNameResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AssetNameResolver {

    override suspend fun resolve(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val parsed = uri.toUri()
            context.contentResolver
                .query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 个别 provider 不支持 query 或已失去授权；日志只记脱敏后的 URI
            Log.d(TAG, "读取文件名失败，回退序号标签 uri=${LogSanitizer.uri(uri)}", e)
            null
        }
    }

    private companion object {
        const val TAG = "AssetNameResolver"
    }
}
