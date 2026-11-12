package com.zhicun.app.data.web

import android.util.Log
import com.zhicun.app.domain.web.FetchedImage
import com.zhicun.app.domain.web.ImageFetcher
import com.zhicun.app.domain.web.UnsafeUrlException
import com.zhicun.app.domain.web.UnsupportedContentException
import com.zhicun.app.domain.web.UrlCheck
import com.zhicun.app.domain.web.UrlChecker
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp 图片抓取（C：og:image 离线保存）。
 *
 * 抓取模式与 [OkHttpWebPageLoader] 完全一致——关自动重定向、每一跳先过 [UrlChecker]、
 * 连接目标钉在校验过的 IP 上、读满上限就停。og:image 的地址是从页面里抽出来的
 * 第二跳地址，SSRF 风险与首跳相同，一条约束都不能省。
 *
 * 与加载器的差别只有两处：Content-Type 只接受图片类型；图片被截断没有意义
 * （HTML 截断仍可解析，半张图什么都不是），超限直接失败。
 */
@Singleton
class OkHttpImageFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val urlChecker: UrlChecker,
) : ImageFetcher {

    override suspend fun fetch(url: String): FetchedImage = withContext(Dispatchers.IO) {
        var current = url

        repeat(MAX_REDIRECTS + 1) {
            val allowed = when (val check = urlChecker.check(current)) {
                is UrlCheck.Rejected -> throw UnsafeUrlException(check.reason)
                is UrlCheck.Allowed -> check
            }

            when (val hop = requestOnce(allowed)) {
                is Hop.Image -> return@withContext hop.image
                is Hop.Redirect -> {
                    Log.i(TAG, "跟随重定向 ${hop.nextUrl}")
                    current = hop.nextUrl
                }
            }
        }

        throw IOException("图片重定向次数超过 $MAX_REDIRECTS 次")
    }

    private fun requestOnce(allowed: UrlCheck.Allowed): Hop {
        val request = Request.Builder()
            .url(allowed.url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        // 与页面加载器同款：钉死校验过的地址，防两次 DNS 解析之间的重绑定
        val callClient = if (allowed.pinnedAddresses.isEmpty()) {
            client
        } else {
            client.newBuilder()
                .dns(PinnedDns(request.url.host, allowed.pinnedAddresses))
                .build()
        }

        callClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: throw IOException("图片重定向缺少 Location")
                val next = response.request.url.resolve(location)?.toString()
                    ?: throw IOException("无法解析图片重定向地址")
                return Hop.Redirect(next)
            }

            if (!response.isSuccessful) {
                throw IOException("图片请求 HTTP ${response.code}")
            }

            val contentType = response.header("Content-Type")
                ?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (!contentType.startsWith("image/")) {
                throw UnsupportedContentException(
                    "og:image 指向的不是图片：${contentType.ifBlank { "未知类型" }}",
                )
            }

            val body = response.body ?: throw IOException("图片响应没有正文")
            val bytes = body.byteStream().use { readAtMost(it, MAX_BYTES) }
            if (bytes.size >= MAX_BYTES) {
                throw IOException("图片超过 ${MAX_BYTES / 1024} KB 上限")
            }
            return Hop.Image(FetchedImage(bytes = bytes, mimeType = contentType))
        }
    }

    private sealed interface Hop {

        data class Image(val image: FetchedImage) : Hop

        data class Redirect(val nextUrl: String) : Hop
    }

    /** 与加载器同款：读满上限就停，防止把几 MB 的大图整个读进内存。 */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "ImageFetcher"
        const val MAX_REDIRECTS = 5

        /** og:image 常见几十到几百 KB；1MB 与页面快照上限对齐。 */
        const val MAX_BYTES = 1024 * 1024
        const val USER_AGENT = "ZhiCun/1.0 (Android)"
    }
}
