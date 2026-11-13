package com.zhicun.app.data.file

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.zhicun.app.domain.file.MimeTypeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MimeTypeResolver] 的 Android 实现：`ContentResolver.getType`。
 *
 * 放在 IO 线程：`getType` 要走 provider 进程间调用，个别 provider（尤其是云盘类 App 的
 * document provider）会有几十毫秒延迟，在主线程调用会有卡顿风险。
 */
@Singleton
class ContentResolverMimeTypeResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MimeTypeResolver {

    override suspend fun resolve(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.getType(uri.toUri())
        } catch (e: Exception) {
            // 无授权时部分 provider 抛 SecurityException；查不到类型不该让保存流程失败，
            // 后续导入器还会再查一次，最终兜底 application/octet-stream
            // 日志只记来源 provider，不记完整 URI（可能含资源标识/临时授权，审核要求脱敏）
            Log.d(TAG, "查询 URI 类型失败，按未知类型处理 uri=${LogSanitizer.uri(uri)}", e)
            null
        }
    }

    private companion object {
        const val TAG = "MimeTypeResolver"
    }
}
