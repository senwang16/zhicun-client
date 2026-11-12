package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.GatewayRejectedException
import com.zhicun.app.domain.ai.SpeechTranscriber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * 云端语音转写（M9，T-006 决策：网关代转，端侧不捆绑模型）。
 *
 * 请求形状：`POST {base}/api/v1/transcribe`，body 直接是音频字节（`audio/mp4`），
 * 鉴权用 Bearer。服务端返回 `{"text": "..."}`（也兼容 `{"result":{"text":"..."}}`）。
 *
 * 为什么把音频当**请求体**而不是 multipart/base64：
 * - 流式发送不占内存（10 分钟录音约 5MB，base64 会膨胀 33% 且需要整块驻留内存）；
 * - 网关侧读 `request.body` 即可转发给 ASR 供应商，无需解析 multipart。
 *
 * 体积上限与网关能力声明保持一致（20MB）：先本地判断，避免把 30MB 音频传完才被 413 拒绝。
 */
@Singleton
class RemoteSpeechTranscriber @Inject constructor(
    private val gateway: AiGatewayHttp,
) : SpeechTranscriber {

    override suspend fun transcribe(audioPath: String, mimeType: String): String {
        val file = File(audioPath)
        if (!file.exists()) {
            // 文件没了重试也没用，属确定性失败
            throw GatewayRejectedException("AUDIO_MISSING", "音频文件不存在：$audioPath")
        }
        if (file.length() > MAX_AUDIO_BYTES) {
            throw GatewayRejectedException("PAYLOAD_TOO_LARGE", "音频超过 ${MAX_AUDIO_BYTES / 1024 / 1024}MB")
        }

        val request = gateway.post(
            path = TRANSCRIBE_PATH,
            body = file.asRequestBody(mimeType.toMediaTypeOrNull()),
            contentType = mimeType,
        )

        return gateway.execute(request) { body ->
            val response = gateway.json.decodeFromString(TranscribeResponse.serializer(), body)
            // 空串是"没人声"，照原样返回：调用方把它当成功结果写入派生内容
            response.text ?: response.result?.text ?: ""
        }.trim()
    }

    @Serializable
    private data class TranscribeResponse(
        val text: String? = null,
        val result: TranscribeResult? = null,
    )

    @Serializable
    private data class TranscribeResult(
        val text: String? = null,
    )

    private companion object {
        const val TRANSCRIBE_PATH = "/api/v1/transcribe"

        /** 与网关能力声明 `max_upload_bytes` 对齐（20MB）。 */
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
    }
}
