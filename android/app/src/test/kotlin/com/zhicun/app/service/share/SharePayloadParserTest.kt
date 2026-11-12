package com.zhicun.app.service.share

import android.content.Intent
import android.net.Uri
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.usecase.SharedContentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 分享 Intent 解析测试（Robolectric：需要真实的 Intent / Uri / PackageManager 行为）。
 *
 * 覆盖 M4 的四种入口（文本、HTML、单图、多图、PDF）与不支持类型，
 * 以及 API 33 前后读 Parcelable 的分支（Robolectric 默认跑 sdk=34，走新 API）。
 */
@RunWith(RobolectricTestRunner::class)
class SharePayloadParserTest {

    private val packageManager = RuntimeEnvironment.getApplication().packageManager

    private fun sendIntent(mime: String?, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test
    fun Parse_plainText_returnsTextPayload() {
        val intent = sendIntent("text/plain", "一段被分享的文字").apply {
            putExtra(Intent.EXTRA_SUBJECT, "标题")
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals("一段被分享的文字", payload.text)
        assertEquals("标题", payload.subject)
        assertTrue(payload.supportedUris.isEmpty())
        assertTrue(payload.unsupportedMimeTypes.isEmpty())
        assertTrue(payload.hasContent)
        assertEquals(RecordType.SHARED_TEXT, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_textHtmlWithoutPlainText_stripsTags() {
        val intent = sendIntent("text/html").apply {
            putExtra(Intent.EXTRA_HTML_TEXT, "<p>第一段&amp;内容</p><p>第二段</p>")
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals("第一段&内容\n第二段", payload.text)
    }

    @Test
    fun Parse_urlShare_extractsSourceUrlViaClassifier() {
        val intent = sendIntent("text/plain", "https://example.com/article")

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals("https://example.com/article", SharedContentClassifier.firstUrl(payload.text))
        assertEquals(RecordType.WEB_LINK, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_singleImage_returnsOneSupportedUri() {
        val intent = sendIntent("image/png").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals(1, payload.supportedUris.size)
        assertEquals("content://media/external/images/1", payload.supportedUris.single().uri)
        assertEquals("image/png", payload.supportedUris.single().mimeType)
        assertEquals(RecordType.IMAGE, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_wildcardImageMime_resolvesRealTypeFromResolver() {
        // 相册/截图最常发的声明类型就是 image 前缀 —— 真实类型必须回查出来，
        // 否则分类器判不出 IMAGE、Asset 表里会落成 application/octet-stream（M4 审核发现）
        val intent = sendIntent("image/*").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/2"))
        }

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            mimeTypeLookup = MimeTypeLookup { "image/jpeg" },
        )

        assertEquals(1, payload.supportedUris.size)
        assertEquals("image/jpeg", payload.supportedUris.single().mimeType)
        assertEquals(RecordType.IMAGE, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_wildcardImageMime_withoutLookup_keepsMimeUnknown() {
        // 不传 lookup 时必须保持"类型未知"，不能猜成 image/jpeg：猜错比不知道更糟
        val intent = sendIntent("image/*").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/2"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals(1, payload.supportedUris.size)
        assertNull(payload.supportedUris.single().mimeType)
        assertEquals(RecordType.FILE, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_wildcardImageMime_resolvedToUnsupportedType_isRejected() {
        // 发送方声称是图片，但 URI 实际是压缩包：以真实类型为准拒收，并如实报告真实类型
        val intent = sendIntent("image/*").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/2"))
        }

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            mimeTypeLookup = MimeTypeLookup { "application/zip" },
        )

        assertTrue(payload.supportedUris.isEmpty())
        assertEquals(listOf("application/zip"), payload.unsupportedMimeTypes)
        assertFalse(payload.hasContent)
    }

    @Test
    fun Parse_concreteDeclaredMime_isTrustedOverLookup() {
        // 具体类型是发送方的明确承诺（Manifest 也按它匹配），不应被 provider 的返回值推翻
        val intent = sendIntent("application/pdf").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/1"))
        }

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            mimeTypeLookup = MimeTypeLookup { "application/octet-stream" },
        )

        assertEquals("application/pdf", payload.supportedUris.single().mimeType)
    }

    @Test
    fun Parse_lookupThrows_treatsTypeAsUnknownInsteadOfFailing() {
        // 无授权时部分 provider 会抛 SecurityException：不能因此丢掉整个附件
        val intent = sendIntent("image/*").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/3"))
        }

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            mimeTypeLookup = MimeTypeLookup { throw SecurityException("no permission") },
        )

        assertEquals(1, payload.supportedUris.size)
        assertNull(payload.supportedUris.single().mimeType)
        assertTrue(payload.unsupportedMimeTypes.isEmpty())
    }

    @Test
    fun Parse_multipleImagesWithMixedRealTypes_splitsSupportedAndUnsupported() {
        val imageUri = Uri.parse("content://media/1")
        val zipUri = Uri.parse("content://media/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(imageUri, zipUri))
        }

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            mimeTypeLookup = MimeTypeLookup { uri ->
                if (uri == imageUri) "image/webp" else "application/zip"
            },
        )

        assertEquals(1, payload.supportedUris.size)
        assertEquals("image/webp", payload.supportedUris.single().mimeType)
        assertEquals(listOf("application/zip"), payload.unsupportedMimeTypes)
    }

    @Test
    fun Parse_multipleImages_returnsAllUris() {
        val uris = listOf("content://media/1", "content://media/2", "content://media/3")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map(Uri::parse)))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals(uris, payload.supportedUris.map { it.uri })
    }

    @Test
    fun Parse_pdf_isSupported() {
        val intent = sendIntent("application/pdf").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/1"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals(1, payload.supportedUris.size)
        assertEquals(RecordType.FILE, SharedContentClassifier.classify(payload))
    }

    @Test
    fun Parse_unsupportedZip_reportsMimeAndHasNoContent() {
        val intent = sendIntent("application/zip").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/archive.zip"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertFalse(payload.hasContent)
        assertEquals(listOf("application/zip"), payload.unsupportedMimeTypes)
        assertTrue(payload.supportedUris.isEmpty())
    }

    @Test
    fun Parse_mixedTextAndUnsupported_reportsBoth() {
        val intent = sendIntent("application/zip", "说明文字").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/archive.zip"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertTrue("文字仍应保存", payload.hasContent)
        assertEquals("说明文字", payload.text)
        assertEquals(1, payload.unsupportedMimeTypes.size)
    }

    @Test
    fun Parse_nonShareIntent_returnsEmptyPayload() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertFalse(payload.hasContent)
        assertNull(payload.text)
    }

    @Test
    fun Parse_nullIntent_returnsEmptyPayload() {
        assertFalse(SharePayloadParser.parse(null, packageManager).hasContent)
    }

    @Test
    fun Parse_referrerPackage_resolvesSourceApp() {
        val intent = sendIntent("text/plain", "hi")

        val payload = SharePayloadParser.parse(
            intent = intent,
            packageManager = packageManager,
            referrerPackage = "com.example.reader",
        )

        // 未安装的包解析不到名字，回退包名（而不是丢掉来源信息）
        assertEquals("com.example.reader", payload.sourceApp)
    }

    @Test
    fun IsSupportedMime_matrix() {
        assertTrue(SharePayloadParser.isSupportedMime("text/plain"))
        assertTrue(SharePayloadParser.isSupportedMime("TEXT/PLAIN"))
        assertTrue(SharePayloadParser.isSupportedMime("text/html"))
        assertTrue(SharePayloadParser.isSupportedMime("image/webp"))
        assertTrue(SharePayloadParser.isSupportedMime("application/pdf"))
        assertFalse(SharePayloadParser.isSupportedMime("application/zip"))
        assertFalse(SharePayloadParser.isSupportedMime("video/mp4"))
        assertFalse(SharePayloadParser.isSupportedMime(null))
    }

    @Test
    fun HtmlToPlainText_removesScriptAndStyle() {
        val html = "<style>p{color:red}</style><script>alert(1)</script><p>正文</p>"

        assertEquals("正文", SharePayloadParser.htmlToPlainText(html))
    }

    @Test
    fun HtmlToPlainText_decodesAmpLast() {
        // "&amp;lt;" 应解成 "&lt;" 字面量，而不是被二次解码成 "<"
        assertEquals("&lt;", SharePayloadParser.htmlToPlainText("&amp;lt;"))
    }

    @Test
    fun HtmlToPlainText_collapsesBlankLines() {
        val html = "<div>a</div><div></div><div></div><div>b</div>"

        assertEquals("a\n\nb", SharePayloadParser.htmlToPlainText(html))
    }

    /**
     * 2026-10-24 全项目审查 L-4：`EXTRA_TEXT` 之前没有任何上限，而分享接收页是 exported 的，
     * 任意应用都能塞一段超长文本进来，直接变成一条超大记录（写库 + FTS 分词 + 整体上传）。
     */
    @Test
    fun Parse_oversizedPlainText_isTruncated() {
        val huge = "a".repeat(600_000)

        val payload = SharePayloadParser.parse(sendIntent("text/plain", huge), packageManager)

        assertEquals("超长文本必须被截断", 500_000, payload.text?.length)
    }

    @Test
    fun Parse_normalLengthText_isNotTruncated() {
        val text = "a".repeat(1_000)

        val payload = SharePayloadParser.parse(sendIntent("text/plain", text), packageManager)

        assertEquals(text, payload.text)
    }

    // ---------------------------------------------------------- 附件入口护栏

    /**
     * 只收 `content://`（2026-11-01 审核 S-5，自海外轨移植）。
     *
     * `file://` 指向发送方自己的私有路径，我们没有读权限；收下来只会在复制阶段失败，
     * 却让用户以为"存住了"。
     */
    @Test
    fun Parse_fileSchemeAttachment_isRejected() {
        val intent = sendIntent("image/png").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/secret/photo.png"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertTrue("file:// 不是可复制的附件", payload.supportedUris.isEmpty())
        assertEquals(listOf("image/png"), payload.unsupportedMimeTypes)
        assertFalse(payload.hasContent)
    }

    /** http(s) 更不是本地附件，同样拒收（不进入复制阶段）。 */
    @Test
    fun Parse_httpSchemeAttachment_isRejected() {
        val intent = sendIntent("image/png").apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.com/a.png"))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertTrue(payload.supportedUris.isEmpty())
        assertEquals(listOf("image/png"), payload.unsupportedMimeTypes)
    }

    /**
     * 单次最多 20 个附件：超限部分不处理，但必须计数并如实提示（2026-11-01 审核 S-5）。
     *
     * 分享接收页是 exported 的，任意应用都能塞几千个 URI 进来；不设上限会让保存阶段
     * 长时间占着生命周期、写爆私有目录。
     */
    @Test
    fun Parse_moreThanTwentyAttachments_isCappedAndCounted() {
        val uris = (1..25).map { Uri.parse("content://media/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals("只处理前 20 个", 20, payload.supportedUris.size)
        assertEquals(uris.take(20).map { it.toString() }, payload.supportedUris.map { it.uri })
        assertEquals("超限的 5 个必须计数", 5, payload.excessUriCount)
    }

    @Test
    fun Parse_exactlyTwentyAttachments_hasNoExcess() {
        val uris = (1..20).map { Uri.parse("content://media/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }

        val payload = SharePayloadParser.parse(intent, packageManager)

        assertEquals(20, payload.supportedUris.size)
        assertEquals(0, payload.excessUriCount)
    }

    // ---------------------------------------------------------- 来源包名解析

    /** `android-app://<pkg>` 才是真正的"发起分享的 App"，取它的包名。 */
    @Test
    fun ReferrerPackageOf_androidAppScheme_returnsPackage() {
        val referrer = Uri.parse("android-app://com.tencent.mm")

        assertEquals("com.tencent.mm", referrerPackageOf(referrer))
    }

    /**
     * 浏览器/Web 中转给的是 http(s) 域名：authority 不是包名。
     *
     * 直接把它当包名会 `NameNotFoundException`，界面显示"来源应用：weibo.com"。
     */
    @Test
    fun ReferrerPackageOf_httpScheme_returnsNull() {
        assertNull(referrerPackageOf(Uri.parse("https://weibo.com/foo")))
        assertNull(referrerPackageOf(Uri.parse("http://m.weibo.cn")))
    }

    @Test
    fun ReferrerPackageOf_null_returnsNull() {
        assertNull(referrerPackageOf(null))
    }

    /** 空 authority（形如 `android-app://`）也不能当包名用。 */
    @Test
    fun ReferrerPackageOf_blankAuthority_returnsNull() {
        assertNull(referrerPackageOf(Uri.parse("android-app://")))
    }
}
