package com.zhicun.app.fake

import com.zhicun.app.domain.ai.ContentEnricher
import com.zhicun.app.domain.ai.Enrichment
import com.zhicun.app.domain.settings.AppLanguage

/**
 * 内容增强器假实现（M11）。
 *
 * 只记录"被调用时的输入"并返回预置结果，不做任何 HTTP ——
 * `SummarizeWorker` 的判定（敏感门禁、正文拼接、上游未就绪、标签截断）因此能在 JVM 上穷举。
 */
class FakeContentEnricher : ContentEnricher {

    var result: Enrichment = Enrichment(
        title = "语音会议纪要",
        summary = "讨论了三点：范围、时间、负责人。",
        tags = listOf("会议", "待办"),
    )

    /** 置为非 null 时 [enrich] 抛该异常，用于测失败分类。 */
    var failure: Throwable? = null

    /** 每次调用的输入文本，按调用顺序记录。 */
    val calls: MutableList<String> = mutableListOf()

    /** 每次调用携带的输出语言（P11-07），用于断言任务快照真的传到了 Provider。 */
    val languages: MutableList<AppLanguage> = mutableListOf()

    override suspend fun enrich(text: String, language: AppLanguage): Enrichment {
        calls += text
        languages += language
        failure?.let { throw it }
        return result
    }
}
