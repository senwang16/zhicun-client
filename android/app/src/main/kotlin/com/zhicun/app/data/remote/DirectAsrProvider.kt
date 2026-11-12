package com.zhicun.app.data.remote

import com.zhicun.app.domain.ai.AiProviderSettings
import com.zhicun.app.domain.ai.GatewayNotConfiguredException
import com.zhicun.app.domain.ai.GatewayRejectedException
import com.zhicun.app.domain.ai.SpeechTranscriber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * 直连 OpenAI 兼容 `audio/transcriptions` 的语音转写实现（开源改造 P0·模式 B）。
 *
 * ## 与官方网关的协议**不能混用**
 * 官方网关收的是**裸音频字节**（`request.body` 直接是音频，Content-Type 为 `audio/mp4`），
 * 因为网关要原样转发给上游、不想解析 multipart；
 * OpenAI 标准接口收的是 **multipart/form-data**（`file` + `model` 字段）。
 * 两套协议长得像，混用的结果是对端 400 且错误信息含糊 —— 这是本节最容易被改错的地方。
 *
 * ## 体积上限与官方一致（20MB）
 * 先在本地判断：把 30MB 音频传完再被 413 拒绝，用户等的是几分钟而不是 18 毫秒。
 *
 * ## 空文本是成功
 * 静音录音、纯环境音的转写结果是空串，照原样返回，由调用方写入派生内容
 * （见 `TranscribeWorker` 的三条判定）。
 */
@Singleton
class DirectAsrProvider @Inject constructor(
    private val gateway: AiGatewayHttp,
    private val settings: AiProviderSettings,
) : SpeechTranscriber {

    override suspend fun transcribe(audioPath: String, mimeType: String): String {
        val config = settings.asrConfig ?: throw GatewayNotConfiguredException()

        val file = File(audioPath)
        if (!file.exists()) {
            // 文件没了重试也没用，属确定性失败
            throw GatewayRejectedException("AUDIO_MISSING", "音频文件不存在：$audioPath")
        }
        if (file.length() > MAX_AUDIO_BYTES) {
            throw GatewayRejectedException(
                "PAYLOAD_TOO_LARGE",
                "音频超过 ${MAX_AUDIO_BYTES / 1024 / 1024}MB",
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // 文件名带真实扩展名：多数供应商按扩展名判断容器（whisper 系尤其如此）
            .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .addFormDataPart("model", config.model)
            .build()

        // 不传 contentType：multipart 的 Content-Type 必须带 boundary，由 OkHttp 自己写
        val request = gateway.postProvider(
            config = config,
            path = TRANSCRIBE_PATH,
            body = body,
        )

        return gateway.execute(request) { raw ->
            val response = gateway.json.decodeFromString(TranscribeResponse.serializer(), raw)
            // 空串是"没人声"，照原样返回
            response.text ?: response.result?.text ?: ""
        }.trim()
    }

    @Serializable
    private data class TranscribeResponse(
        val text: String? = null,
        /** 部分供应商沿用 `result` 包装（与官方网关的兼容形态保持一致）。 */
        val result: TranscribeResult? = null,
    )

    @Serializable
    private data class TranscribeResult(val text: String? = null)

    companion object {
        /** 设置页 & 文档共用：相对用户填的 baseUrl。 */
        const val TRANSCRIBE_PATH: String = "/audio/transcriptions"

        /** 与官方网关 `max_upload_bytes` 对齐（20MB）。 */
        const val MAX_AUDIO_BYTES: Long = 20L * 1024 * 1024
    }
}