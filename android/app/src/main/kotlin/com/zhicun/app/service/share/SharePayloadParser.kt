package com.zhicun.app.service.share

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.model.InputUri

/**
 * 把系统分享的 `Intent` 解析成 domain 层认识的 [RecordInput]。
 *
 * 放在 `service` 层的原因：`Intent` / `PackageManager` 都是 Android framework 类型，
 * 解析完就丢掉它们，后面的 UseCase 只看到纯数据（可在纯 JVM 下测试）。
 *
 * 支持范围（M4）：`text/plain`、`text/html`、image 前缀（任意图片）、`application/pdf`。
 * 注意：注释里不能出现斜杠星号连写 —— Kotlin 块注释是**可嵌套**的，
 * 在 KDoc 里写 "image 斜杠 星号" 会开一层新注释，把后面的代码整段吞掉。
 * 其余类型不静默丢弃，而是记进 [RecordInput.unsupportedMimeTypes]，由接收页如实提示。
 *
 * ## 通配声明类型必须回查真实类型
 * 相册、截图预览、文件管理器常常把类型声明成 `image` 前缀这种通配形式。通配值**不能**直接当
 * 附件的 MIME 存库，也不能用来判断"这是不是图片"，否则分类器会把它判成普通文件、
 * Asset 表里会落成 `application/octet-stream`。因此传入 [MimeTypeLookup] 时会按 URI
 * 回查真实类型并写进 [InputUri.mimeType]（详见 [judge]）。
 */
object SharePayloadParser {

    private const val TAG = "SharePayloadParser"

    private const val MIME_TEXT_PLAIN = "text/plain"
    private const val MIME_TEXT_HTML = "text/html"
    private const val MIME_PDF = "application/pdf"
    private const val MIME_IMAGE_PREFIX = "image/"
    private const val MIME_UNKNOWN = "unknown"

    /** `EXTRA_TEXT` 里可能带 HTML（部分浏览器只给 HTML），超长 HTML 转文本前先截断，避免卡 UI。 */
    private const val MAX_HTML_CHARS = 200_000

    /**
     * 纯文本入库上限（2026-10-24 全项目审查 L-4）。
     *
     * 之前 `EXTRA_TEXT` 没有任何上限：发送方（含任意第三方应用，`ShareReceiverActivity`
     * 是 exported 的）可以塞一段几十万字的文本进来，直接变成一条超大的记录 —— 它会被
     * 写进 `raw_text`、被 FTS 分词、被摘要任务整体上传。这里给一条与真实分享完全不可能
     * 撞上的护栏（Binder 对 Intent extra 本身也有 ~1MB 量级限制）。
     *
     * 取 50 万而不是 20 万：HTML 那条限制是为了正则处理成本，这条是体积护栏，
     * 两者目的不同，所以不共用一个常量。
     */
    private const val MAX_TEXT_CHARS = 500_000

    /**
     * 单次分享最多收多少个附件（2026-11-01 审核 S-5，自海外轨移植）。
     *
     * 发送方完全可以是任意应用（接收页是 exported 的），一次塞几千个 URI 进来会让
     * 保存阶段长时间占着生命周期、写爆私有目录。取 20：真实"多选图片分享"常见上限，
     * 超出部分不静默丢，记进 [RecordInput.excessUriCount] 由提示如实报出。
     */
    private const val MAX_ATTACHMENTS = 20

    /**
     * @param referrerPackage 发起分享的包名（来自 `Activity.getReferrer()`，`Intent` 本身拿不到）
     * @param mimeTypeLookup 传入后会在声明类型是通配符（例如 `image` 前缀）时，
     *   按 URI 查询**真实类型**回填 [InputUri.mimeType]。不传则退化为"类型未知"，
     *   行为与 M4 首版一致（纯 JVM 测试可不传）。
     */
    fun parse(
        intent: Intent?,
        packageManager: PackageManager?,
        referrerPackage: String? = null,
        mimeTypeLookup: MimeTypeLookup? = null,
    ): RecordInput {
        if (intent == null) return RecordInput()

        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            Log.w(TAG, "非分享 Intent，action=$action")
            return RecordInput()
        }

