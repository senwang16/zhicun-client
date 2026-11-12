package com.zhicun.app.fake

import com.zhicun.app.domain.diagnostic.DiagnosticLog

/**
 * [DiagnosticLog] 假实现：记录调用供断言（"调度失败必须留痕"等行为）。
 */
class FakeDiagnosticLog : DiagnosticLog {

    data class Entry(val tag: String, val message: String, val error: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun w(tag: String, message: String, error: Throwable?) {
        entries += Entry(tag, message, error)
    }
}
