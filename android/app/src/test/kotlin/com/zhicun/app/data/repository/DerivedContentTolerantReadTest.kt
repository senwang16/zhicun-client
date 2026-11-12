package com.zhicun.app.data.repository

import com.zhicun.app.data.local.AppDatabase
import com.zhicun.app.data.local.AppDatabaseFactory
import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.data.local.entity.RecordEntity
import com.zhicun.app.data.mapper.toDomainOrNull
import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.LocalStatus
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 派生内容容错读取（2026-11-10 Bug 4）。
 *
 * ## 复现的是什么
 * 真机日志：`IllegalArgumentException: No enum constant
 * com.zhicun.app.domain.model.DerivedType.TRANSCRIBE`，整页详情打不开
 * （预埋 SQL 把 `TRANSCRIPTION` 写成了 `TRANSCRIBE`）。
 *
 * ## 验收口径
 * - 一条脏行只应少一块派生内容：同记录下可识别的内容照常返回；
 * - 只有脏行时也不能抛异常（详情页至少还能显示原文）。
 */
@RunWith(RobolectricTestRunner::class)
class DerivedContentTolerantReadTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: DerivedContentRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        database = AppDatabaseFactory.createInMemory(RuntimeEnvironment.getApplication())
        repository = DerivedContentRepositoryImpl(database.derivedContentDao())
        // 外键：derived_contents.record_id → records.id
        database.recordDao().insert(
            RecordEntity(
                id = RECORD_ID,
                type = RecordType.VOICE_NOTE,
                captureSource = CaptureSource.MANUAL,
                rawText = "一条语音记录",
                sourceUrl = null,
                sourceTitle = null,
                sourceApp = null,
                createdAt = 1L,
                updatedAt = 1L,
                localStatus = LocalStatus.SAVED,
                processingStatus = ProcessingStatus.DONE,
                sensitivityStatus = SensitivityStatus.CLEAR,
                recordRevision = 1,
                isDeleted = false,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getByRecordId_skipsRowWithUnrecognizedContentType() = runBlocking {
        repository.upsert(validTranscription())
        insertRawRow(id = "dirty-1", contentType = "TRANSCRIBE")

        val contents = repository.getByRecordId(RECORD_ID)

        assertEquals(1, contents.size)
        assertEquals(DerivedType.TRANSCRIPTION, contents.single().contentType)
        assertEquals("转写正文", contents.single().content)
    }

    @Test
    fun getByRecordId_onlyDirtyRows_returnsEmptyInsteadOfThrowing() = runBlocking {
        insertRawRow(id = "dirty-1", contentType = "TRANSCRIBE")

        assertTrue(repository.getByRecordId(RECORD_ID).isEmpty())
    }

    /** mapper 的判据只有一处：`UNKNOWN` 不进 domain。 */
    @Test
    fun toDomainOrNull_unknownType_isNull() {
        val entity = validEntity(id = "dirty-2").copy(contentType = DerivedType.UNKNOWN)
        assertNull(entity.toDomainOrNull())
    }

    private fun validTranscription(): DerivedContent = DerivedContent(
        id = "ok-1",
        recordId = RECORD_ID,
        contentType = DerivedType.TRANSCRIPTION,
        content = "转写正文",
        processorVersion = "test",
        processedAt = 1L,
        errorMessage = null,
    )

    private fun validEntity(id: String) = DerivedContentEntity(
        id = id,
        recordId = RECORD_ID,
        contentType = DerivedType.TRANSCRIPTION,
        content = "x",
        processorVersion = "test",
        processedAt = 1L,
        errorMessage = null,
    )

    /**
     * 直接写库模拟"手工 SQL / 旧版本写进来的脏数据"。
     *
     * 不能走 DAO：`DerivedType` 类型的参数无法表达 `TRANSCRIBE` 这种不存在于枚举的值 ——
     * 这正是这个 bug 只可能从 SQL 侧进来的原因。
     */
    private fun insertRawRow(id: String, contentType: String) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO derived_contents
                (id, record_id, content_type, content, processor_version, processed_at,
                 error_message, asset_id)
            VALUES (?, ?, ?, '脏数据', 'test', 1, NULL, '')
            """.trimIndent(),
            arrayOf(id, RECORD_ID, contentType),
        )
    }

    private companion object {
        const val RECORD_ID = "rec-voice-1"
    }
}