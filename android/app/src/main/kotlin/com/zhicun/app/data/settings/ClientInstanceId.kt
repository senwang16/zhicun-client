package com.zhicun.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端实例标识（2026-11-05 防御加固）。
 *
 * ## 用途
 * 国内网关按「路由 + 令牌指纹」限流，而国内包**所有用户共享同一个 Bearer token** ——
 * 只按令牌计数会让全部用户落进同一个桶：一个人刷满配额，其他人一起被 429。
 * 这里生成一个**每安装唯一**的随机串随请求头 `X-Client-Id` 发出，让网关按实例分桶。
 *
 * ## 为什么不是隐私问题
 * - 它是本地随机 UUID，不含设备号、IMEI、Android ID 或任何账号信息；
 * - 它与用户身份无关，卸载重装即变，换不回历史行为；
 * - 只发给自家网关，不进入任何第三方。
 * 因此它属于"设备级会话标识"，与架构约束里的"不做用户画像"不冲突。
 *
 * ## 为什么用 SharedPreferences 而不是 Keystore
 * 它**不是机密**：泄露它最多让人冒用某个实例的限流配额，而配额本身
 * 还有全局总闸兜底。为它引入 Keystore 加解密只会增加启动开销。
 */
@Singleton
class ClientInstanceId @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 本安装的实例标识；首次访问时生成并持久化。
     *
     * 用 `synchronized` 而不是 `lazy`：首次访问可能同时来自多个工作线程
     * （摘要/转写/向量三个 Worker 并发），双重生成会得到两个不同的 ID，
     * 一半请求落在另一个桶里，限流就分裂了。
     */
    val value: String
        get() = synchronized(lock) {
            prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { generated ->
                    prefs.edit { putString(KEY_CLIENT_ID, generated) }
                }
        }

    private companion object {
        const val PREFS_NAME = "client_instance"
        const val KEY_CLIENT_ID = "client_id"
        val lock = Any()
    }
}
