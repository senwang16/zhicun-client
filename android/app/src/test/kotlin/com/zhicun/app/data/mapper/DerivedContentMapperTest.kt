package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.domain.model.DerivedType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedContentMapperTest {

    private fun entity(content: String? = "识别出的文字", errorMessage: String? = null) =
        DerivedContentEntity(
            id = "derived-1",
            recordId = "rec-1",
            contentType = DerivedType.OCR_TEXT,
            content = content,
            processorVersion = "ocr-v1",
            processedAt = 5_000L,
            errorMessage = errorMessage,
        )

    @Test
    fun DerivedContentEntity_successResult_mapsContent() {
        val domain = entity().toDomain()

        assertEquals("derived-1", domain.id)
        assertEquals("rec-1", domain.recordId)
        assertEquals(DerivedType.OCR_TEXT, domain.contentType)
        assertEquals("识别出的文字", domain.content)
        assertEquals("ocr-v1", domain.processorVersion)
        assertEquals(5_000L, domain.processedAt)
        assertNull(domain.errorMessage)
    }

    @Test
    fun DerivedContentEntity_failureResult_keepsErrorAndNullContent() {
        val domain = entity(content = null, errorMessage = "OCR 无识别结果").toDomain()

        assertNull(domain.content)
        assertEquals("OCR 无识别结果", domain.errorMessage)
    }

    @Test
    fun DerivedContent_roundTripThroughEntity_preservesAllFields() {
        val domain = entity().toDomain()

        assertEquals(domain, domain.toEntity().toDomain())
    }
}
