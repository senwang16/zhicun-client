package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.fake.testAsset
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 附件分区（2026-10-24 重做）：
 * 1. 主行是**人类可读类型**，不再是裸 MIME（录音曾显示成 `audio/mp4` = "为什么是 MP4"）；
 * 2. 已保存的附件可点击，失败态不可点击（不能给一个点了没反应的按钮）；
 * 3. 点击只回调，打开动作在 Screen 层（FileProvider 在 Robolectric 下恒失败，见 M15 教训）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RecordDetailAssetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun AssetsBlock_audio_showsRecordingInsteadOfMime() {
        composeRule.setContent {
            AppTheme {
                AssetsBlock(assets = listOf(testAsset(mimeType = "audio/mp4", fileSize = 2_048L)))
            }
        }

        composeRule.onNodeWithText("录音 · 2.0 KB · 已保存").assertIsDisplayed()
        composeRule.onNodeWithText("audio/mp4").assertDoesNotExist()
        // 导入器的默认名（asset-1 / asset.m4a）不展示：录音下面再来个像文件类型的字样更迷惑
        composeRule.onNodeWithText("asset-1").assertDoesNotExist()
    }

    @Test
    fun AssetsBlock_pdf_showsPdfLabelAndRealFileName() {
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(
                        testAsset(id = "zhicun-test.pdf", mimeType = "application/pdf", fileSize = 597L),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("PDF · 597 B · 已保存").assertIsDisplayed()
        // 真实文件名来自 localPath，比 MIME 有用
        composeRule.onNodeWithText("zhicun-test.pdf").assertIsDisplayed()
    }

    @Test
    fun assetKindOf_mapsMimeToReadableKind() {
        assertEquals(AssetKind.IMAGE, assetKindOf("image/png"))
        assertEquals(AssetKind.PDF, assetKindOf("application/pdf"))
        assertEquals(AssetKind.PDF, assetKindOf("application/x-pdf"))
        assertEquals(AssetKind.AUDIO, assetKindOf("audio/mp4"))
        assertEquals(AssetKind.VIDEO, assetKindOf("video/mp4"))
        assertEquals(AssetKind.TEXT, assetKindOf("text/plain"))
        assertEquals(AssetKind.OTHER, assetKindOf("application/octet-stream"))
    }

    @Test
    fun AssetsBlock_savedAsset_clickInvokesCallback() {
        var opened: String? = null
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(testAsset(id = "a1")),
                    onOpenAsset = { opened = it.id },
                )
            }
        }

        composeRule.onAllNodesWithTag(RecordDetailTestTags.ASSET_ITEM).onFirst().performClick()

        assertEquals("a1", opened)
    }

    /** 分享收进行内 ⋮（2026-10-29）：低频动作不必常驻，但要能找到。 */
    @Test
    fun AssetsBlock_savedAsset_shareFromOverflowInvokesShareWithoutOpening() {
        var opened: String? = null
        var shared: String? = null
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(testAsset(id = "a1")),
                    onOpenAsset = { opened = it.id },
                    onShareAsset = { shared = it.id },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.assetMoreTag("a1")).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetShareTag("a1")).performClick()

        assertEquals("a1", shared)
        assertNull("分享按钮不能同时触发整行打开", opened)
    }

    /** 删除是破坏性动作：只在 ⋮ 里出现，点了只发起请求（确认框在页面壳）。 */
    @Test
    fun AssetsBlock_deleteFromOverflowInvokesCallback() {
        var deleted: String? = null
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(testAsset(id = "a1")),
                    onDeleteAsset = { deleted = it.id },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.assetMoreTag("a1")).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetDeleteTag("a1")).performClick()

        assertEquals("a1", deleted)
    }

    /** 不传 onDeleteAsset 的调用点（合集来源）连菜单项都不该有。 */
    @Test
    fun AssetsBlock_withoutDeleteCallback_hidesDeleteMenuItem() {
        composeRule.setContent {
            AppTheme {
                AssetsBlock(assets = listOf(testAsset(id = "a1")))
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.assetMoreTag("a1")).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetShareTag("a1")).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetDeleteTag("a1")).assertDoesNotExist()
    }

    /** 保存失败的附件打不开、分享不了，但删除入口必须在 —— 那正是用户想做的事。 */
    @Test
    fun AssetsBlock_failedAsset_stillHasDeleteEntry() {
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(testAsset(id = "a1", saveStatus = AssetSaveStatus.FAILED, fileSize = 0L)),
                    onDeleteAsset = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.assetOpenTag("a1")).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetMoreTag("a1")).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetShareTag("a1")).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.assetDeleteTag("a1")).assertIsDisplayed()
    }

    @Test
    fun AssetsBlock_failedAsset_isNotClickable() {
        var clicked = false
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(testAsset(saveStatus = AssetSaveStatus.FAILED, fileSize = 0L)),
                    onOpenAsset = { clicked = true },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.ASSET_ITEM).performClick()

        assertFalse("保存失败的附件点开也没用，不该触发回调", clicked)
        composeRule.onNodeWithText("图片 · 0 B · 保存失败").assertIsDisplayed()
    }

    @Test
    fun AssetsBlock_multipleAssets_rendersOneRowEach() {
        composeRule.setContent {
            AppTheme {
                AssetsBlock(
                    assets = listOf(
                        testAsset(id = "a1", mimeType = "image/png"),
                        testAsset(id = "a2", mimeType = "application/pdf"),
                    ),
                )
            }
        }

        assertEquals(2, composeRule.onAllNodesWithTag(RecordDetailTestTags.ASSET_ITEM).fetchSemanticsNodes().size)
        assertTrue(composeRule.onAllNodesWithTag(RecordDetailTestTags.ASSET_ITEM).fetchSemanticsNodes().isNotEmpty())
    }
}
