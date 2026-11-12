package com.zhicun.app.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询串归一化与表达式构造（M10）。
 *
 * 这些是纯函数，全部在 JVM 上直接验证：它们决定了"用户输入什么"与"SQL 收到什么"
 * 之间的映射，是检索链路里最容易出错、也最容易测的一层。
 */
class SearchQueryTest {

    @Test
    fun Normalize_blank_returnsNull() {
        assertNull(SearchQuery.normalize(null))
        assertNull(SearchQuery.normalize(""))
        assertNull(SearchQuery.normalize("   \n\t "))
    }

    @Test
    fun Normalize_collapsesWhitespaceAndTrims() {
        assertEquals("断联 陪伴", SearchQuery.normalize("  断联 \n\t 陪伴  "))
    }

    @Test
    fun Normalize_capsLength() {
        val long = "a".repeat(SearchQuery.MAX_LENGTH + 50)

        val normalized = SearchQuery.normalize(long)!!

        assertEquals(SearchQuery.MAX_LENGTH, normalized.length)
    }

    /** 纯中文查询不生成 FTS 表达式：simple 分词器会把整段汉字当一个 token，MATCH 必然零命中。 */
    @Test
    fun FtsExpression_chineseOnly_returnsNull() {
        assertNull(SearchQuery.ftsExpression("断联陪伴"))
        assertNull(SearchQuery.ftsExpression("陪伴 计划"))
    }

    @Test
    fun FtsExpression_asciiTerms_becomePrefixQueries() {
        assertEquals("compose*", SearchQuery.ftsExpression("compose"))
        // 多词是隐式 AND：两个词都要命中
        assertEquals("kotlin* coroutines*", SearchQuery.ftsExpression("kotlin coroutines"))
    }

    @Test
    fun FtsExpression_skipsSingleCharAndMixedTerms() {
        // 单词干太短（命中面过大）与含非 ASCII 的词都不进 FTS，交给子串匹配
        assertNull(SearchQuery.ftsExpression("a"))
        assertEquals("compose*", SearchQuery.ftsExpression("compose 陪伴"))
    }

    /**
     * FTS 语法注入防护：用户输入里的引号/星号/连字符不能进入表达式。
     * 含这类字符的"词"整段丢弃（子串匹配那一路仍然会覆盖它们），
     * 于是 MATCH 表达式里只可能出现 `[A-Za-z0-9_]` 加本类自己加的 `*`。
     */
    @Test
    fun FtsExpression_dropsTermsWithFtsSyntaxCharacters() {
        assertNull(SearchQuery.ftsExpression("\" -- *"))
        // "foo\"bar" 与 "baz*" 都不是纯 ASCII 词 → 整句没有可用词干
        assertNull(SearchQuery.ftsExpression("foo\"bar baz*"))
        // 同句里合法的词仍然保留
        assertEquals("compose*", SearchQuery.ftsExpression("compose foo\"bar"))
    }

    @Test
    fun LikePattern_wrapsWithWildcards() {
        assertEquals("%陪伴%", SearchQuery.likePattern("陪伴"))
    }

    @Test
    fun LikePattern_escapesSqlWildcards() {
        // 不转义的话 "100%" 会退化成"以 100 开头"，"a_b" 会匹配 "axb"
        assertEquals("%100\\%%", SearchQuery.likePattern("100%"))
        assertEquals("%a\\_b%", SearchQuery.likePattern("a_b"))
        assertEquals("%c\\\\d%", SearchQuery.likePattern("c\\d"))
    }

    @Test
    fun Terms_splitsOnSpace() {
        assertTrue(SearchQuery.terms("kotlin coroutines").contains("coroutines"))
        assertEquals(2, SearchQuery.terms("断联 陪伴").size)
    }
}
