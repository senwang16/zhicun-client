package com.zhicun.app.domain.ai

import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "这次外发用了哪些内容块"与"记录里现在有哪些块"（2026-10-23 审查 R-2）。
 *
 * 这两件事必须由同一份代码回答：确认范围来自后者，闸门比对用的是前者。
 * 若两边各写一套 `if (rawText != null) ...`，迟早出现"发了转写文本、却只报了原文"，
 * 而那正是这次要修的漏洞。
 */
class ProcessingInputTextTest {

    private val record = testRecord(rawText = "会议纪要", sourceTitle = "周会")

    @Test
    fun SummaryInput_reportsRawTextAndTitle() {
        val input = ProcessingInputText.summaryInput(record, emptyList())

        assertTrue(input.text.contains("会议纪要"))
        assertEquals(
            setOf(ContentPart.RAW_TEXT, ContentPart.SOURCE_TITLE),
            input.parts,
        )
    }

    /** 转写文本参与了摘要 → 必须出现在清单里，否则闸门无从判断"用户看过它没有"。 */
    @Test
    fun SummaryInput_includesDerivedBodyParts() {
        val derived = listOf(
            testDerived(contentType = DerivedType.TRANSCRIPTION, content = "录音里的内容"),
        )

        val input = ProcessingInputText.summaryInput(record, derived)

        assertTrue(input.text.contains("录音里的内容"))
        assertTrue(ContentPart.TRANSCRIPTION in input.parts)
    }

    /** 空内容不算"用到了这一块"：空串拼进去只会多两个换行，不该撑大确认范围。 */
    @Test
    fun SummaryInput_skipsBlankParts() {
        val derived = listOf(testDerived(contentType = DerivedType.OCR_TEXT, content = "   "))

        val input = ProcessingInputText.summaryInput(record.copy(rawText = null, sourceTitle = null), derived)

        assertEquals("", input.text)
        assertEquals(emptySet<ContentPart>(), input.parts)
    }

    /** 多图记录：同类型多条 + 附件顺序前缀，模型才知道这是几份不同的材料。 */
    @Test
    fun SummaryInput_prefixesMultipleOcrSegmentsInOrder() {
        val derived = listOf(
            testDerived(id = "d1", contentType = DerivedType.OCR_TEXT, content = "截图一", assetId = "a1"),
            testDerived(id = "d2", contentType = DerivedType.OCR_TEXT, content = "截图二", assetId = "a2"),
        )

        val input = ProcessingInputText.summaryInput(record.copy(rawText = null, sourceTitle = null), derived)

        assertEquals("第 1 张图片：截图一\n\n第 2 张图片：截图二", input.text)
        assertEquals(setOf(ContentPart.OCR_TEXT), input.parts)
    }

    @Test
    fun SummaryInput_prefixesMultipleTranscriptionSegments() {
        val derived = listOf(
            testDerived(id = "d1", contentType = DerivedType.TRANSCRIPTION, content = "上午站会", assetId = "v1"),
            testDerived(id = "d2", contentType = DerivedType.TRANSCRIPTION, content = "客户电话", assetId = "v2"),
        )

        val input = ProcessingInputText.summaryInput(record.copy(rawText = null, sourceTitle = null), derived)

        assertEquals("第 1 段音频：上午站会\n\n第 2 段音频：客户电话", input.text)
        assertEquals(setOf(ContentPart.TRANSCRIPTION), input.parts)
    }

    /** 单文件记录保持改造前的输入，不凭空多出"第 1 张图片"。 */
    @Test
    fun SummaryInput_singleSegment_hasNoPrefix() {
        val derived = listOf(
            testDerived(contentType = DerivedType.OCR_TEXT, content = "白板上的架构图", assetId = "a1"),
        )

        val input = ProcessingInputText.summaryInput(record.copy(rawText = null, sourceTitle = null), derived)

        assertEquals("白板上的架构图", input.text)
    }

    /** 有摘要就走摘要（省 token），此时用的块是 SUMMARY 而不是原文。 */
    @Test
    fun EmbeddingInput_prefersDigest() {
        val derived = listOf(testDerived(contentType = DerivedType.SUMMARY, content = "三点结论"))

        val input = ProcessingInputText.embeddingInput(record, derived)

        assertEquals("三点结论", input.text)
        assertEquals(setOf(ContentPart.SUMMARY), input.parts)
    }

    /** 摘要还没生成 → 退回正文，清单也跟着变成正文那几块。 */
    @Test
    fun EmbeddingInput_withoutDigest_fallsBackToBody() {
        val input = ProcessingInputText.embeddingInput(record, emptyList())

        assertEquals(
            setOf(ContentPart.RAW_TEXT, ContentPart.SOURCE_TITLE),
            input.parts,
        )
    }

    // ------------------------------------------------------------- 确认范围

