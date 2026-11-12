package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMapperTest {

    private fun entity(
        rawText: String? = "内容",
        sourceUrl: String? = "https://example.com",
        sourceTitle: String? = "标题",
        sourceApp: String? = "Chrome",
    ) = RecordEntity(
        id = "rec-1",
        type = RecordType.SHARED_TEXT,
        captureSource = CaptureSource.SYSTEM_SHARE,
        rawText = rawText,
        sourceUrl = sourceUrl,
        sourceTitle = sourceTitle,
        sourceApp = sourceApp,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        localStatus = LocalStatus.SAVED,
        processingStatus = ProcessingStatus.PENDING,
        sensitivityStatus = SensitivityStatus.UNKNOWN,
        recordRevision = 2,
        isDeleted = false,
    )

    @Test
    fun RecordEntity_allFieldsPresent_mapsToDomainWithoutLoss() {
        val domain = entity().toDomain()

        assertEquals("rec-1", domain.id)
        assertEquals(RecordType.SHARED_TEXT, domain.type)
        assertEquals(CaptureSource.SYSTEM_SHARE, domain.captureSource)
        assertEquals("内容", domain.rawText)
        assertEquals("https://example.com", domain.sourceUrl)
        assertEquals("标题", domain.sourceTitle)
        assertEquals("Chrome", domain.sourceApp)
        assertEquals(1_000L, domain.createdAt)
        assertEquals(2_000L, domain.updatedAt)
        assertEquals(LocalStatus.SAVED, domain.localStatus)
        assertEquals(ProcessingStatus.PENDING, domain.processingStatus)
        assertEquals(SensitivityStatus.UNKNOWN, domain.sensitivityStatus)
        assertEquals(2, domain.recordRevision)
        assertEquals(false, domain.isDeleted)
    }

    @Test
    fun RecordEntity_allNullableFieldsNull_mapsToNulls() {
        val domain = entity(rawText = null, sourceUrl = null, sourceTitle = null, sourceApp = null)
            .toDomain()

        assertNull(domain.rawText)
        assertNull(domain.sourceUrl)
        assertNull(domain.sourceTitle)
        assertNull(domain.sourceApp)
    }

    @Test
    fun Record_roundTripThroughEntity_preservesAllFields() {
        val domain = entity().toDomain()

        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun Record_toEntity_keepsSoftDeleteFlag() {
        val deleted = entity().copy(isDeleted = true).toDomain()

        assertEquals(true, deleted.toEntity().isDeleted)
    }

    // ------------------------------------------------------------ 整理标记（M16）

    /**
     * 收藏 / 置顶 / 归档必须原样往返。
     *
     * 这三个标记是"用户怎么看这条记录"的唯一真源，映射漏一个的后果是"点了收藏，
     * 重进页面又变回未收藏"——数据其实写进去了，只是读出来时被吞掉。
     */
    @Test
    fun Record_organizeFlags_roundTripThroughEntity() {
        val domain = entity().toDomain().copy(isFavorite = true, isPinned = true, archivedAt = 7_000L)

        val restored = domain.toEntity().toDomain()

        assertEquals(true, restored.isFavorite)
        assertEquals(true, restored.isPinned)
        assertEquals(7_000L, restored.archivedAt)
        assertTrue(restored.isArchived)
    }

    /** 未归档时是 null 而不是 0：0 会被误判成"归档于 1970 年"。 */
    @Test
    fun Record_notArchived_mapsToNullTimestamp() {
        val domain = entity().toDomain()

        assertNull(domain.archivedAt)
        assertFalse(domain.isArchived)
    }
}
