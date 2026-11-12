package com.zhicun.app.data.web

import com.zhicun.app.domain.web.UnsafeUrlException
import com.zhicun.app.domain.web.UnsupportedContentException
import com.zhicun.app.domain.web.UrlCheck
import com.zhicun.app.domain.web.UrlChecker
import com.zhicun.app.domain.web.WebRequestException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 抓取层测试（M8）。
 *
 * 用 [MockWebServer] 起真实 HTTP 服务，验证的是**真的发了请求之后**的行为：
 * 重定向是否逐跳校验、内容类型是否被挡住、超大响应是否被截断、4xx/5xx 是否落到可重试。
 *
 * 为什么地址校验用假实现：生产实现会拒绝 `127.0.0.1`，而测试服务恰好只监听本机 ——
 * 用真校验就没法测抓取本身。校验规则由 `SafeUrlCheckerTest` 单独覆盖，
 * 这里只验证"校验被调用了，而且每一跳都调用"。
 */
@RunWith(RobolectricTestRunner::class)
class OkHttpWebPageLoaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 记录每次被校验的地址；可指定某些地址被判拒绝。 */
    private class RecordingUrlChecker(
        private val rejected: List<String> = emptyList(),
    ) : UrlChecker {

        val checked: MutableList<String> = mutableListOf()

        override suspend fun check(rawUrl: String): UrlCheck {
            checked += rawUrl
            return if (rawUrl in rejected) {
                UrlCheck.Rejected("拒绝访问内网地址")
            } else {
                UrlCheck.Allowed(rawUrl)
            }
        }
    }

    private fun loader(checker: UrlChecker = RecordingUrlChecker()): OkHttpWebPageLoader =
        OkHttpWebPageLoader(
            client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
            urlChecker = checker,
        )

    @Test
    fun Load_htmlResponse_returnsBytesAndCharset() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>标题</title></head><body><p>正文</p></body></html>"),
        )

        val page = loader().load(server.url("/post").toString())

        assertEquals("utf-8", page.declaredCharset)
        assertTrue(String(page.content, Charsets.UTF_8).contains("正文"))
        assertEquals("最终地址应与请求地址一致", server.url("/post").toString(), page.finalUrl)
    }

    @Test
    fun Load_redirect_checksEveryHopAndFollowsToFinalUrl() = runTest {
        val checker = RecordingUrlChecker()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body><p>终点</p></body></html>"),
        )

        val page = loader(checker).load(server.url("/start").toString())

        assertEquals("应跟随到最终地址", server.url("/final").toString(), page.finalUrl)
        assertEquals(
            "重定向目标也必须过一遍校验，否则 https://evil → http://127.0.0.1 就能绕过",
            listOf(server.url("/start").toString(), server.url("/final").toString()),
            checker.checked,
        )
    }

    @Test
    fun Load_redirectToRejectedAddress_throwsAndMakesNoSecondRequest() = runTest {
        val target = server.url("/internal").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))

        val error = runCatching { loader(RecordingUrlChecker(listOf(target))).load(server.url("/start").toString()) }
            .exceptionOrNull()

        assertTrue("应抛 UnsafeUrlException，实际 $error", error is UnsafeUrlException)
        assertEquals("被拒绝的重定向目标不该真的被请求", 1, server.requestCount)
    }

    @Test
    fun Load_initialUrlRejected_throwsWithoutAnyRequest() = runTest {
        val url = server.url("/secret").toString()

        val error = runCatching { loader(RecordingUrlChecker(listOf(url))).load(url) }.exceptionOrNull()

        assertTrue("应抛 UnsafeUrlException，实际 $error", error is UnsafeUrlException)
        assertEquals("校验不通过时一个请求都不该发出去", 0, server.requestCount)
    }

    @Test
    fun Load_tooManyRedirects_throwsWebRequestException() = runTest {
        repeat(8) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop"))
        }

        val error = runCatching { loader().load(server.url("/loop").toString()) }.exceptionOrNull()

        assertTrue("重定向次数超限应终态失败，而不是一直跟下去，实际 $error", error is WebRequestException)
    }

    @Test
    fun Load_nonHtmlContentType_isRejected() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(ByteArray(64))),
        )

        val error = runCatching { loader().load(server.url("/pic.png").toString()) }.exceptionOrNull()

        assertTrue("图片不该当网页解析，实际 $error", error is UnsupportedContentException)
    }

    @Test
    fun Load_serverError_isIOExceptionSoItCanBeRetried() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { loader().load(server.url("/boom").toString()) }.exceptionOrNull()

        assertTrue("5xx 应交给重试机制，实际 $error", error is IOException)
    }

    @Test
    fun Load_oversizedBody_isTruncatedToLimit() = runTest {
        val twoMegabytes = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(twoMegabytes)),
        )

        val page = loader().load(server.url("/huge").toString())

        assertEquals("最多读 1MB", 1024 * 1024, page.content.size)
    }

    @Test
    fun Load_withoutContentType_stillTriesHtml() = runTest {
        // 有些静态托管不返回 Content-Type，直接放弃太严格
        server.enqueue(MockResponse().setBody("<html><body><p>无类型</p></body></html>"))

        val page = loader().load(server.url("/plain").toString())

        assertTrue(String(page.content, Charsets.UTF_8).contains("无类型"))
    }
}