    /**
     * 确认范围 = 两条外发路径的并集（+ 会被上传的附件）。
     *
     * 这条断言的是"结构上同源"：改了任一路径的拼装规则，这里会自动跟着变，
     * 不会出现"确认时漏记 OCR、外发时却发 OCR"。
     */
    @Test
    fun PresentParts_isUnionOfAllOutboundPaths() {
        val derived = listOf(
            testDerived(contentType = DerivedType.TRANSCRIPTION, content = "录音内容"),
            testDerived(contentType = DerivedType.SUMMARY, content = "摘要"),
        )

        val parts = ProcessingInputText.presentParts(record, derived, attachmentUploadable = true)

        assertEquals(
            setOf(
                ContentPart.RAW_TEXT,
                ContentPart.SOURCE_TITLE,
                ContentPart.TRANSCRIPTION,
                ContentPart.SUMMARY,
                ContentPart.ATTACHMENT,
            ),
            parts,
        )
    }

    /**
     * 图片附件不算"会被上传的内容"：OCR 在本地做，图片字节从不出设备。
     * 把它算进确认范围只会让"已确认内容"这句话变得不准确。
     */
    @Test
    fun PresentParts_excludesAttachmentWhenNothingIsUploaded() {
        val parts = ProcessingInputText.presentParts(
            record.copy(type = RecordType.IMAGE),
            emptyList(),
            attachmentUploadable = false,
        )

        assertEquals(setOf(ContentPart.RAW_TEXT, ContentPart.SOURCE_TITLE), parts)
    }

    // ------------------------------------------------------------- 合集（M20）

    @Test
    fun CollectionSummaryInput_ordersMembersAndExcludesDeleted() {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = "合集说明")
        val members = listOf(
            CollectionMemberInput(testRecord(id = "m1", rawText = "第一条"), emptyList()),
            CollectionMemberInput(
                testRecord(id = "m2", rawText = "这条不该出现", isDeleted = true),
                emptyList(),
            ),
            CollectionMemberInput(
                testRecord(id = "m3", rawText = "第三条", archivedAt = 5_000L),
                emptyList(),
            ),
        )

        val input = ProcessingInputText.collectionSummaryInput(collection, emptyList(), members)

        assertEquals("合集说明\n\n第一条\n\n第三条", input.text)
        assertTrue("归档只影响收件箱，不排除生成", input.text.contains("第三条"))
        assertEquals(
            setOf(ContentPart.RAW_TEXT),
            input.parts,
        )
    }

    @Test
    fun CollectionSummaryInput_delegatesDerivedPartsOfMembers() {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = null)
        val members = listOf(
            CollectionMemberInput(
                testRecord(id = "m1", rawText = null),
                listOf(testDerived(recordId = "m1", contentType = DerivedType.OCR_TEXT, content = "截图文字")),
            ),
        )

        val input = ProcessingInputText.collectionSummaryInput(collection, emptyList(), members)

        assertEquals("截图文字", input.text)
        assertEquals(setOf(ContentPart.OCR_TEXT), input.parts)
    }

    @Test
    fun CollectionSummaryInput_splitsBudgetAcrossActiveMembers() {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = null)
        val members = listOf(
            CollectionMemberInput(testRecord(id = "m1", rawText = "a".repeat(3_000)), emptyList()),
            CollectionMemberInput(testRecord(id = "m2", rawText = "b".repeat(3_000)), emptyList()),
        )

        val input = ProcessingInputText.collectionSummaryInput(collection, emptyList(), members)

        // 预算 4000 平均分给两条 → 每条最多 2000 字，不会出现"第一条吃光预算"
        assertTrue(input.text.contains("a".repeat(2_000)))
        assertTrue(input.text.contains("b".repeat(2_000)))
        assertTrue(!input.text.contains("a".repeat(2_001)))
        assertTrue(!input.text.contains("b".repeat(2_001)))
    }

    @Test
    fun CollectionPresentParts_includesMemberPartsWithoutAttachment() {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = "合集说明")
        val members = listOf(
            CollectionMemberInput(
                testRecord(id = "m1", rawText = "成员一"),
                listOf(testDerived(recordId = "m1", contentType = DerivedType.OCR_TEXT, content = "图片文字")),
            ),
            CollectionMemberInput(
                testRecord(id = "m2", rawText = null),
                listOf(
                    testDerived(
                        recordId = "m2",
                        contentType = DerivedType.TRANSCRIPTION,
                        content = "录音转写",
                    ),
                ),
            ),
        )

        val parts = ProcessingInputText.collectionPresentParts(collection, emptyList(), members)

        assertEquals(
            setOf(ContentPart.RAW_TEXT, ContentPart.OCR_TEXT, ContentPart.TRANSCRIPTION),
            parts,
        )
    }

    @Test
    fun CollectionEmbeddingInput_prefersCollectionDigest() {
        val collection = testRecord(id = "c1", type = RecordType.COLLECTION, rawText = "合集说明")
        val derived = listOf(testDerived(recordId = "c1", contentType = DerivedType.SUMMARY, content = "合集结论"))
        val members = listOf(CollectionMemberInput(testRecord(id = "m1"), emptyList()))

        val input = ProcessingInputText.collectionEmbeddingInput(collection, derived, members)

        assertEquals("合集结论", input.text)
        assertEquals(setOf(ContentPart.SUMMARY), input.parts)
    }
}
