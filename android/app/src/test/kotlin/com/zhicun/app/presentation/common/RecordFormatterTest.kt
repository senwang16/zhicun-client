package com.zhicun.app.presentation.common

import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.fake.testRecord
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示文本规则的单测（纯 JVM，不需要 Robolectric）。
 *
 * 这些规则直接决定"卡片上看到什么"，出错时用户看到的是空白卡片或标题与摘要重复。
 */
class RecordFormatterTest {

    @Test
    fun DisplayTitle_withSourceTitle_prefersSourceTitleOverRawText() {
        val record = testRecord(
            sourceTitle = "  Compose 官方文档  ",
            rawText = "正文第一行\n正文第二行",
        )

        assertEquals("Compose 官方文档", record.displayTitle())
    }

    @Test
    fun DisplayTitle_withoutSourceTitle_usesFirstNonBlankLine() {
        val record = testRecord(sourceTitle = null, rawText = "\n\n  买牛奶  \n记得买两盒")

        assertEquals("买牛奶", record.displayTitle())
    }

    @Test
    fun DisplayTitle_allBlank_returnsNull() {
        val record = testRecord(sourceTitle = "   ", rawText = "\n \n")

        assertNull(record.displayTitle())
    }

    @Test
    fun DisplaySnippet_dropsTitleLine() {
        val record = testRecord(rawText = "标题行\n第二行内容\n第三行内容")

        assertEquals("第二行内容 第三行内容", record.displaySnippet())
    }

    @Test
    fun DisplaySnippet_singleLineFallsBackToUrl() {
        val record = testRecord(rawText = "只有一行", sourceUrl = "https://example.com/a")

        assertEquals("https://example.com/a", record.displaySnippet())
    }

    @Test
    fun DisplaySnippet_noRawTextAndNoUrl_returnsNull() {
        val record = testRecord(rawText = null, sourceUrl = null)

        assertNull(record.displaySnippet())
    }

    @Test
    fun FormatFileSize_scalesUnits() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", formatFileSize(2L * 1024 * 1024 * 1024))
        assertEquals("—", formatFileSize(-1))
    }

    @Test
    fun RecordTypeShortRes_distinctForEachType() {
        val shorts = RecordType.entries.map { recordTypeShortRes(it) }

        assertEquals("每种记录类型都应有独立的徽标文案", RecordType.entries.size, shorts.toSet().size)
    }

    // ------------------------------------------------ 兜底标题（2026-10-24）

    /**
     * 录音、图片、文件都没有 `raw_text`（转写/OCR 落在派生内容里），卡片只能靠兜底标题区分。
     *
     * 三者必须互不相同：三者的卡片外形完全一样（都没正文），标题里的类型词是唯一线索。
     */
    @Test
    fun RecordFallbackTitleRes_mediaTypesAreDistinct() {
        val voice = recordFallbackTitleRes(RecordType.VOICE_NOTE)
        val image = recordFallbackTitleRes(RecordType.IMAGE)
        val file = recordFallbackTitleRes(RecordType.FILE)

        assertEquals("录音/图片/文件必须能一眼分开", 3, setOf(voice, image, file).size)
        assertFalse(
            "录音不该退化成中性的'记录'",
            voice == recordFallbackTitleRes(RecordType.TEXT_NOTE),
        )
    }

    /**
     * 兜底标题里的时间只有月日与时分，不带年份。
     *
     * 卡片标题只有一行，带年份会把"几点录的"挤出可视区；年份在详情页能看到。
     */
    @Test
    fun FallbackTitleTimePattern_isMonthDayAndHourMinute() {
        val text = formatTimestamp(
            1_700_000_000_000L,
            Locale.SIMPLIFIED_CHINESE,
            TimestampStyle.COMPACT,
        )

        assertTrue("实际：$text", Regex("""^\d{2}-\d{2} \d{2}:\d{2}$""").matches(text))
    }

    /** 英文界面要用月名在前、12 小时制的写法；跟着设备 Locale 走会让中英混排（R-I18N-12）。 */
    @Test
    fun CompactTimestamp_englishUsesMonthNameAndAmPm() {
        val text = formatTimestamp(1_700_000_000_000L, Locale.ENGLISH, TimestampStyle.COMPACT)

        assertTrue("实际：$text", Regex("""^[A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2} [AP]M$""").matches(text))
    }

    @Test
    fun FullTimestamp_chineseKeepsYearAnd24HourClock() {
        val text = formatTimestamp(1_700_000_000_000L, Locale.SIMPLIFIED_CHINESE)

        assertTrue("实际：$text", Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").matches(text))
    }
}
