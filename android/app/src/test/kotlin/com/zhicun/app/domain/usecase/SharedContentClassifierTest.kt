package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.RecordInput
import com.zhicun.app.domain.model.InputUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 记录类型推断与 URL 提取的纯函数测试。 */
class SharedContentClassifierTest {

    private fun payload(
        text: String? = null,
        uris: List<InputUri> = emptyList(),
    ) = RecordInput(text = text, supportedUris = uris)

    private fun uri(mime: String) = InputUri(uri = "content://x/1", mimeType = mime)

    @Test
    fun Classify_plainText_isSharedText() {
        assertEquals(RecordType.SHARED_TEXT, SharedContentClassifier.classify(payload("随手记一句")))
    }

    @Test
    fun Classify_textWithUrl_isWebLink() {
        val type = SharedContentClassifier.classify(payload("看看这个 https://example.com/a?b=1"))

        assertEquals(RecordType.WEB_LINK, type)
    }

    @Test
    fun Classify_imageWithText_isImage() {
        val type = SharedContentClassifier.classify(
            payload(text = "这张图有用", uris = listOf(uri("image/png"))),
        )

        assertEquals(RecordType.IMAGE, type)
    }

    @Test
    fun Classify_pdf_isFile() {
        val type = SharedContentClassifier.classify(payload(uris = listOf(uri("application/pdf"))))

        assertEquals(RecordType.FILE, type)
    }

    @Test
    fun Classify_multiImages_isImage() {
        val type = SharedContentClassifier.classify(
            payload(uris = listOf(uri("image/jpeg"), uri("image/webp"))),
        )

        assertEquals(RecordType.IMAGE, type)
    }

    @Test
    fun FirstUrl_extractsFirstHttpLink() {
        val url = SharedContentClassifier.firstUrl("见 https://a.com/x 与 https://b.com/y")

        assertEquals("https://a.com/x", url)
    }

    @Test
    fun FirstUrl_stripsTrailingChinesePunctuation() {
        val url = SharedContentClassifier.firstUrl("原文在这里：https://example.com/post。")

        assertEquals("https://example.com/post", url)
    }

    @Test
    fun FirstUrl_ignoresNonHttpSchemes() {
        assertNull(SharedContentClassifier.firstUrl("content://media/external/images/1"))
        assertNull(SharedContentClassifier.firstUrl("没有链接的一段话"))
    }
}
