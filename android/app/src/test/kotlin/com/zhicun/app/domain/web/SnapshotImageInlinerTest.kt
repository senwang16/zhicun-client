package com.zhicun.app.domain.web

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照图片内嵌（C 补：图片回显）。
 *
 * 钉三件事：图片真的变成 data URI（查看页禁网也能显示）、
 * SSRF 约束不因"图片看起来无害"而放松（下载一律走 ImageFetcher）、
 * 单图失败只降级不传染（快照仍然可用）。
 */
class SnapshotImageInlinerTest {

    private val base = "https://example.com/post/1"

    /** 一段任意字节：内嵌器不解析图片内容，只做 base64 搬运。 */
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private class FakeFetcher : ImageFetcher {
        val responses = mutableMapOf<String, FetchedImage>()
        val failures = mutableMapOf<String, Throwable>()
        val fetched = mutableListOf<String>()

        override suspend fun fetch(url: String): FetchedImage {
            fetched += url
            failures[url]?.let { throw it }
            return responses[url] ?: throw IOException("HTTP 404")
        }
    }

    private fun pageOf(html: String) = WebPage(
        finalUrl = base,
        content = html.toByteArray(Charsets.UTF_8),
        declaredCharset = "utf-8",
    )

    private fun givenImage(fetcher: FakeFetcher, url: String) {
        fetcher.responses[url] = FetchedImage(bytes = pngBytes, mimeType = "image/png")
    }

    @Test
    fun Inline_httpImage_rewritesToDataUriAndDropsSrcset() = runTest {
        val fetcher = FakeFetcher()
        givenImage(fetcher, "https://cdn.example.com/a.png")

        val outcome = SnapshotImageInliner.inline(
            pageOf(
                "<html><head><title>t</title></head><body>" +
                    "<img src=\"https://cdn.example.com/a.png\" " +
                    "srcset=\"https://cdn.example.com/a@2x.png 2x\"></body></html>",
            ),
            fetcher,
        )

        val html = outcome.htmlBytes.toString(Charsets.UTF_8)
        assertEquals(1, outcome.inlinedCount)
        assertEquals(0, outcome.failedCount)
        assertEquals(listOf("https://cdn.example.com/a.png"), fetcher.fetched)
        val expected = "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(pngBytes)
        assertTrue("src 应被替换为 data URI", html.contains("src=\"$expected\""))
        assertFalse("srcset 指向远程必然裂图，必须摘掉", html.contains("srcset"))
    }

    @Test
    fun Inline_relativeSrc_resolvesAgainstFinalUrl() = runTest {
        val fetcher = FakeFetcher()
        givenImage(fetcher, "https://example.com/img/b.jpg")

        val outcome = SnapshotImageInliner.inline(
            pageOf("<html><body><img src=\"../img/b.jpg\"></body></html>"),
            fetcher,
        )

        assertEquals("相对路径要用页面最终地址补全", "https://example.com/img/b.jpg", fetcher.fetched.single())
        assertEquals(1, outcome.inlinedCount)
    }

    @Test
    fun Inline_fetchFailure_keepsOriginalSrcOthersStillInlined() = runTest {
        val fetcher = FakeFetcher()
        fetcher.failures["https://cdn.example.com/broken.png"] = IOException("HTTP 404")
        givenImage(fetcher, "https://cdn.example.com/ok.png")

        val html = "<html><body>" +
            "<img src=\"https://cdn.example.com/broken.png\">" +
            "<img src=\"https://cdn.example.com/ok.png\"></body></html>"
        val outcome = SnapshotImageInliner.inline(pageOf(html), fetcher)

        val out = outcome.htmlBytes.toString(Charsets.UTF_8)
        assertEquals(1, outcome.inlinedCount)
        assertEquals(1, outcome.failedCount)
        assertTrue("失败的图保留原 src（与修复前一致）", out.contains("cdn.example.com/broken.png"))
        assertTrue("成功的图照常内嵌", out.contains("data:image/png;base64,"))
    }

    @Test
    fun Inline_lazyPlaceholderSource_prefersDataSrc() = runTest {
        // 微信公众号文章的典型形态：src 是占位图，真图在 data-src
        val fetcher = FakeFetcher()
        givenImage(fetcher, "https://mmbiz.example.com/c.jpg")

        val outcome = SnapshotImageInliner.inline(
            pageOf(
                "<html><body>" +
                    "<img src=\"data:image/svg+xml,%3Csvg/%3E\" " +
                    "data-src=\"https://mmbiz.example.com/c.jpg\"></body></html>",
            ),
            fetcher,
        )

        val out = outcome.htmlBytes.toString(Charsets.UTF_8)
        assertEquals(1, outcome.inlinedCount)
        assertTrue("真图应被内嵌", out.contains("data:image/png;base64,"))
        assertFalse("data-src 用完要摘掉", out.contains("data-src"))
    }

