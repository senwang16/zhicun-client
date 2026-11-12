package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.fake.FakeTagRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 添加用户标签（M16）。
 *
 * 标签是**跨记录共享**的（`tags` 表上 `(name, source)` 唯一），所以这个用例的每条断言
 * 都在守同一件事：**同一个"名字"必须只对应一个标签行**。
 * 一旦规范化漏掉一环，数据库里就会出现 `"会议"` 与 `"会议 "` 两行，
 * 界面看起来是同一个标签，点哪个都筛不全 —— 这种问题用户很难描述，排查也很难。
 */
class AddUserTagUseCaseTest {

    private lateinit var tags: FakeTagRepository

    @Before
    fun setUp() {
        tags = FakeTagRepository()
    }

    private fun useCase() = AddUserTagUseCase(tags)

    private suspend fun namesOf(recordId: String): List<String> =
        tags.getByRecordId(recordId).map { it.name }

    // ------------------------------------------------------------ 正常路径

    @Test
    fun Add_validName_storesUserTag() = runBlocking {
        val result = useCase()("r1", "会议", now = 100L)

        assertEquals(AddTagResult.Added("会议"), result)
        val tag = tags.getByRecordId("r1").single()
        assertEquals("会议", tag.name)
        assertEquals("用户手打的标签必须标成 USER，否则会被 AI 重新处理覆盖", TagSource.USER, tag.source)
    }

    // ------------------------------------------------------------ 规范化

    @Test
    fun Add_surroundingWhitespace_isTrimmed() = runBlocking {
        val result = useCase()("r1", "  会议  ", now = 100L)

        assertEquals(AddTagResult.Added("会议"), result)
        assertEquals(listOf("会议"), namesOf("r1"))
    }

    /** 换行必须被压掉：标签是单行 chip，带换行会让列表高度跳变。 */
    @Test
    fun Add_innerWhitespaceCollapsesToOneSpace() = runBlocking {
        val result = useCase()("r1", "产品\t\n设计", now = 100L)

        assertEquals(AddTagResult.Added("产品 设计"), result)
        assertEquals(listOf("产品 设计"), namesOf("r1"))
    }

    @Test
    fun Add_onlyWhitespace_isRejected() = runBlocking {
        assertEquals(AddTagResult.EmptyName, useCase()("r1", "   \n\t ", now = 100L))
        assertTrue(namesOf("r1").isEmpty())
    }

    @Test
    fun Add_emptyString_isRejected() = runBlocking {
        assertEquals(AddTagResult.EmptyName, useCase()("r1", "", now = 100L))
    }

    // ------------------------------------------------------------ 长度

    @Test
    fun Add_nameAtLimit_isAccepted() = runBlocking {
        val name = "标".repeat(AddUserTagUseCase.MAX_NAME_LENGTH)

        assertEquals(AddTagResult.Added(name), useCase()("r1", name, now = 100L))
    }

    /** 超长拒绝而不是截断：悄悄砍掉用户打的内容比明确拒绝更让人困惑。 */
    @Test
    fun Add_nameOverLimit_isRejectedWithoutWriting() = runBlocking {
        val name = "标".repeat(AddUserTagUseCase.MAX_NAME_LENGTH + 1)

        assertEquals(AddTagResult.NameTooLong, useCase()("r1", name, now = 100L))
        assertTrue("被拒绝的名字不能留下半截标签", namesOf("r1").isEmpty())
    }

    /** 首尾空白不该把一个合规的名字挤成超长。 */
    @Test
    fun Add_paddingWhitespaceDoesNotCountTowardLimit() = runBlocking {
        val name = "标".repeat(AddUserTagUseCase.MAX_NAME_LENGTH)

        assertEquals(AddTagResult.Added(name), useCase()("r1", "   $name   ", now = 100L))
    }

    // ------------------------------------------------------------ 重复

    @Test
    fun Add_sameNameTwice_reportsDuplicate() = runBlocking {
        useCase()("r1", "会议", now = 100L)

        assertEquals(AddTagResult.Duplicate, useCase()("r1", "会议", now = 200L))
        assertEquals("不能出现两个'会议'", 1, namesOf("r1").size)
    }

    /** 只在空白上不同的"同一个标签"也必须算重复。 */
    @Test
    fun Add_sameNameWithDifferentWhitespace_reportsDuplicate() = runBlocking {
        useCase()("r1", "会议", now = 100L)

        assertEquals(AddTagResult.Duplicate, useCase()("r1", "  会议\n", now = 200L))
        assertEquals(1, namesOf("r1").size)
    }

    /** 同名标签挂到另一条记录上是正常操作，不算重复。 */
    @Test
    fun Add_sameNameOnAnotherRecord_isAllowed() = runBlocking {
        useCase()("r1", "会议", now = 100L)

        assertEquals(AddTagResult.Added("会议"), useCase()("r2", "会议", now = 200L))
        assertEquals(listOf("会议"), namesOf("r2"))
    }

    // ------------------------------------------------------------ 规范化本身

    @Test
    fun Normalize_handlesCommonInputs() {
        assertEquals("会议", AddUserTagUseCase.normalize(" 会议 "))
        assertEquals("产品 设计", AddUserTagUseCase.normalize("产品  设计"))
        assertEquals("", AddUserTagUseCase.normalize("   "))
    }

    /**
     * 全角空格与不换行空格必须当空白处理。
     *
     * 这两种字符中文用户天天遇到（中文输入法、从 Word 或网页复制），而 `String.trim()`
     * 与 `\s` 都**不认**它们 —— 不处理的话，"　会议"与"会议"会成为两个标签，
     * 界面上看起来一模一样，点哪个都筛不全。
     */
    @Test
    fun Normalize_treatsFullWidthAndNonBreakingSpaceAsWhitespace() {
        assertEquals("会议", AddUserTagUseCase.normalize("\u3000会议\u3000"))
        assertEquals("会议", AddUserTagUseCase.normalize("\u00A0会议"))
        assertEquals("产品 设计", AddUserTagUseCase.normalize("产品\u3000设计"))
    }

    @Test
    fun Add_fullWidthSpacePaddedName_isDeduplicated() = runBlocking {
        useCase()("r1", "会议", now = 100L)

        assertEquals(AddTagResult.Duplicate, useCase()("r1", "\u3000会议\u3000", now = 200L))
        assertEquals(1, namesOf("r1").size)
    }
}
