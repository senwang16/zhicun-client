package com.zhicun.app.fake

import com.zhicun.app.domain.ai.SpeechTranscriber

/**
 * 转写假实现（M9）。
 *
 * 用可变结果而不是 mock：转写 Worker 的"空串算成功 / 异常分类"两条规则
 * 依赖返回值与异常类型，假实现能同时表达这两件事。
 */
class FakeSpeechTranscriber : SpeechTranscriber {

    var result: String = "今天下午三点开会"

    /** 置为非 null 时抛出该异常（模拟未配置网关 / 网络故障 / 网关拒绝）。 */
    var failure: Throwable? = null

    /** 按音频路径返回不同结果（多音频测试）；未命中时退回 [result]。 */
    val resultByPath: MutableMap<String, String> = mutableMapOf()

    val calls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun transcribe(audioPath: String, mimeType: String): String {
        calls += audioPath to mimeType
        failure?.let { throw it }
        return resultByPath[audioPath] ?: result
    }
}
