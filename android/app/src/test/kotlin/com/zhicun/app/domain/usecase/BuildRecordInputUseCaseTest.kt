package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.file.MimeTypeResolver
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.InputUri
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.FakeMimeTypeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手动新建的输入组装：文本归一 + 按 URI 查真实类型（M5）。 */
class BuildRecordInputUseCaseTest {

    private val imageUri = "content://media/external/images/1"
    private val pdfUri = "content://docs/document/9"

    @Test
    fun Invoke_trimsTextAndKeepsAttachments() = runTest {
        val useCase = BuildRecordInputUseCase(
            FakeMimeTypeResolver(mapOf(imageUri to "image/png")),
        )

        val input = useCase("  一张截图  ", listOf(InputUri(imageUri, null)))

        assertEquals("一张截图", input.text)
        assertEquals(1, input.supportedUris.size)
        assertEquals("image/png", input.supportedUris.single().mimeType)
        assertTrue(input.hasContent)
    }

    @Test
    fun Invoke_blankText_becomesNull() = runTest {
        val useCase = BuildRecordInputUseCase(FakeMimeTypeResolver())

        assertNull(useCase("   \n ", emptyList()).text)
    }

    @Test
    fun Invoke_resolvesEachUriIndependently() = runTest {
        val useCase = BuildRecordInputUseCase(
            FakeMimeTypeResolver(mapOf(imageUri to "image/jpeg", pdfUri to "application/pdf")),
        )

        val input = useCase("", listOf(InputUri(imageUri, null), InputUri(pdfUri, null)))

        assertEquals(
            listOf("image/jpeg", "application/pdf"),
            input.supportedUris.map { it.mimeType },
        )
    }

    @Test
    fun Invoke_resolverReturnsNull_keepsUriWithUnknownType() = runTest {
        val useCase = BuildRecordInputUseCase(FakeMimeTypeResolver())

        val input = useCase("", listOf(InputUri(imageUri, null)))

        assertEquals(1, input.supportedUris.size)
        assertNull(input.supportedUris.single().mimeType)
    }

    @Test
    fun Invoke_resolverThrows_stillReturnsInputInsteadOfFailingSave() = runTest {
        // 查类型失败不能连累用户写的字：附件降级为"类型未知"，正文照常保存
        val resolver = FakeMimeTypeResolver(mapOf(imageUri to "image/png")).apply {
            throwOnResolve = true
        }
        val useCase = BuildRecordInputUseCase(resolver)

        val input = useCase("正文不能丢", listOf(InputUri(imageUri, null)))

        assertEquals("正文不能丢", input.text)
        assertNull(input.supportedUris.single().mimeType)
    }

    @Test
    fun Invoke_cancellationDuringMimeResolve_isPropagated() = runTest {
        // 取消不是"查不到类型"：不能让已取消的保存继续往下走
        val resolver = object : MimeTypeResolver {
            override suspend fun resolve(uri: String): String? =
                throw CancellationException("cancelled")
        }
        val useCase = BuildRecordInputUseCase(resolver)

        var caught: CancellationException? = null
        try {
            useCase("正文", listOf(InputUri(imageUri, null)))
        } catch (e: CancellationException) {
            caught = e
        }

        assertNotNull("取消必须原样抛出，不能被降级成类型未知", caught)
    }

    @Test
    fun Invoke_resolvedImageMime_classifiesAsImageRecord() = runTest {
        val useCase = BuildRecordInputUseCase(
            FakeMimeTypeResolver(mapOf(imageUri to "image/png")),
        )

        val input = useCase("", listOf(InputUri(imageUri, null)))

        assertEquals(
            RecordType.IMAGE,
            SharedContentClassifier.classify(input, captureSource = CaptureSource.MANUAL),
        )
    }

    @Test
    fun Invoke_keepsDisplayNameFromPicker() = runTest {
        // 新建页已经在选择时查到了名字，组装输入时必须原样带走，否则保存后只剩清洗过的磁盘名
        val useCase = BuildRecordInputUseCase(FakeMimeTypeResolver(mapOf(imageUri to "image/png")))

        val input = useCase("", listOf(InputUri(imageUri, null, displayName = "IMG_0001.png")))

        assertEquals("IMG_0001.png", input.supportedUris.single().displayName)
    }

    @Test
    fun Invoke_resolverReturnsNull_keepsMimeKnownByCaller() = runTest {
        // 调用方已经知道类型时，查不到不能把已知类型抹成 null
        val useCase = BuildRecordInputUseCase(FakeMimeTypeResolver())

        val input = useCase("", listOf(InputUri(pdfUri, mimeType = "application/pdf")))

        assertEquals("application/pdf", input.supportedUris.single().mimeType)
    }
}
