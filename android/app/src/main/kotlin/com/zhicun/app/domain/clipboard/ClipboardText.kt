package com.zhicun.app.domain.clipboard

/**
 * 剪贴板文本的清洗规则（M13，纯函数）。
 *
 * ## 为什么不清洗内容本身
 * "保存剪贴板"的语义就是**原样保存用户复制的东西**。删换行、合并空白、截断长度
 * 这些"让数据更好看"的处理，在这里都是篡改：用户复制的是一段代码，缩进被压平就废了。
 * 因此只做两件事：去掉首尾空白、判断是否为空。
 *
 * 唯一被限制的是**通知里显示的预览**：一条记录可能有上万字，通知栏塞不下。
 */
object ClipboardText {

    /** 通知/提示里预览的最大字符数。 */
    const val PREVIEW_LENGTH: Int = 40

    /**
     * 归一化：仅去首尾空白。
     *
     * 首尾空白与 [dedupKey]/SQL `TRIM(...)` 用**同一组字符**（[DEDUP_TRIM_CHARS]）：
     * 落库文本与判重键必须经过同一把尺子，否则判重键里保留的首尾字符会在落库时被去掉，
     * 第二次复制同一内容就判不出重复（2026-10-28 复审）。
     * 只去这五个字符意味着 U+2000、VT 这类更冷门的空白会原样保留 —— 这正是
     * "原样保存用户复制的东西"要的结果。
     *
     * @return 空白/`null` 输入返回 `null`（表示"没有可保存的内容"）；
     *   仅含空白但不在裁剪集合里的内容（如一个 U+2000）同样算空
     */
    fun normalize(raw: String?): String? =
        raw?.trim(*DEDUP_TRIM_CHARS)?.takeIf { it.isNotBlank() }

    /**
     * 判重用的归一化（2026-10-27，PRD §2.4）：仅统一换行符 + 去首尾空白。
     *
     * **不代表保存内容**：真正落库的仍是 [normalize] 后的原文（保留用户复制时的换行风格）。
     * Windows 剪贴板常带 `\r\n`，同一段内容从不同应用复制出来换行符可能不同，
     * 不统一就会漏判重复；URL 参数、中间空格一律保持原样，避免"看起来一样、实际不同"
     * 的内容被误删。
     *
     * ## 为什么不用 `trim()`
     * 2026-10-28 修复：Kotlin 的 `trim()` 按 `Char.isWhitespace` 处理，会去掉 VT、FF、
     * U+2000 等；而 SQL 侧的 `TRIM(...)` 是显式字符集。两边范围不同就会出现
     * "Kotlin 算出的判重键与 SQL 不相等"的漏判。这里改为与
     * `RecordDao.findIdByNormalizedText` 完全相同的一组字符（tab/LF/CR/半角空格/全角空格），
     * 改任何一边都必须同步另一边。
     *
     * 该规则必须与 `RecordDao.findIdByNormalizedText` 的 SQL 表达式保持一致。
     */
    fun dedupKey(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim(*DEDUP_TRIM_CHARS)

    /**
     * 生成一行预览：换行折叠成空格，超长截断加省略号。
     *
     * 换行必须折叠 —— 直接塞进通知会显示成一个巨大的多行块，
     * 甚至把"已保存"几个字挤出可见区域。
     */
    fun preview(text: String, maxLength: Int = PREVIEW_LENGTH): String {
        val singleLine = text.trim().replace(WHITESPACE_RUN, " ")
        if (singleLine.length <= maxLength) return singleLine
        return singleLine.take(maxLength).trimEnd() + "…"
    }

    /** 连续空白（含换行/制表符）压成一个空格。 */
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 判重键首尾允许去掉的字符，必须与 `RecordDao.findIdByNormalizedText` 的
     * `TRIM(...)` 字符集逐字一致：tab、LF、CR、半角空格、全角空格（U+3000）。
     */
    private val DEDUP_TRIM_CHARS = charArrayOf('\t', '\n', '\r', ' ', '\u3000')
}
