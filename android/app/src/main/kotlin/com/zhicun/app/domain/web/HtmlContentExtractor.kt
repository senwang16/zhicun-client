package com.zhicun.app.domain.web

import java.io.ByteArrayInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 从 HTML 里抽标题与正文（M8）。
 *
 * 纯函数、无网络、无 Android 依赖，因此"抽取得好不好"可以拿固定 HTML 片段在单测里钉住。
 *
 * 抽取顺序是**从最像正文的容器往外退**：`article` → `main` → `body`。
 * 一上来就取 `body` 会把导航栏、推荐位、版权声明全算进正文，搜索结果和摘要都会被污染。
 * 这条链是对常见博客/新闻站的归纳，不是通用算法；抽不到也只影响"正文片段"的质量，
 * 不影响 URL 与标题 —— 所以不需要引入可读性算法库（那会为了一个辅助功能加几百 KB）。
 */
object HtmlContentExtractor {

    /**
     * @param title 页面标题；取不到为 null（不是空串，便于上层区分"没有标题"和"标题是空白"）
     * @param text 清理后的正文，最多 [MAX_TEXT_CHARS] 字
     * @param partial 正文偏短，疑似登录墙 / 付费墙 / 纯前端渲染
     * @param imageUrl 页面配图（og:image）的**绝对 URL**；取不到或写的是相对路径且无法
     *   resolve 时为 null。相对路径用页面最终地址 resolve（C 支持离线浏览的一部分）
     */
    data class Extracted(
        val title: String?,
        val text: String,
        val partial: Boolean,
        val imageUrl: String? = null,
    )

    /** 存进数据库的正文上限。够搜索与预览，又不会把一条记录撑成几十 KB。 */
    const val MAX_TEXT_CHARS: Int = 20_000

    /**
     * 正文短于这个长度就标"未完整提取"。
     *
     * 300 字是经验值：正常的文章正文远超它，而登录墙 / 验证码页 / 纯 SPA 骨架页通常只有
     * 几十字（"请登录后查看""正在加载…"）。
     */
    private const val MIN_MEANINGFUL_CHARS = 300

    /** 这些标签的内容一律不是正文，先删掉再取文本。 */
    private const val NOISE_SELECTOR =
        "script, style, noscript, iframe, svg, canvas, form, button, select, textarea, nav, header, footer, aside"

    /** 按段落级元素取文本，比整块 text() 更接近人读到的样子。 */
    private const val BLOCK_SELECTOR = "h1, h2, h3, h4, p, li, blockquote, pre, td"

    fun extract(page: WebPage): Extracted {
        // charset 为 null 时 Jsoup 会读 BOM 与页面里的 meta charset（GBK 页面靠这一步）
        val document = Jsoup.parse(
            ByteArrayInputStream(page.content),
            page.declaredCharset,
            page.finalUrl,
        )
        document.select(NOISE_SELECTOR).remove()

        val title = extractTitle(document)
        val root = document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.body()
        val text = extractText(root)

        return Extracted(
            title = title,
            text = text,
            partial = text.length < MIN_MEANINGFUL_CHARS,
            imageUrl = extractImageUrl(document),
        )
    }

    private fun extractTitle(document: Document): String? {
        val fromTitle = document.title().trim()
        if (fromTitle.isNotEmpty()) return fromTitle

        val ogTitle = document.selectFirst("meta[property=og:title]")
            ?.attr("content")?.trim().orEmpty()
        if (ogTitle.isNotEmpty()) return ogTitle

        return document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * og:image 提取（C）。解析时已把页面最终地址设为 base URI，
     * 相对路径用 `absUrl` resolve 成绝对地址；原始值空白或 resolve 不了时视为"没有配图"。
     * 先判原始值再 resolve：`absUrl` 对空白串可能给出怪结果，顺序不能反。
     */
    private fun extractImageUrl(document: Document): String? {
        val meta = document.selectFirst("meta[property=og:image]") ?: return null
        if (meta.attr("content").isBlank()) return null
        return meta.absUrl("content").trim().takeIf { it.isNotEmpty() }
    }

    private fun extractText(root: Element): String {
        val blocks = root.select(BLOCK_SELECTOR)
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        // 嵌套元素（li 里的 p、td 里的 p）会把同一段文字吐两次，去掉连续重复
        val lines = if (blocks.isEmpty()) {
            listOf(root.text().trim()).filter { it.isNotEmpty() }
        } else {
            blocks.fold(mutableListOf<String>()) { acc, line ->
                if (acc.lastOrNull() != line) acc.add(line)
                acc
            }
        }

        val joined = lines.joinToString("\n")
        return if (joined.length <= MAX_TEXT_CHARS) {
            joined
        } else {
            joined.take(MAX_TEXT_CHARS) + "…"
        }
    }
}
