package com.zhicun.app.data.diagnostic

import android.util.Log
import com.zhicun.app.domain.diagnostic.DiagnosticLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DiagnosticLog] 的 android.util.Log 实现。
 *
 * 只在 data/di 层使用；domain 测试注入假实现（Log 在未 mock 的 android.jar 里
 * 会抛 RuntimeException，这是项目已知坑 #3，domain 测试因此保持纯 JVM）。
 */
@Singleton
class AndroidDiagnosticLog @Inject constructor() : DiagnosticLog {

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error != null) {
            Log.w(tag, message, error)
        } else {
            Log.w(tag, message)
        }
    }
}
