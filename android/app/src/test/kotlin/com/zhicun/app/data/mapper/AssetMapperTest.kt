package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.AssetEntity
import com.zhicun.app.domain.model.AssetSaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetMapperTest {

    private fun entity(fileHash: String? = "abc123", originalUri: String? = "content://media/1") =
        AssetEntity(
            id = "asset-1",
            recordId = "rec-1",
            mimeType = "image/jpeg",
            localPath = "/data/user/0/com.zhicun.app/files/assets/rec-1/a.jpg",
            fileSize = 2048L,
            fileHash = fileHash,
            originalUri = originalUri,
            saveStatus = AssetSaveStatus.SAVED,
        )

    @Test
    fun AssetEntity_allFieldsPresent_mapsToDomain() {
        val domain = entity().toDomain()

        assertEquals("asset-1", domain.id)
        assertEquals("rec-1", domain.recordId)
        assertEquals("image/jpeg", domain.mimeType)
        assertEquals("/data/user/0/com.zhicun.app/files/assets/rec-1/a.jpg", domain.localPath)
        assertEquals(2048L, domain.fileSize)
        assertEquals("abc123", domain.fileHash)
        assertEquals("content://media/1", domain.originalUri)
        assertEquals(AssetSaveStatus.SAVED, domain.saveStatus)
    }

    @Test
    fun AssetEntity_nullableFieldsNull_mapsToNulls() {
        val domain = entity(fileHash = null, originalUri = null).toDomain()

        assertNull(domain.fileHash)
        assertNull(domain.originalUri)
    }

    @Test
    fun Asset_roundTripThroughEntity_preservesAllFields() {
        val domain = entity().toDomain()

        assertEquals(domain, domain.toEntity().toDomain())
    }
}