        val declaredMime = intent.type?.lowercase()?.takeIf { it.isNotBlank() }
        val text = resolveText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }

        val allUris = if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.multipleUris()
        } else {
            listOfNotNull(intent.singleUri())
        }
        // 数量上限必须在 judge 之前截断：否则超量 URI 仍然会逐个查 MIME / 解析
        val uris = allUris.take(MAX_ATTACHMENTS)
        val excessUriCount = allUris.size - uris.size

        val supported = mutableListOf<InputUri>()
        val unsupported = mutableListOf<String>()
        uris.forEach { uri ->
            val verdict = judge(uri, declaredMime, mimeTypeLookup)
            val accepted = verdict.accepted
            if (accepted != null) {
                supported += accepted
            } else {
                unsupported += verdict.rejectedMime ?: MIME_UNKNOWN
            }
        }

        return RecordInput(
            text = text,
            subject = subject,
            sourceApp = resolveSourceApp(intent, packageManager, referrerPackage),
            supportedUris = supported,
            unsupportedMimeTypes = unsupported,
            excessUriCount = excessUriCount,
        )
    }

    /**
     * 对单个 URI 做「收不收」的判定，并给出该附件**可存库的真实类型**。
     *
     * 判定顺序（不能换）：
     * 0. 只收 `content://`。`file://` 指向的是发送方自己的私有路径，我们没有读权限；
     *    `http(s)://` 更不是可复制的附件。收下来只会在复制阶段失败，还会让用户
     *    以为"存住了"（2026-11-01 审核 S-5，自海外轨移植）。
     * 1. 发送方声明的是**具体类型**（无通配符）→ 直接采信。它是发送方对"这是什么"的明确承诺，
     *    而且 Manifest 的 intent-filter 也是按它匹配的。
     * 2. 声明是通配符（`image` 前缀、星号斜杠星号）或没声明 → 向 [MimeTypeLookup] 问真实类型。
     *
     * 第 2 步是 M4 审核发现的关键缺口：`image` 前缀是相册/截图最常发的声明类型，
     * 首版把通配符一律置为 `null`，导致 ①分类器判不出 `IMAGE`（退化成 `FILE`）、
     * ②Asset 表里 MIME 落成 `application/octet-stream`。现在用真实类型回填，
     * 两条链路同时修好；查询不到时才退回 `null`，由 data 层再试一次。
     */
    private fun judge(
        uri: Uri,
        declaredMime: String?,
        lookup: MimeTypeLookup?,
    ): UriVerdict {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return UriVerdict(rejectedMime = declaredMime ?: MIME_UNKNOWN)
        }

        val concreteDeclared = declaredMime?.takeIf { !it.contains('*') }
        if (concreteDeclared != null) {
            return if (isSupportedMime(concreteDeclared)) {
                UriVerdict(accepted = InputUri(uri.toString(), concreteDeclared))
            } else {
                UriVerdict(rejectedMime = concreteDeclared)
            }
        }

        val resolved = queryMimeType(lookup, uri)
        return when {
            // 问不到类型：无法据此拒收，先收下（复制阶段还能再问一次）
            resolved == null -> UriVerdict(accepted = InputUri(uri.toString(), null))
            isSupportedMime(resolved) -> UriVerdict(accepted = InputUri(uri.toString(), resolved))
            else -> UriVerdict(rejectedMime = resolved)
        }
    }

    private fun queryMimeType(lookup: MimeTypeLookup?, uri: Uri): String? {
        if (lookup == null) return null
        return try {
            lookup.typeOf(uri)?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 个别 provider 在无授权时会抛 SecurityException；问不到类型不算致命错误，
            // 只是这一条会以"类型未知"入库，复制阶段仍会再查一次
            Log.d(TAG, "查询 URI 类型失败 uri=$uri", e)
            null
        }
    }

    /** 文本优先取 `EXTRA_TEXT`；只有 HTML 时，降级把 HTML 转成纯文本。 */
    private fun resolveText(intent: Intent): String? {
        val plain = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!plain.isNullOrBlank()) return plain.trim().take(MAX_TEXT_CHARS)

        val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        if (!html.isNullOrBlank()) {
            return htmlToPlainText(html).trim().take(MAX_TEXT_CHARS).takeIf { it.isNotEmpty() }
        }

        return null
    }

    /**
     * 判断某 MIME 是否受支持。
     *
     * 注意 `text/plain` 与 `text/html` 是"文本载体"而非附件：它们不会生成 Asset，
     * 但 URI 列表里可能出现（少数 App 把 txt 当附件发），此时按文本类附件接受。
     */
    internal fun isSupportedMime(mime: String?): Boolean {
        val normalized = mime?.lowercase() ?: return false
        return normalized == MIME_TEXT_PLAIN ||
            normalized == MIME_TEXT_HTML ||
            normalized == MIME_PDF ||
            normalized.startsWith(MIME_IMAGE_PREFIX)
    }

    /**
     * 来源应用：优先 `referrer`（系统会带上发起分享的包名），其次 `intent.package`。
     *
     * 解析成可读名称而不是包名：卡片上显示 `com.android.chrome` 对用户毫无意义。
     * 解析失败就退回包名，再失败就留空（`sourceApp` 允许为空）。
     */
    private fun resolveSourceApp(
        intent: Intent,
        packageManager: PackageManager?,
        referrerPackage: String?,
    ): String? {
        val packageName = referrerPackage?.takeIf { it.isNotBlank() }
            ?: intent.`package`
            ?: return null
        if (packageManager == null) return packageName
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // 包已被卸载/隐藏很常见（例如分享源是临时安装的 App），不算异常情况
            Log.d(TAG, "无法解析来源应用名，回退包名：$packageName", e)
            packageName
        }
    }

    // --- API 33 前后 Parcelable 读取方式不同：必须显式分支（AGENTS.md §2.4） ---

    @Suppress("DEPRECATION")
    private fun Intent.singleUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.multipleUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    // --- HTML → 纯文本（不引 jsoup：分享正文只需要"能读"，不需要保真渲染） ---

    private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val BLOCK_BOUNDARY = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>|</h[1-6]\\s*>")
    private val ANY_TAG = Regex("<[^>]+>")
    private val INLINE_SPACES = Regex("[\\t\\x0B\\f\\r]+")
    private val MULTI_BLANK_LINES = Regex("\n{3,}")

    internal fun htmlToPlainText(html: String): String {
        val source = if (html.length > MAX_HTML_CHARS) html.take(MAX_HTML_CHARS) else html
        val withoutScripts = SCRIPT_OR_STYLE.replace(source, " ")
        val withBreaks = BLOCK_BOUNDARY.replace(withoutScripts, "\n")
        val withoutTags = ANY_TAG.replace(withBreaks, " ")
        val decoded = decodeCommonEntities(withoutTags)
        return decoded
            .replace(INLINE_SPACES, " ")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(MULTI_BLANK_LINES, "\n\n")
            .trim()
    }

    private fun decodeCommonEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // &amp; 必须最后替换，否则 "&amp;lt;" 会被二次解码成 "<"
        .replace("&amp;", "&")

    /**
     * 单个 URI 的判定结果。
     *
     * 用一个只有两个字段的小类型而不是 `Pair`：`Pair<InputUri?, String?>` 在调用处
     * `first` / `second` 完全读不出语义，而这里的两个分支（收 / 拒）本来就是互斥的。
     */
    private data class UriVerdict(
        val accepted: InputUri? = null,
        val rejectedMime: String? = null,
    )
}

/**
 * 从 `Activity.getReferrer()` 的 URI 里取来源包名。
 *
 * 只有 `android-app://<pkg>` 才是"发起分享的 App"。浏览器/Web 中转会给
 * `https://weibo.com` 这类 URI，`authority` 是域名而不是包名 —— 直接拿去
 * `getApplicationInfo` 必抛 `NameNotFoundException`，界面上就会出现
 * "来源应用：weibo.com" 的裸域名。非 `android-app` 一律返回 null，
 * 由调用方回退到 `intent.package`。
 */
internal fun referrerPackageOf(referrer: Uri?): String? =
    referrer
        ?.takeIf { it.scheme == "android-app" }
        ?.authority
        ?.takeIf { it.isNotBlank() }