    @Test
    fun Inline_nonHttpSrc_skippedWithoutFetch() = runTest {
        val fetcher = FakeFetcher()

        val outcome = SnapshotImageInliner.inline(
            pageOf(
                "<html><body>" +
                    "<img src=\"data:image/gif;base64,R0lGODlhAQABAAAAACw=\">" +
                    "<img src=\"blob:https://example.com/x\">" +
                    "<img></body></html>",
            ),
            fetcher,
        )

        assertEquals("data:/blob:/空 src 都不下载", 0, fetcher.fetched.size)
        assertEquals(0, outcome.inlinedCount)
        assertEquals(0, outcome.failedCount)
    }

    @Test
    fun Inline_sameUrlTwice_fetchedOnce() = runTest {
        val fetcher = FakeFetcher()
        givenImage(fetcher, "https://cdn.example.com/same.png")

        val outcome = SnapshotImageInliner.inline(
            pageOf(
                "<html><body>" +
                    "<img src=\"https://cdn.example.com/same.png\">" +
                    "<img src=\"https://cdn.example.com/same.png\"></body></html>",
            ),
            fetcher,
        )

        assertEquals("同一 URL 只下载一次", 1, fetcher.fetched.size)
        assertEquals(2, outcome.inlinedCount)
    }

    @Test
    fun Inline_overImageCount_stopsAtLimit() = runTest {
        val fetcher = FakeFetcher()
        val imgs = (0 until 35).joinToString("") { i ->
            givenImage(fetcher, "https://cdn.example.com/$i.png")
            "<img src=\"https://cdn.example.com/$i.png\">"
        }

        val outcome = SnapshotImageInliner.inline(pageOf("<html><body>$imgs</body></html>"), fetcher)

        assertEquals(SnapshotImageInliner.MAX_IMAGES, fetcher.fetched.size)
        assertEquals(SnapshotImageInliner.MAX_IMAGES, outcome.inlinedCount)
    }

    @Test
    fun Inline_overByteBudget_stopsBeforeExceeding() = runTest {
        val fetcher = FakeFetcher()
        // 每张 800KB：预判按单图上限 1MB 算，累计 2.4MB 后"2.4+1 > 3"，第 4 张不再下载
        val imgs = (0 until 5).joinToString("") { i ->
            fetcher.responses["https://cdn.example.com/$i.png"] =
                FetchedImage(bytes = ByteArray(800_000), mimeType = "image/png")
            "<img src=\"https://cdn.example.com/$i.png\">"
        }

        val outcome = SnapshotImageInliner.inline(pageOf("<html><body>$imgs</body></html>"), fetcher)

        assertEquals(3, fetcher.fetched.size)
        assertEquals(3, outcome.inlinedCount)
    }

    @Test
    fun Inline_budgetNearlyFull_skipsEvenSmallImage() = runTest {
        val fetcher = FakeFetcher()
        // 三张 800KB 累计 2.4MB 后，哪怕下一张只有 100KB 也预判放弃：
        // 这是刻意的保守——保证任何图片组合的 base64 产物加页面本身都不会越过
        // 6MB 快照落盘上限（否则整份快照不落盘，比少内嵌一张严重得多）
        fetcher.responses["https://cdn.example.com/a.png"] =
            FetchedImage(bytes = ByteArray(800_000), mimeType = "image/png")
        fetcher.responses["https://cdn.example.com/b.png"] =
            FetchedImage(bytes = ByteArray(800_000), mimeType = "image/png")
        fetcher.responses["https://cdn.example.com/c.png"] =
            FetchedImage(bytes = ByteArray(800_000), mimeType = "image/png")
        fetcher.responses["https://cdn.example.com/d.png"] =
            FetchedImage(bytes = ByteArray(100_000), mimeType = "image/png")

        val html = "<html><body>" +
            "<img src=\"https://cdn.example.com/a.png\">" +
            "<img src=\"https://cdn.example.com/b.png\">" +
            "<img src=\"https://cdn.example.com/c.png\">" +
            "<img src=\"https://cdn.example.com/d.png\"></body></html>"
        val outcome = SnapshotImageInliner.inline(pageOf(html), fetcher)

        assertEquals(3, fetcher.fetched.size)
        assertEquals(3, outcome.inlinedCount)
        val out = outcome.htmlBytes.toString(Charsets.UTF_8)
        assertTrue("放不下的图保留原 src", out.contains("cdn.example.com/d.png"))
    }

    @Test
    fun Inline_cspAndCharsetMeta_normalized() = runTest {
        val outcome = SnapshotImageInliner.inline(
            pageOf(
                "<html><head>" +
                    "<meta charset=\"utf-8\">" +
                    "<meta http-equiv=\"Content-Security-Policy\" content=\"img-src 'self'\">" +
                    "<title>t</title></head><body><p>正文</p></body></html>",
            ),
            FakeFetcher(),
        )

        val out = outcome.htmlBytes.toString(Charsets.UTF_8)
        assertFalse("CSP meta 会拦掉 data: 图片，必须摘掉", out.contains("Content-Security-Policy"))
        assertTrue("输出必须带 UTF-8 声明，App 外打开也不乱码", out.contains("<meta charset=\"utf-8\""))
    }
}
