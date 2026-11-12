package com.zhicun.app.domain.web

/**
 * 一次网页抓取的原始结果。
 *
 * 保留 `ByteArray` 而不是先转 `String`：编码由响应头或页面里的 `meta charset` 决定，
 * 而"用哪个编码解码"是解析阶段的事（见 [HtmlContentExtractor]）。在这里提前 `String(bytes)`
 * 会用错编码，把 GBK 页面变成一堆问号。
 */
class WebPage(
    /** 重定向之后的最终地址，写进日志与派生内容便于排查。 */
    val finalUrl: String,
    val content: ByteArray,
    /** 响应头里声明的字符集（`Content-Type: text/html; charset=gbk`），可能为 null。 */
    val declaredCharset: String?,
)

/** 链接未通过 SSRF 校验。终态失败：同样的链接再试一次结论不会变。 */
class UnsafeUrlException(message: String) : Exception(message)

/** 返回的内容不是可解析的 HTML（图片、PDF、JSON…）。终态失败。 */
class UnsupportedContentException(message: String) : Exception(message)

/**
 * 请求本身有问题且重试无用（重定向缺少 `Location`、重定向次数超限…）。终态失败。
 *
 * 与 `IOException` 区分开的理由：`IOException` 会被判"可重试"，
 * 而这两种情况重试三次结果一模一样，只会白耗流量。
 */
class WebRequestException(message: String) : Exception(message)

/**
 * 源站返回了非 2xx 状态码。
 *
 * 携带状态码，让调用方能区分两类完全不同的结果：
 * - 401/403/429/451：登录墙或反爬策略。同一地址再试一次仍然被拒，**不应**进入重试；
 * - 5xx：服务端临时故障，仍应交给重试机制。
 *
 * 继承 [java.io.IOException] 是为了保持既有"网络层失败"的语义，
 * 调用方可以只按状态码做更细的分流。
 */
class HttpStatusException(val statusCode: Int) : java.io.IOException("HTTP $statusCode")

/**
 * 抓取网页（M8）。
 *
 * 实现必须保证：每次重定向后的地址都重新过 SSRF 校验、请求总耗时受超时约束、
 * 读取的字节数受上限约束。这些约束在实现里做，而不是指望调用方记得传参。
 */
interface WebPageLoader {

    /**
     * @throws UnsafeUrlException 链接被 SSRF 防护拒绝
     * @throws UnsupportedContentException 内容类型不是 HTML
     * @throws HttpStatusException 源站返回非 2xx；调用方按状态码区分"站点拒绝"与"服务端临时故障"
     * @throws java.io.IOException 连接超时、连接重置等网络层失败 —— 上层会判"可重试"
     */
    suspend fun load(url: String): WebPage
}

/**
 * 一次图片抓取的结果（C：og:image 离线保存）。
 *
 * @param bytes 图片原始字节
 * @param mimeType 响应声明的类型（`image/jpeg` 等），已归一为小写
 */
class FetchedImage(val bytes: ByteArray, val mimeType: String)

/**
 * 抓取图片（C）。
 *
 * 与 [WebPageLoader] 分开而不是共用，是因为两者的内容类型约束相反：
 * 页面加载器**只接受 HTML**（图片响应会被它终态拒绝），图片抓取器**只接受图片类型**。
 * 相同的硬约束（SSRF 每跳重判、超时、大小上限）在各自实现里保证，
 * 与加载器同一套标准，由单测钉住。
 */
interface ImageFetcher {

    /**
     * @throws UnsafeUrlException 链接被 SSRF 防护拒绝
     * @throws UnsupportedContentException 内容类型不是图片（image 加任意子类型）
     * @throws java.io.IOException 网络层失败或图片超过大小上限
     */
    suspend fun fetch(url: String): FetchedImage
}
