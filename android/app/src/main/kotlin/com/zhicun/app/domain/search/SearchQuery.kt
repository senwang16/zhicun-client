package com.zhicun.app.domain.search

/**
 * 搜索词归一化与检索表达式构造（M10）。
 *
 * ## 为什么要拆成"FTS + 子串"两条路
 * 索引后端是 FTS4（Android 系统 SQLite 没有 FTS5，见
 * [com.zhicun.app.data.local.FtsSyncCallback]），且用的是默认 `simple` 分词器：
 * 它把**连续的非 ASCII 字节当成一个 token**。所以"断联陪伴"是**一个** token，
 * 检索"陪伴"永远不命中 —— 对中文用户来说，纯 FTS 等于搜索不可用。
 *
 * 因此中文查询必须走 `LIKE '%词%'` 子串匹配（全表扫描，记录量在个人知识库量级完全够用），
 * 而 ASCII 单词走 FTS 前缀查询（走索引、支持多词 AND）。两条路的结果在
 * [com.zhicun.app.data.repository.SearchRepositoryImpl] 里合并去重。
 *
 * ## 为什么不在这里做 SQL 字符串拼接
 * 本类只产出**参数值**（MATCH 表达式 / LIKE 模式），SQL 文本在 DAO 里固定不变，
 * 用户输入永远以绑定参数进入查询 —— FTS 的 MATCH 语法注入与 SQL 注入都无从谈起。
 * 唯一的例外是 FTS 表达式里的 `*`，它是本类自己加的前缀通配符，且词干只允许
 * `[A-Za-z0-9_]`。
 */
object SearchQuery {

    /** 查询串长度上限：再长也没有检索意义，反而拖慢 LIKE 扫描。 */
    const val MAX_LENGTH: Int = 100

    /** FTS 词干最短长度：单字符前缀会命中太多行，得不偿失。 */
    private const val MIN_FTS_TERM_LENGTH: Int = 2

    private val WHITESPACE = Regex("\\s+")
    private val ASCII_WORD = Regex("[A-Za-z0-9_]+")

    /**
     * 归一化：去首尾空白、把连续空白压成一个空格、截断到 [MAX_LENGTH]。
     *
     * @return 归一化后的查询串；空串或全空白返回 null（调用方据此回到"未搜索"状态）
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val collapsed = trimmed.replace(WHITESPACE, " ")
        return collapsed.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    /** 按空白切成词。 */
    fun terms(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotEmpty() }

    /**
     * FTS4 查询表达式：每个 ASCII 词加 `*` 做前缀匹配，多词之间是隐式 AND。
     *
     * @return 没有可用 ASCII 词（纯中文查询）时返回 null，此时只走子串匹配
     */
    fun ftsExpression(normalized: String): String? {
        val parts = terms(normalized)
            .filter { ASCII_WORD.matches(it) && it.length >= MIN_FTS_TERM_LENGTH }
            .map { "$it*" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * LIKE 模式（已含两侧 `%`）。
     *
     * `\`、`%`、`_` 必须转义，否则用户搜 "100%" 会变成"任意后缀"，搜 "a_b" 会匹配 "axb"。
     * 调用方的 SQL 必须带 `ESCAPE '\'`，两处必须同时成立才有意义。
     */
    fun likePattern(normalized: String): String {
        val escaped = normalized
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
