package com.zhicun.app.domain.model

import com.zhicun.app.fake.testRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认范围的取值与判定（2026-10-23 审查 R-2）。
 *
 * 这类"编码进一列 TEXT"的设计只有两处会出错：编码/解码不对称，
 * 以及"哪些块需要确认"这条规则被不同调用点各写一遍。两个都钉在这里。
 */
class ContentPartTest {

    // ---------------------------------------------------------------- 编码解码

    @Test
    fun Encode_thenDecode_roundTrips() {
        val parts = setOf(ContentPart.RAW_TEXT, ContentPart.TRANSCRIPTION, ContentPart.ATTACHMENT)

        assertEquals(parts, ContentPart.decode(ContentPart.encode(parts)))
    }

    /** 顺序必须稳定：同一批内容两次编码要得到同一个字符串，否则 DB 会写出假变更。 */
    @Test
    fun Encode_isOrderIndependent() {
        val a = ContentPart.encode(setOf(ContentPart.ATTACHMENT, ContentPart.RAW_TEXT))
        val b = ContentPart.encode(setOf(ContentPart.RAW_TEXT, ContentPart.ATTACHMENT))

        assertEquals(a, b)
    }

    @Test
    fun Encode_emptySet_isEmptyString() {
        assertEquals("", ContentPart.encode(emptySet()))
    }

    /**
     * 不认识的名字直接丢掉。
     *
     * 两种真实来源：用户装过新版本又回退（枚举多了一个值）、数据被外部工具改过。
     * 丢掉之后的后果是"这块不在确认范围内"，也就是**更严格**，方向是安全的。
     */
    @Test
    fun Decode_ignoresUnknownNames() {
        assertEquals(
            setOf(ContentPart.RAW_TEXT),
            ContentPart.decode("RAW_TEXT,SOMETHING_NEW"),
        )
        assertEquals(emptySet<ContentPart>(), ContentPart.decode("WHATEVER"))
        assertEquals(emptySet<ContentPart>(), ContentPart.decode(" , "))
        assertEquals(emptySet<ContentPart>(), ContentPart.decode(null))
    }

    // -------------------------------------------------------------- 哪些要确认

    /** AI 自己的产物（摘要 / 标签）不需要单独确认；外部世界的内容一律要。 */
    @Test
    fun RequiresConfirmation_exemptsAiProducedPartsOnly() {
        assertFalse(ContentPart.SUMMARY.requiresConfirmation)
        assertFalse(ContentPart.TAGS.requiresConfirmation)

        listOf(
            ContentPart.RAW_TEXT,
            ContentPart.SOURCE_TITLE,
            ContentPart.PAGE_TEXT,
            ContentPart.OCR_TEXT,
            ContentPart.TRANSCRIPTION,
            ContentPart.ATTACHMENT,
        ).forEach { part ->
            assertTrue("${part.name} 应当需要确认", part.requiresConfirmation)
        }
    }

    // ------------------------------------------------------- Record 的组合判定

    @Test
    fun CloudConfirmedFor_requiresBothVersionAndScope() {
        val confirmed = testRecord(
            recordRevision = 3,
            cloudConfirmedRevision = 3,
            cloudConfirmedScope = setOf(ContentPart.RAW_TEXT),
        )
        assertTrue(confirmed.cloudConfirmed)
        assertTrue(confirmed.cloudConfirmedFor(setOf(ContentPart.RAW_TEXT)))

        // 版本号对、范围不够 → 不算确认过
        assertFalse(confirmed.cloudConfirmedFor(setOf(ContentPart.RAW_TEXT, ContentPart.OCR_TEXT)))

        // 范围够、版本号过期 → 也不算
        val stale = confirmed.copy(recordRevision = 4)
        assertFalse(stale.cloudConfirmedFor(setOf(ContentPart.RAW_TEXT)))
    }

    /** 没确认过范围的旧记录（`cloudConfirmedScope` 为空）不能再放行任何需要确认的块。 */
    @Test
    fun CloudConfirmedFor_emptyScopeBlocksContentParts() {
        val noScope = testRecord(recordRevision = 1, cloudConfirmedRevision = 1)

        assertTrue(noScope.cloudConfirmed)
        assertFalse(noScope.cloudConfirmedFor(setOf(ContentPart.RAW_TEXT)))
        // 但"只发摘要"这种不需要确认的组合仍然可以（摘要由已放行的内容浓缩而来）
        assertTrue(noScope.cloudConfirmedFor(setOf(ContentPart.SUMMARY)))
        // 什么都不发也当然可以
        assertTrue(noScope.cloudConfirmedFor(emptySet()))
    }
}
