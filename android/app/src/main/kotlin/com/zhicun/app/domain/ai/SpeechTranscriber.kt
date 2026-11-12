package com.zhicun.app.domain.ai

/**
 * 云端语音转写契约（T-006 决策：走 Cloudflare Workers 网关，端侧不捆绑模型）。
 *
 * 为什么抽象成接口：转写要联网，单测里既不能连真网关也不该依赖音频解码；
 * 而"没有音频附件""转写为空算成功""失败保留原音频"都是业务规则，必须能穷举。
 * Worker 只依赖这个接口，M9 的假实现让整条链路可以在 JVM 上验证。
 */
interface SpeechTranscriber {

    /**
     * 转写一段本地音频。
     *
     * @param audioPath 私有目录里的绝对路径
     * @param mimeType 音频真实类型（`audio/mp4` 等）
     * @return 转写文本；**空串表示"没有人声"**，是正常结果，不是失败
     * @throws GatewayNotConfiguredException 未配置网关
     * @throws GatewayRejectedException 网关拒绝（格式不支持、过大、鉴权失败）
     * @throws java.io.IOException 网络/超时等可重试故障
     */
    suspend fun transcribe(audioPath: String, mimeType: String): String
}
