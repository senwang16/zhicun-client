package com.zhicun.app.domain.web

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 抽取测试（M8）。
 *
 * 用固定 HTML 片段钉住四件事：标题的降级链、正文容器的选择、噪声标签的剔除、
 * 以及"正文偏短 = 未完整提取"的判定。这些都是纯函数行为，不需要网络与 Android 运行时。
 */
class HtmlContentExtractorTest {

    private fun page(html: String, charset: String? = "utf-8"): WebPage = WebPage(
        finalUrl = "https://example.com/post/1",
        content = html.toByteArray(Charsets.UTF_8),
        declaredCharset = charset,
    )

    private fun longText(times: Int = 40): String = (1..times).joinToString(" ") { "这是第${it}段正文内容，用于凑够长度。" }

    @Test
    fun Extract_normalArticle_takesTitleAndArticleText() {
        val html = """
            <html><head><title>智存 · 设计说明</title></head>
            <body>
              <nav>首页 | 关于我们 | 登录</nav>
              <article>
                <h1>智存 · 设计说明</h1>
                <p>第一段：本地优先，数据不出设备。</p>
                <p>第二段：派生内容可重建，原文不可覆盖。</p>
              </article>
              <footer>版权所有 2026</footer>
              <script>var tracking = 1;</script>
            </body></html>
        """.trimIndent()

        val result = HtmlContentExtractor.extract(page(html))

        assertEquals("智存 · 设计说明", result.title)
        assertTrue("应包含第一段，实际 ${result.text}", result.text.contains("第一段"))
        assertTrue("应包含第二段", result.text.contains("第二段"))
        assertFalse("导航栏不该进正文", result.text.contains("关于我们"))
        assertFalse("页脚不该进正文", result.text.contains("版权所有"))
        assertFalse("脚本不该进正文", result.text.contains("tracking"))
    }

    @Test
    fun Extract_withoutTitle_fallsBackToOgTitle() {
        val html = """
            <html><head>
              <meta property="og:title" content="来自 og 的标题" />
            </head><body><main><p>${longText()}</p></main></body></html>
        """.trimIndent()

        assertEquals("来自 og 的标题", HtmlContentExtractor.extract(page(html)).title)
    }

    @Test
    fun Extract_withoutTitleOrOg_fallsBackToH1() {
        val html = "<html><body><h1>唯一的标题</h1><p>${longText()}</p></body></html>"

        assertEquals("唯一的标题", HtmlContentExtractor.extract(page(html)).title)
    }

    @Test
    fun Extract_withoutAnyTitle_returnsNull() {
        val html = "<html><body><p>${longText()}</p></body></html>"

        assertNull("抽不到标题时应为 null，而不是空串", HtmlContentExtractor.extract(page(html)).title)
    }

    @Test
    fun Extract_shortText_marksPartial() {
        // 登录墙 / 纯前端渲染的典型样子：只有一句话
        val html = "<html><head><title>请登录</title></head><body><p>请登录后查看全文</p></body></html>"

        val result = HtmlContentExtractor.extract(page(html))

        assertTrue("正文过短应标记为未完整提取", result.partial)
        assertEquals("请登录后查看全文", result.text)
    }

    @Test
    fun Extract_longText_isNotPartial() {
        val html = "<html><body><article><p>${longText()}</p></article></body></html>"

        assertFalse(HtmlContentExtractor.extract(page(html)).partial)
    }

    @Test
    fun Extract_hugeText_isCappedWithEllipsis() {
        val repeated = "很长的正文。".repeat(6_000)
        val html = "<html><body><article><p>$repeated</p></article></body></html>"

        val text = HtmlContentExtractor.extract(page(html)).text

        assertEquals(HtmlContentExtractor.MAX_TEXT_CHARS + 1, text.length)
        assertTrue("截断处应有省略号", text.endsWith("…"))
    }

    @Test
    fun Extract_gbkPage_decodesByDeclaredCharset() {
        val html = "<html><head><title>中文标题</title></head><body><p>${longText()}</p></body></html>"
        val gbk = html.toByteArray(Charset.forName("GBK"))
        val result = HtmlContentExtractor.extract(
            WebPage("https://example.com/gbk", gbk, declaredCharset = "gbk"),
        )

        assertEquals("按响应头编码解码，标题不该出现乱码", "中文标题", result.title)
        assertFalse("正文里不该有替换字符", result.text.contains('\uFFFD'))
    }

    @Test
    fun Extract_duplicateNestedBlocks_areCollapsed() {
        // li 里套 p：同一句话会被两层选择器各取一次
        val html = "<html><body><article><ul><li><p>重复的一句话</p></li></ul></article></body></html>"

        val text = HtmlContentExtractor.extract(page(html)).text

        assertEquals("重复的一句话", text)
    }

    @Test
    fun Extract_listItems_areJoinedByNewline() {
        val html = "<html><body><article><li>甲</li><li>乙</li></article></body></html>"

        assertEquals("甲\n乙", HtmlContentExtractor.extract(page(html)).text)
    }

    @Test
    fun Extract_emptyBody_returnsEmptyText() {
        val html = "<html><head><title>空页面</title></head><body></body></html>"

        val result = HtmlContentExtractor.extract(page(html))

        assertEquals("", result.text)
        assertTrue("没有正文应算未完整提取", result.partial)
    }

    // ------------------------------------------------ og:image（C）

    @Test
    fun Extract_ogImageAbsoluteUrl_isReturned() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/cover.jpg" />
              <title>带配图</title>
            </head><body><p>${longText()}</p></body></html>
        """.trimIndent()

        assertEquals(
            "https://cdn.example.com/cover.jpg",
            HtmlContentExtractor.extract(page(html)).imageUrl,
        )
    }

    @Test
    fun Extract_ogImageRelativeUrl_resolvesAgainstFinalUrl() {
        // 页面在 https://example.com/post/1，相对路径 /img/x.png 应 resolve 成绝对地址
        val html = """
            <html><head>
              <meta property="og:image" content="/img/x.png" />
              <title>相对路径配图</title>
            </head><body><p>${longText()}</p></body></html>
        """.trimIndent()

        assertEquals(
            "https://example.com/img/x.png",
            HtmlContentExtractor.extract(page(html)).imageUrl,
        )
    }

    @Test
    fun Extract_withoutOgImage_imageUrlIsNull() {
        val html = "<html><head><title>无配图</title></head><body><p>${longText()}</p></body></html>"

        assertNull(HtmlContentExtractor.extract(page(html)).imageUrl)
    }

    @Test
    fun Extract_ogImageEmptyContent_isNull() {
        val html = """
            <html><head>
              <meta property="og:image" content=" " />
              <title>空配图</title>
            </head><body><p>${longText()}</p></body></html>
        """.trimIndent()

        assertNull("空值/纯空白等于没有配图", HtmlContentExtractor.extract(page(html)).imageUrl)
    }
}
