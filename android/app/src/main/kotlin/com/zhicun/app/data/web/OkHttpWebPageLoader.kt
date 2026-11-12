package com.zhicun.app.data.web

import android.util.Log
import com.zhicun.app.domain.web.HttpStatusException
import com.zhicun.app.domain.web.UnsafeUrlException
import com.zhicun.app.domain.web.UnsupportedContentException
import com.zhicun.app.domain.web.UrlCheck
import com.zhicun.app.domain.web.UrlChecker
import com.zhicun.app.domain.web.WebPage
import com.zhicun.app.domain.web.WebPageLoader
import com.zhicun.app.domain.web.WebRequestException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp 实现（M8）。
 *
 * ## 为什么不用 `followRedirects(true)`
 * OkHttp 自己跟随重定向时，我们看不到中间的跳转地址，也就没机会对每一跳做 SSRF 校验 ——
 * `https://example.com → http://127.0.0.1/` 会直接打到内网。所以这里关掉自动跟随，
 * 手动循环：**每一跳先过 [UrlChecker]，再发请求**。
 *
 * ## 三条硬约束（M8 验收要求）
 * - 超时 10 秒：连接、读、整体调用各 10 秒（`callTimeout` 是最后一道闸，
 *   防止服务端一点一点挤数据把总时长拖到几十秒）；
 * - 单个页面最多读 [MAX_BYTES]，超出部分丢掉（HTML 被截断仍能解析，比整个失败有用）；
 * - 只接受 HTML：图片、PDF、JSON 直接终态失败，不浪费时间解析。
 */
@Singleton
class OkHttpWebPageLoader @Inject constructor(
    private val client: OkHttpClient,
    private val urlChecker: UrlChecker,
) : WebPageLoader {

    override suspend fun load(url: String): WebPage = withContext(Dispatchers.IO) {
        var current = url

        // 最多跟 MAX_REDIRECTS 跳；每一跳都重新校验，这是防 SSRF 绕过的关键
        repeat(MAX_REDIRECTS + 1) {
            val allowed = when (val check = urlChecker.check(current)) {
                is UrlCheck.Rejected -> throw UnsafeUrlException(check.reason)
                is UrlCheck.Allowed -> check
            }

            when (val hop = requestOnce(allowed)) {
                is Hop.Page -> return@withContext hop.page
                is Hop.Redirect -> {
                    Log.i(TAG, "跟随重定向 ${hop.nextUrl}")
                    current = hop.nextUrl
                }
            }
        }

        throw WebRequestException("重定向次数超过 $MAX_REDIRECTS 次")
    }

    private fun requestOnce(allowed: UrlCheck.Allowed): Hop {
        val request = Request.Builder()
            .url(allowed.url)
            // 用本应用的真实标识，不伪装浏览器：目标站点有权知道是谁在抓
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        // 把连接目标钉在校验过的 IP 上（M8 审核整改）：不然 OkHttp 会自行再解析一次，
        // 两次解析之间的 DNS 变化就能把"放行的公网地址"换成内网地址。
        // 复用 base client 的 newBuilder()：连接池、线程池、超时配置全部继承，只是换了 Dns。
        val callClient = if (allowed.pinnedAddresses.isEmpty()) {
            // 空列表只可能来自测试里的假 UrlChecker；生产校验器必经"字面量"或"解析"两条路之一，
            // 都会带出地址。此时退回原 client，避免把测试桩变成"永远解析失败"。
            client
        } else {
            client.newBuilder()
                .dns(PinnedDns(request.url.host, allowed.pinnedAddresses))
                .build()
        }

        callClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: throw WebRequestException("重定向缺少 Location")
                val next = response.request.url.resolve(location)?.toString()
                    ?: throw WebRequestException("无法解析重定向地址")
                return Hop.Redirect(next)
            }

            if (!response.isSuccessful) {
                // 带上状态码：401/403/429/451 是登录墙或反爬，重试结论不会变；
                // 5xx 才是服务端临时故障。分流由调用方（WebExtractWorker）决定，
                // 这里不再把所有非 2xx 都压成同一条可重试的 IOException。
                throw HttpStatusException(response.code)
            }

            val contentType = response.header("Content-Type").orEmpty()
            if (!isHtml(contentType)) {
                throw UnsupportedContentException(
                    "不是网页内容：${contentType.ifBlank { "未知类型" }}",
                )
            }

            val body = response.body ?: throw IOException("响应没有正文")
            val bytes = body.byteStream().use { readAtMost(it, MAX_BYTES) }
            return Hop.Page(
                WebPage(
                    finalUrl = allowed.url,
                    content = bytes,
                    declaredCharset = charsetOf(contentType),
                ),
            )
        }
    }

    private sealed interface Hop {

        data class Redirect(val nextUrl: String) : Hop

        data class Page(val page: WebPage) : Hop
    }

    /** 读满上限就停：剩下的字节继续读没有意义，而且可能是几百 MB 的视频流。 */
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

    private fun isHtml(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        // 有些站点把 text/html 写成大写或带空格；没有 Content-Type 的按 HTML 试一次
        return type.isEmpty() || type == "text/html" || type == "application/xhtml+xml"
    }

    private fun charsetOf(contentType: String): String? =
        CHARSET_REGEX.find(contentType)?.groupValues?.get(1)
            ?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "WebLoader"
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 1024 * 1024
        const val USER_AGENT = "ZhiCun/1.0 (Android)"
        val CHARSET_REGEX = Regex("""charset\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
    }
}
