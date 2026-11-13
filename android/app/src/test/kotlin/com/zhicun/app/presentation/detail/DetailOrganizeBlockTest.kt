package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 详情页"整理"分区测试（M16）。
 *
 * 这一块是独立文件（`DetailOrganizeBlock.kt`），所以测试也单独一个：
 * `RecordDetailScreenTest` 已经很长，再塞进来只会让失败信息更难定位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class DetailOrganizeBlockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tag(name: String, source: TagSource = TagSource.USER) = Tag(
        id = "tag-$name",
        name = name,
        source = source,
        confidence = if (source == TagSource.AI) 0.9 else null,
        createdAt = 1_000L,
    )

    private fun setContent(
        record: com.zhicun.app.domain.model.Record = testRecord(id = "r1"),
        tags: List<Tag> = emptyList(),
        tagInput: String = "",
        tagError: TagInputError? = null,
        onToggleFavorite: () -> Unit = {},
        onTogglePinned: () -> Unit = {},
        onTagInputChange: (String) -> Unit = {},
        onAddTag: () -> Unit = {},
        onRemoveTag: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme {
                DetailOrganizeBlock(
                    record = record,
                    tags = tags,
                    tagInput = tagInput,
                    tagError = tagError,
                    onToggleFavorite = onToggleFavorite,
                    onTogglePinned = onTogglePinned,
                    onTagInputChange = onTagInputChange,
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                )
            }
        }
    }

    @Test
    fun DetailOrganizeBlock_inactiveRecord_showsPlainLabels() {
        setContent()

        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        composeRule.onNodeWithText("置顶").assertIsDisplayed()
        // 归档移到了顶栏 ⋮（2026-10-29）：它不是"整理内容"，而是把记录移出收件箱
        composeRule.onAllNodesWithText("归档").assertCountEquals(0)
    }

    @Test
    fun DetailOrganizeBlock_favoriteAndPinned_showsActiveLabels() {
        setContent(record = testRecord(id = "r1", isFavorite = true, isPinned = true))

        composeRule.onNodeWithText("已收藏").assertIsDisplayed()
        composeRule.onNodeWithText("已置顶").assertIsDisplayed()
    }

    /** 归档入口挪走后，正文里仍要解释"东西没丢"（不然用户会以为记录被删了）。 */
    @Test
    fun DetailOrganizeBlock_archived_showsHintWithoutArchiveButton() {
        setContent(record = testRecord(id = "r1", archivedAt = 5_000L))

        composeRule.onNodeWithTag(DetailOrganizeTestTags.ARCHIVED_HINT).assertIsDisplayed()
        composeRule.onAllNodesWithText("取消归档").assertCountEquals(0)
    }

    @Test
    fun DetailOrganizeBlock_notArchived_hidesArchivedHint() {
        setContent()

        composeRule.onAllNodesWithTag(DetailOrganizeTestTags.ARCHIVED_HINT).assertCountEquals(0)
    }

    @Test
    fun DetailOrganizeBlock_clickFavorite_invokesCallback() {
        var toggles = 0
        setContent(onToggleFavorite = { toggles++ })

        composeRule.onNodeWithTag(DetailOrganizeTestTags.FAVORITE).performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun DetailOrganizeBlock_clickPin_invokesCallback() {
        var toggles = 0
        setContent(onTogglePinned = { toggles++ })

        composeRule.onNodeWithTag(DetailOrganizeTestTags.PIN).performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun DetailOrganizeBlock_userTagsAreRemovable() {
        var removed: String? = null
        setContent(
            tags = listOf(tag("工作"), tag("待办")),
            onRemoveTag = { removed = it },
        )

        composeRule.onNodeWithTag(DetailOrganizeTestTags.removeTagTag("工作")).performClick()

        assertEquals("tag-工作", removed)
    }

    /**
     * AI 标签不提供删除入口。
     *
     * 模型下次处理时会把它们再写回来，让用户点一个"删了又回来"的按钮，
     * 比不提供按钮更让人困惑。
     */
    @Test
    fun DetailOrganizeBlock_aiTagsAreNotShownInEditor() {
        setContent(tags = listOf(tag("工作", TagSource.AI), tag("手写", TagSource.USER)))

        composeRule.onNodeWithTag(DetailOrganizeTestTags.removeTagTag("手写")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(DetailOrganizeTestTags.removeTagTag("工作"))
            .assertCountEquals(0)
    }

    @Test
    fun DetailOrganizeBlock_typingTag_reportsChange() {
        var typed: String? = null
        setContent(onTagInputChange = { typed = it })

        composeRule.onNodeWithTag(DetailOrganizeTestTags.TAG_INPUT).performTextReplacement("读书清单")

        assertEquals("读书清单", typed)
    }

    @Test
    fun DetailOrganizeBlock_blankInput_disablesAddButton() {
        setContent(tagInput = "   ")

        composeRule.onNodeWithTag(DetailOrganizeTestTags.TAG_ADD).assertIsNotEnabled()
    }

    @Test
    fun DetailOrganizeBlock_nonBlankInput_enablesAddButton() {
        var adds = 0
        setContent(tagInput = "读书清单", onAddTag = { adds++ })

        composeRule.onNodeWithTag(DetailOrganizeTestTags.TAG_ADD).performClick()

        assertEquals(1, adds)
    }

    @Test
    fun DetailOrganizeBlock_tooLongError_showsMessage() {
        setContent(tagError = TagInputError.TooLong)

        composeRule.onNodeWithTag(DetailOrganizeTestTags.TAG_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("标签最多 20 个字").assertIsDisplayed()
    }

    @Test
    fun DetailOrganizeBlock_duplicateError_showsMessage() {
        setContent(tagError = TagInputError.Duplicate)

        composeRule.onNodeWithText("这条记录已经有这个标签了").assertIsDisplayed()
    }

    /**
     * 输入未提交时有显式的取消入口（2026-10-24 用户反馈）：点了"清空"，
     * 输入被清掉（= 放弃），不应有任何标签被加上。
     */
    @Test
    fun DetailOrganizeBlock_tagInputPresent_showsCancelButtonThatClearsInput() {
        var latestInput: String? = null
        var adds = 0
        setContent(
            tagInput = "读书清单",
            onTagInputChange = { latestInput = it },
            onAddTag = { adds++ },
        )

        composeRule.onNodeWithTag(DetailOrganizeTestTags.TAG_CANCEL)
            .assertIsDisplayed()
            .performClick()

        assertEquals("清空即取消：输入应被清掉", "", latestInput)
        assertEquals("取消不该触发添加", 0, adds)
    }

    /** 输入为空时没有取消按钮：没东西可取消，多一个按钮只是噪音。 */
    @Test
    fun DetailOrganizeBlock_emptyTagInput_hidesCancelButton() {
        setContent(tagInput = "")

        composeRule.onAllNodesWithTag(DetailOrganizeTestTags.TAG_CANCEL).assertCountEquals(0)
    }
}
