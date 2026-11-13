package com.zhicun.app.presentation.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import com.zhicun.app.fake.testAsset
import com.zhicun.app.fake.testDerived
import com.zhicun.app.fake.testRecord
import com.zhicun.app.presentation.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 详情页界面测试：原文、来源、处理状态、附件状态是否都渲染出来，返回按钮是否回调。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp")
class RecordDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun RecordDetailContent_fullRecord_showsRawTextAndMetadata() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            rawText = "这是完整原文\n第二行也要显示",
                            sourceApp = "Chrome",
                            sourceUrl = "https://example.com/post",
                        ),
                        assets = listOf(testAsset(id = "a1", mimeType = "image/png", fileSize = 2048L)),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText("这是完整原文\n第二行也要显示").assertIsDisplayed()
        composeRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.com/post").assertIsDisplayed()
        // 附件区在原文下方（2026-10-24 从页面底部挪上来），类型名不再显示裸 MIME
        composeRule.onNodeWithText("图片 · 2.0 KB · 已保存").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_noAssets_hidesAssetsSection() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(record = testRecord(), assets = emptyList()),
                    onBack = {},
                )
            }
        }

        // 没有附件时整块不渲染："无附件"这行字对用户没有信息量
        composeRule.onNodeWithText("附件").assertDoesNotExist()
    }

    @Test
    fun RecordDetailContent_clickBack_invokesCallback() {
        var backed = false

        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(record = testRecord(), assets = emptyList()),
                    onBack = { backed = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("返回").performClick()

        assertTrue("返回按钮应触发 onBack", backed)
    }

    @Test
    fun RecordDetailContent_notFound_showsHint() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(uiState = RecordDetailUiState.NotFound, onBack = {})
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.NOT_FOUND).assertIsDisplayed()
        composeRule.onNodeWithText("记录不存在").assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_imageWithOcrText_showsRecognizedText() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = ProcessingStatus.DONE,
                        ),
                        assets = listOf(testAsset(recordId = "r1")),
                        derived = listOf(testDerived(recordId = "r1", content = "会议室 A · 周五 15:00")),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("识别文字").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("会议室 A · 周五 15:00").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_imageOcrFailed_showsRetryAndInvokesCallback() {
        var retried = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = ProcessingStatus.FAILED,
                        ),
                        assets = listOf(testAsset(recordId = "r1")),
                    ),
                    onBack = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("识别失败，原图仍可正常查看").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_RETRY).performScrollTo().performClick()

        assertTrue("重试按钮应触发 onRetry", retried)
    }

    @Test
    fun RecordDetailContent_imageStillProcessing_showsPendingHint() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = ProcessingStatus.PENDING,
                        ),
                        assets = listOf(testAsset(recordId = "r1")),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("正在识别图片中的文字…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_RETRY).assertDoesNotExist()
    }

    @Test
    fun RecordDetailContent_imageWithoutText_showsEmptyHintInsteadOfError() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = ProcessingStatus.DONE,
                        ),
                        assets = listOf(testAsset(recordId = "r1")),
                        derived = listOf(testDerived(recordId = "r1", content = "")),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("这张图里没有识别到文字").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.OCR_RETRY).assertDoesNotExist()
    }

    @Test
    fun RecordDetailContent_nonImageRecord_hidesOcrSection() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(record = testRecord(), assets = emptyList()),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("识别文字").assertDoesNotExist()
    }

    @Test
    fun RecordDetailContent_webLinkWithExtractedContent_showsTitleAndBody() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.WEB_LINK,
                            rawText = null,
                            sourceUrl = "https://example.com/post",
                            processingStatus = ProcessingStatus.DONE,
                        ),
                        assets = emptyList(),
                        derived = listOf(
                            testDerived(
                                id = "r1:TITLE",
                                recordId = "r1",
                                contentType = DerivedType.TITLE,
                                content = "网页标题",
                            ),
                            testDerived(
                                id = "r1:PAGE_TEXT",
                                recordId = "r1",
                                contentType = DerivedType.PAGE_TEXT,
                                content = "网页第一段正文",
                            ),
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("网页正文").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("网页标题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("网页第一段正文").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_webPartialExtraction_showsHintAboveText() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", type = RecordType.WEB_LINK, rawText = null),
                        assets = emptyList(),
                        derived = listOf(
                            testDerived(
                                id = "r1:PAGE_TEXT",
                                recordId = "r1",
                                contentType = DerivedType.PAGE_TEXT,
                                content = "请登录后查看全文",
                                errorMessage = "正文较少，可能需要登录或页面由脚本渲染",
                            ),
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("正文较少，可能需要登录或页面由脚本渲染")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("请登录后查看全文").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_webExtractFailed_showsHintAndRetry() {
        var retried = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.WEB_LINK,
                            rawText = null,
                            sourceUrl = "https://example.com/post",
                            processingStatus = ProcessingStatus.FAILED,
                        ),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("正文未完整提取，链接已保留").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.WEB_RETRY).performScrollTo().performClick()

        assertTrue("重新提取按钮应触发 onRetry", retried)
    }

    // ------------------------------------------------- 长文收起/展开（2026-10-24）

    /**
     * 长原文默认收起并给出"展开全文"（Play 商店 "Read more" 模式）。
     *
     * 收起与否由**布局是否溢出**决定，不数字符：所以短文本（其余用例的
     * "这是完整原文"）永远不该出现这个按钮。
     */
    @Test
    fun RecordDetailContent_longRawText_collapsedWithExpandToggle() {
        val longText = (1..40).joinToString("\n") { "第 $it 行的内容，这一行足够长以构成整段。" }
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = longText),
                        assets = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT + "_toggle")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("展开全文").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_longRawText_expandShowsCollapseToggle() {
        val longText = (1..40).joinToString("\n") { "第 $it 行的内容，这一行足够长以构成整段。" }
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = longText),
                        assets = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("展开全文").performScrollTo().performClick()

        composeRule.onNodeWithText("收起").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("展开全文").assertCountEquals(0)
    }

    /** 短原文不出现切换按钮：没溢出就没有"展开"，不该多一个没用的入口。 */
    @Test
    fun RecordDetailContent_shortRawText_hidesToggle() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = "短短的一句话"),
                        assets = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RecordDetailTestTags.RAW_TEXT + "_toggle")
            .assertCountEquals(0)
    }

    // ------------------------------------------------- 原文编辑 / 手动重处理 / 复制（2026-10-26）

    /** 原文的"编辑"入口只回调；编辑态由 ViewModel 的 StateFlow 驱动。 */
    @Test
    fun RecordDetailContent_rawTextEditButton_invokesStart() {
        var started = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = "原内容"),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    onStartRawTextEdit = { started = true },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_EDIT_START)
            .performScrollTo()
            .performClick()

        assertTrue("编辑按钮应触发 onStartRawTextEdit", started)
    }

    /** 编辑态渲染草稿，保存/取消分别回调；用户没点之前不会自行离开编辑。 */
    @Test
    fun RecordDetailContent_rawTextEditing_showsDraftAndInvokesCallbacks() {
        var saved = false
        var cancelled = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = "原内容"),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    contentEdit = DetailContentEditState(editing = true, draft = "改过的内容"),
                    onCancelRawTextEdit = { cancelled = true },
                    onSaveRawText = { saved = true },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_EDIT)
            .performScrollTo()
            // 输入框的语义文本还包含 supportingText（"保存后会重新生成…"），所以用包含
            .assertTextContains("改过的内容")
        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_SAVE).performClick()
        assertTrue("保存按钮应触发 onSaveRawText", saved)

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_CANCEL).performClick()
        assertTrue("取消按钮应触发 onCancelRawTextEdit", cancelled)
    }

    @Test
    fun RecordDetailContent_rawTextEditError_isShown() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = "原内容"),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    contentEdit = DetailContentEditState(
                        editing = true,
                        draft = "和别人的修改冲突了",
                        error = DetailContentEditError.Conflict,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_ERROR)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("内容刚被修改过，请取消后重试").assertIsDisplayed()
    }

    @Test
    fun RecordDetailContent_rawTextCopy_invokesCallbackWithText() {
        var copied: String? = null
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", rawText = "这段原文要被复制"),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    onCopyText = { copied = it },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.RAW_TEXT_COPY)
            .performScrollTo()
            .performClick()

        assertEquals("这段原文要被复制", copied)
    }

    /** 图片的 OCR 分区也各自有复制（2026-10-26 用户裁决：单独模块单独复制）。 */
    @Test
    fun RecordDetailContent_ocrCopy_invokesCallbackWithOcrText() {
        var copied: String? = null
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(
                            id = "r1",
                            type = RecordType.IMAGE,
                            rawText = null,
                            processingStatus = ProcessingStatus.DONE,
                        ),
                        assets = listOf(testAsset(recordId = "r1")),
                        derived = listOf(testDerived(recordId = "r1", content = "识别出来的文字")),
                    ),
                    onBack = {},
                    onCopyText = { copied = it },
                )
            }
        }

        composeRule.onNodeWithTag("${RecordDetailTestTags.OCR_TEXT}_copy")
            .performScrollTo()
            .performClick()

        assertEquals("识别出来的文字", copied)
    }

    /** 手动重新处理只给未完成的记录：卡住时有自救入口。 */
    @Test
    fun RecordDetailContent_unfinishedStatus_showsManualRetry() {
        var retried = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", processingStatus = ProcessingStatus.FAILED),
                        assets = emptyList(),
                    ),
                    onBack = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.MANUAL_RETRY)
            .performScrollTo()
            .performClick()

        assertTrue("手动重新处理应触发 onRetry", retried)
    }

    @Test
    fun RecordDetailContent_doneStatus_hidesManualRetry() {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", processingStatus = ProcessingStatus.DONE),
                        assets = emptyList(),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.MANUAL_RETRY).assertDoesNotExist()
    }

    /** 云端被挡时点重试不会发生任何事：换成设置入口，而不是留一个假按钮。 */
    @Test
    fun RecordDetailContent_manualRetryCloudBlocked_showsSettingsEntry() {
        var opened = false
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = testRecord(id = "r1", processingStatus = ProcessingStatus.LOCAL_ONLY),
                        assets = emptyList(),
                        canRetry = false,
                    ),
                    onBack = {},
                    onOpenSettings = { opened = true },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.MANUAL_RETRY).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordDetailTestTags.MANUAL_RETRY_SETTINGS)
            .performScrollTo()
            .performClick()

        assertTrue("应打开设置页", opened)
    }

    // ------------------------------------------------------------ M20：合集

    private fun member(recordId: String, position: Int) = CollectionMember(
        collectionId = "c1",
        recordId = recordId,
        position = position,
        addedAt = 1_000L,
        snapshotFingerprint = null,
    )

    @Test
    fun RecordDetailContent_collection_showsMembersAndOpensSource() {
        var opened: String? = null
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            rawText = "合集说明",
            processingStatus = ProcessingStatus.DONE,
        )

        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = collection,
                        assets = emptyList(),
                        collection = DetailCollectionState(
                            members = listOf(
                                CollectionMemberView(member("a", 0), testRecord(id = "a", rawText = "成员 A 的正文")),
                                CollectionMemberView(
                                    member("b", 1),
                                    testRecord(id = "b", rawText = "成员 B 的正文", isDeleted = true),
                                ),
                            ),
                        ),
                    ),
                    onBack = {},
                    onRecordClick = { opened = it },
                )
            }
        }

        // 已删除来源照常展示并打标：合集由哪些记录合并而来是事实，不因删除而消失
        composeRule.onNodeWithTag(RecordDetailTestTags.COLLECTION_MEMBER_PREFIX + "b")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("已删除").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("成员 A 的正文").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(RecordDetailTestTags.COLLECTION_MEMBER_PREFIX + "a").performClick()

        assertEquals("a", opened)
    }

    @Test
    fun RecordDetailContent_collectionStale_showsRegenerateAndInvokesRetry() {
        var retries = 0
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            rawText = "合集说明",
            processingStatus = ProcessingStatus.DONE,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )

        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = collection,
                        assets = emptyList(),
                        collection = DetailCollectionState(stale = true),
                        canRetry = true,
                    ),
                    onBack = {},
                    onRetry = { retries++ },
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.COLLECTION_STALE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordDetailTestTags.COLLECTION_REGENERATE)
            .performScrollTo()
            .performClick()

        assertEquals(1, retries)
    }

    /** 刚创建的合集还在处理中，即使指纹不一致也不该喊"重新生成"。 */
    @Test
    fun RecordDetailContent_collectionProcessing_hidesStaleBanner() {
        val collection = testRecord(
            id = "c1",
            type = RecordType.COLLECTION,
            rawText = "合集说明",
            processingStatus = ProcessingStatus.PENDING,
            sensitivityStatus = SensitivityStatus.CLEAR,
        )

        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(
                        record = collection,
                        assets = emptyList(),
                        collection = DetailCollectionState(stale = true),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RecordDetailTestTags.COLLECTION_STALE).assertDoesNotExist()
    }

    // ------------------------------------------------- 顶栏 ⋮（2026-10-29）

    private fun renderWithTopMenu(
        record: com.zhicun.app.domain.model.Record = testRecord(),
        onToggleArchived: () -> Unit = {},
        onRequestDeleteRecord: () -> Unit = {},
    ) {
        composeRule.setContent {
            AppTheme {
                RecordDetailContent(
                    uiState = RecordDetailUiState.Content(record = record, assets = emptyList()),
                    onBack = {},
                    onToggleArchived = onToggleArchived,
                    onRequestDeleteRecord = onRequestDeleteRecord,
                )
            }
        }
    }

    /** 归档从正文挪到顶栏：不常用，但要找得到。 */
    @Test
    fun RecordDetailContent_topMenuArchive_invokesCallback() {
        var toggles = 0
        renderWithTopMenu(onToggleArchived = { toggles++ })

        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MORE).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MENU_ARCHIVE).performClick()

        assertEquals(1, toggles)
    }

    /** 删除记录只发起请求：确认框在页面壳里，纯展示组件不自己弹。 */
    @Test
    fun RecordDetailContent_topMenuDelete_onlyRequestsConfirmation() {
        var requested = 0
        renderWithTopMenu(onRequestDeleteRecord = { requested++ })

        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MORE).performClick()
        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MENU_DELETE).performClick()

        assertEquals(1, requested)
        composeRule.onNodeWithTag(RecordDetailTestTags.RECORD_DELETE_CONFIRM).assertDoesNotExist()
    }

    /** 已归档的记录菜单里是"取消归档"：同一个位置表达当前状态的反向动作。 */
    @Test
    fun RecordDetailContent_archivedRecord_menuSaysUnarchive() {
        renderWithTopMenu(record = testRecord(archivedAt = 5_000L))

        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MORE).performClick()

        composeRule.onNodeWithText("取消归档").assertIsDisplayed()
    }

    /** 已删除（等待清理）的记录不给动作：归档/删除都没有意义。 */
    @Test
    fun RecordDetailContent_deletedRecord_hidesTopMenu() {
        renderWithTopMenu(record = testRecord(isDeleted = true))

        composeRule.onNodeWithTag(RecordDetailTestTags.TOP_MORE).assertDoesNotExist()
    }
}
