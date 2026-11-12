package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.TagEntity
import com.zhicun.app.domain.model.TagSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagMapperTest {

    @Test
    fun TagEntity_aiTagWithConfidence_mapsAllFields() {
        val entity = TagEntity(
            id = "tag-1",
            name = "Kotlin",
            source = TagSource.AI,
            confidence = 0.87,
            createdAt = 100L,
        )

        val domain = entity.toDomain()

        assertEquals("tag-1", domain.id)
        assertEquals("Kotlin", domain.name)
        assertEquals(TagSource.AI, domain.source)
        assertEquals(0.87, domain.confidence!!, 0.0001)
        assertEquals(100L, domain.createdAt)
    }

    @Test
    fun TagEntity_userTag_hasNullConfidence() {
        val domain = TagEntity(
            id = "tag-2",
            name = "待读",
            source = TagSource.USER,
            confidence = null,
            createdAt = 200L,
        ).toDomain()

        assertEquals(TagSource.USER, domain.source)
        assertNull(domain.confidence)
    }

    @Test
    fun Tag_roundTripThroughEntity_preservesAllFields() {
        val domain = TagEntity(
            id = "tag-3",
            name = "OCR",
            source = TagSource.AI,
            confidence = 0.5,
            createdAt = 300L,
        ).toDomain()

        assertEquals(domain, domain.toEntity().toDomain())
    }
}
