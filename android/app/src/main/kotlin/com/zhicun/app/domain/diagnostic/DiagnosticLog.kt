package com.zhicun.app.domain.diagnostic

/**
 * 领域层的诊断出口（2026-10-22 审核 P2：禁止空 catch 的配套件）。
 *
 * ## 为什么不是 android.util.Log
 * domain 层禁止导入 Android framework（M2 分层规则，ARCHITECTURE §2）。领域用例里
 * "捕获后必须留痕"的需求由本接口承载，实现（android.util.Log）放在 data 层。
 *
 * ## 语义
 * 只开 `w`（warn）一个级别：领域用例不负责分级，"这里发生了值得人看的事"本身就是级别。
 * 实现方保证不抛异常——诊断通道自己坏掉不能反过来打断业务。
 */
interface DiagnosticLog {

    fun w(tag: String, message: String, error: Throwable? = null)
}
