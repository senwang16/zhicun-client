package com.zhicun.app.domain.web

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 把页面正文图片内嵌进快照 HTML（C 补：图片回显，2026-10-24 用户反馈）。
 *
 * ## 为什么在保存时做，而不是查看时放开网络
 * 查看页按"快照不是浏览器"禁脚本、禁网络、禁 file/content——远程 `<img>` 在那里
 * **必然**裂图。放开网络等于把快照变回浏览器，且图片事后可能下线；
 * 唯一符合"离线快照"定位的做法是保存时把图片抓下来，转成 `data:` URI 写进 HTML。
 *
 * 纯 Kotlin（Jsoup + 域内 [ImageFetcher] 接口），无 Android 依赖，可直接单测。
 * 每张图的下载都走 [ImageFetcher]：SSRF 每跳重判、钉死 DNS、单图 1MB 上限
 * 与 og:image 同一套硬约束，一条不能省——页面里的图和 og:image 是同级风险。
 *
 * 预算（防把快照撑爆）：最多 [MAX_IMAGES] 张、原始字节合计 [MAX_TOTAL_IMAGE_BYTES]；
 * 超出即停止（后面的图保持原样，反正查看页也显示不了）。
 * 单图失败只跳过该图（保留原 src），不影响其余图片与快照本身——与
 * WebExtractWorker"附加能力失败必须降级"同一口径。
 * 注意预算是**下载前预判**的（见循环内说明）：保证任何图片组合都不会把快照
 * 撑过落盘上限——否则"附加能力失败必须降级"会退化成"整份快照丢失"。
 *
 * 顺手做两件净化（输出即"可直接离线渲染"的形态）：
 * 1. 摘掉 `Content-Security-Policy` meta——WebView 会执行它，`img-src` 不含
 *    `data:` 时内嵌图照样被拦；
 * 2. 摘掉原 charset 声明、统一输出 UTF-8 并写入 `<meta charset="utf-8">`——
 *   （原字节可能是 GBK）这样快照文件在 App 外用浏览器打开也不乱码。
 */
object SnapshotImageInliner {

    /** 内嵌结果：字节 + 成功/失败计数（供调用方记日志，域层不碰 android.util.Log）。 */
    class Outcome(val htmlBytes: ByteArray, val inlinedCount: Int, val failedCount: Int)

    /** 最多内嵌的图片张数。正常文章远用不满，防异常页面拖垮任务时长。 */
    const val MAX_IMAGES: Int = 30

    /** 内嵌图片的原始字节总预算。base64 后约 4MB，叠加 ≤1MB 的页面本身，低于快照 6MB 上限。 */
    const val MAX_TOTAL_IMAGE_BYTES: Long = 3L * 1024 * 1024

    /**
     * 单图字节上限，与 `OkHttpImageFetcher.MAX_BYTES` 对齐（超出即抛，这里只用于预算预判；
     * domain 层不引 data 层常量，两处对齐靠测试钉住）。
     */
    const val MAX_IMAGE_BYTES: Long = 1L * 1024 * 1024

    suspend fun inline(page: WebPage, imageFetcher: ImageFetcher): Outcome {
        // charset 为 null 时 Jsoup 读 BOM 与 meta charset（GBK 页面靠这一步），与提取器一致
        val document = Jsoup.parse(
            ByteArrayInputStream(page.content),
            page.declaredCharset,
            page.finalUrl,
        )
        stripPolicyMetas(document)
        normalizeCharset(document)
        // <picture> 的 <source srcset> 全是远程候选：留着就可能被优先选中，
        // 而查看页禁网必然失败；摘掉后浏览器才落到 <img> 本身（可能是内嵌图）
        document.select("source[srcset]").removeAttr("srcset")

        var inlinedCount = 0
        var failedCount = 0
        var totalBytes = 0L
        // 同一 URL 出现在多张 <img>（头像、装饰图）时只下载一次；null 表示"下载过但失败"，
        // 失败结果也缓存，避免同一张坏图被重复请求
        val dataUriCache = HashMap<String, String?>()

        for (img in document.select("img")) {
            if (inlinedCount >= MAX_IMAGES) break
            // fetch 前预判：按单图上限 [MAX_IMAGE_BYTES] 算，这张下完会越出总预算就停。
            // 不能等下载完再看——"累计到 3MB−ε 后又来一张接近 1MB 的图"会把原始字节推到
            // ≈4MB，base64 后 ≈5.33MB，叠加 1MB 的页面本身就越过 6MB 快照落盘上限，
            // saveHtmlSnapshot 抛异常、saveQuietly 降级，结果是**整份快照不落盘**而不是
            // "少内嵌一张"。预判牺牲一点预算利用率（最坏只用到 ~2MB+ε），换
            // "最多丢图、绝不丢快照"在任何图片组合下都成立。
            if (totalBytes + MAX_IMAGE_BYTES > MAX_TOTAL_IMAGE_BYTES) break

            val attr = pickSourceAttr(img) ?: continue
            val absolute = img.absUrl(attr).trim()
            if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) continue

            val dataUri = dataUriCache.getOrPut(absolute) {
                try {
                    val image = imageFetcher.fetch(absolute)
                    totalBytes += image.bytes.size
                    toDataUri(image)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // 单图失败（404/防盗链/SSRF 拒绝）：保留原 src，只计失败
                }
            }
            if (dataUri == null) {
                failedCount++
                continue
            }
            img.attr("src", dataUri)
            img.removeAttr("srcset")
            img.removeAttr("data-src")
            inlinedCount++
        }

        return Outcome(
            htmlBytes = document.html().toByteArray(Charsets.UTF_8),
            inlinedCount = inlinedCount,
            failedCount = failedCount,
        )
    }

    /**
     * 选承载真实地址的属性。懒加载页（微信公众号文章是典型）src 是占位图或空白、
     * 真图藏在 `data-src`：src 为空白或是 data: 占位图时优先取 data-src。
     * 返回 null 表示没有可下载的候选。
     */
    private fun pickSourceAttr(img: Element): String? {
        val src = img.attr("src").trim()
        val lazy = img.attr("data-src").trim()
        return when {
            lazy.isNotEmpty() && (src.isEmpty() || src.startsWith("data:")) -> "data-src"
            src.isNotEmpty() -> "src"
            else -> null
        }
    }

    private fun toDataUri(image: FetchedImage): String {
        val mime = image.mimeType.ifBlank { "image/png" } // fetcher 已保证是 image/ 前缀
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(image.bytes)}"
    }

    /** CSP meta 会让 WebView 拦掉 data: 图片，快照里直接摘掉（HTTP 头不落盘，无需处理）。 */
    private fun stripPolicyMetas(document: Document) {
        for (meta in document.select("meta[http-equiv]").toList()) {
            val equiv = meta.attr("http-equiv")
            if (equiv.equals("Content-Security-Policy", ignoreCase = true) ||
                equiv.equals("X-Content-Security-Policy", ignoreCase = true)
            ) {
                meta.remove()
            }
        }
    }

    /** 摘掉旧 charset 声明（原文可能是 GBK），统一写成 UTF-8，输出字节与声明不再错位。 */
    private fun normalizeCharset(document: Document) {
        for (meta in document.select("meta[charset], meta[http-equiv]").toList()) {
            if (meta.hasAttr("charset") ||
                meta.attr("http-equiv").equals("Content-Type", ignoreCase = true)
            ) {
                meta.remove()
            }
        }
        document.head().prependElement("meta").attr("charset", "utf-8")
    }
}
