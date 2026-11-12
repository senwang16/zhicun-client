package com.zhicun.app.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 剪贴板文本规则（M13，纯函数）。
 *
 * 核心不变量：**保存的内容必须与用户复制的完全一致**（只允许去掉首尾空白）。
 * 压平缩进、合并空行这类"看起来更整洁"的处理，对复制代码的用户就是数据损坏。
 */
class ClipboardTextTest {

    // -------------------------------------------------------------- normalize

    @Test
    fun Normalize_null_returnsNull() {
        assertNull(ClipboardText.normalize(null))
    }

    @Test
    fun Normalize_blankOnly_returnsNull() {
        assertNull(ClipboardText.normalize("   \n\t  \r\n "))
    }

    @Test
    fun Normalize_empty_returnsNull() {
        assertNull(ClipboardText.normalize(""))
    }

    @Test
    fun Normalize_trimsSurroundingWhitespaceOnly() {
        assertEquals("一句话", ClipboardText.normalize("\n  一句话 \t\n"))
    }

    /** 内部缩进与空行必须原样保留：用户复制的可能是一段代码。 */
    @Test
    fun Normalize_keepsInnerIndentAndBlankLines() {
        val code = "fun main() {\n\n    println(1)\n}"

        assertEquals(code, ClipboardText.normalize("  $code  "))
    }

    /**
     * 落库清洗与判重键必须用同一组字符（2026-10-28 复审）。
     *
     * U+2000 不在 SQL `TRIM(...)` 的集合里：如果 normalize 用 `trim()` 把它去掉，
     * 存进去的文本就比判重键少一截，第二次复制同一内容会判不出重复。
     */
    @Test
    fun Normalize_preservesWhitespaceOutsideDedupSet() {
        assertEquals("\u2000一段内容\u2000", ClipboardText.normalize("\u2000一段内容\u2000"))
        assertEquals("\u000Ba\u000B", ClipboardText.normalize("\u000Ba\u000B"))
    }

    /** 裁剪集合之外的空白虽被保留，但"整段都是空白"仍不算可保存内容。 */
    @Test
    fun Normalize_whitespaceOnlyOutsideDedupSet_returnsNull() {
        assertNull(ClipboardText.normalize("\u2000\u2000"))
        assertNull(ClipboardText.normalize("\u000B"))
    }

    // ---------------------------------------------------------------- 判重键

    /**
     * 判重键的空白集合必须与 SQL `TRIM(...)` 完全一致（2026-10-28 修复）。
     *
     * 这里用 SQL 实际处理的五个字符做首尾包裹：只要 Kotlin 侧多去或少去任何字符，
     * 都会与 `RecordDao.findIdByNormalizedText` 产生不一致。
     */
    @Test
    fun DedupKey_trimsExactlyTheSqlWhitespaceSet() {
        assertEquals(
            "同一段内容",
            ClipboardText.dedupKey("\u3000 \t\r\n同一段内容\r\n\t \u3000"),
        )
    }

    /** `trim()` 会去掉 VT，但 SQL 的字符集里没有它 —— 判重键必须保留，避免两边算出的键不同。 */
    @Test
    fun DedupKey_keepsVerticalTabLikeSql() {
        assertEquals("\u000Ba\u000B", ClipboardText.dedupKey("\u000Ba\u000B"))
    }

    /** 换行符统一为 LF，但内部换行与缩进原样保留。 */
    @Test
    fun DedupKey_normalizesCrLfWithoutTouchingInnerLayout() {
        assertEquals(
            "fun main() {\n\n    println(1)\n}",
            ClipboardText.dedupKey("  fun main() {\r\n\r\n    println(1)\r\n}  "),
        )
    }

    // ---------------------------------------------------------------- preview

    @Test
    fun Preview_shortText_isUnchanged() {
        assertEquals("短内容", ClipboardText.preview("短内容"))
    }

    /** 通知只有一行的高度：换行必须折叠，否则"已保存"会被挤出去。 */
    @Test
    fun Preview_collapsesNewlinesIntoSingleSpace() {
        assertEquals(
            "第一行 第二行 第三行",
            ClipboardText.preview("第一行\n第二行\n\n第三行"),
        )
    }

    @Test
    fun Preview_exactlyMaxLength_hasNoEllipsis() {
        val text = "a".repeat(10)

        assertEquals(text, ClipboardText.preview(text, maxLength = 10))
    }

    @Test
    fun Preview_longerThanMax_truncatesWithEllipsis() {
        val preview = ClipboardText.preview("a".repeat(50), maxLength = 10)

        assertEquals("a".repeat(10) + "…", preview)
    }

    /**
     * 截断点正好落在空白上时不留下"空格 + 省略号"这种半截效果。
     */
    @Test
    fun Preview_truncationPointOnWhitespace_trimsTrailingSpace() {
        val preview = ClipboardText.preview("abcdefg hijklmn", maxLength = 8)

        assertEquals("abcdefg…", preview)
    }

    /**
     * 截断后的长度允许比 `maxLength` 多 1（省略号本身）。
     * 这条不变量保证预览永远塞得进一行，不会因为省略号把行撑爆。
     */
    @Test
    fun Preview_neverExceedsMaxPlusEllipsis() {
        val preview = ClipboardText.preview("字".repeat(500), maxLength = 20)

        assertEquals(21, preview.length)
    }

    @Test
    fun Preview_multilineLongText_resultIsSingleLine() {
        val text = buildString {
            repeat(30) { append("第").append(it).append("行\n") }
        }

        val preview = ClipboardText.preview(text, maxLength = 40)

        assertEquals(false, preview.contains('\n'))
    }
}
