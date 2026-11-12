package com.zhicun.app.service.share

import com.zhicun.app.R
import com.zhicun.app.domain.model.SaveRecordResult
import com.zhicun.app.domain.model.RecordInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分享反馈文案选择测试（纯 JVM）。
 *
 * 这些分支在真机上只能靠"手动分享 5 次"验证，抽成纯函数后可一次穷举。
 */
class ShareFeedbackTest {

    @Test
    fun SavedFeedback_textOnly_simpleSavedMessage() {
        val feedback = savedFeedback(SaveRecordResult("r", savedAssetCount = 0, failedAssetCount = 0, skippedAssetCount = 0))

        assertEquals(R.string.share_saved, feedback.messageRes)
        assertEquals(emptyList<Any>(), feedback.args)
    }

    @Test
    fun SavedFeedback_withAssets_mentionsCount() {
        val feedback = savedFeedback(SaveRecordResult("r", savedAssetCount = 3, failedAssetCount = 0, skippedAssetCount = 0))

        assertEquals(R.string.share_saved_with_assets, feedback.messageRes)
        assertEquals(listOf(3), feedback.args)
    }

    @Test
    fun SavedFeedback_failedAssets_takesPrecedenceOverSaved() {
        val feedback = savedFeedback(SaveRecordResult("r", savedAssetCount = 1, failedAssetCount = 2, skippedAssetCount = 0))

        assertEquals(R.string.share_saved_with_failed_assets, feedback.messageRes)
        assertEquals(listOf(2), feedback.args)
    }

    @Test
    fun SavedFeedback_skippedOnly_mentionsSkipped() {
        val feedback = savedFeedback(SaveRecordResult("r", savedAssetCount = 0, failedAssetCount = 0, skippedAssetCount = 1))

        assertEquals(R.string.share_saved_with_skipped, feedback.messageRes)
    }

    @Test
    fun SavedFeedback_failedAndSkipped_bothMentioned() {
        val feedback = savedFeedback(SaveRecordResult("r", savedAssetCount = 1, failedAssetCount = 1, skippedAssetCount = 2))

        assertEquals(R.string.share_saved_with_failed_and_skipped, feedback.messageRes)
        assertEquals(listOf(1, 2), feedback.args)
    }

    @Test
    fun EmptyFeedback_nothingAtAll() {
        val feedback = emptyFeedback(RecordInput())

        assertEquals(R.string.share_nothing_received, feedback.messageRes)
    }

    @Test
    fun EmptyFeedback_unsupportedTypes_listsDistinctMimes() {
        val feedback = emptyFeedback(
            RecordInput(unsupportedMimeTypes = listOf("application/zip", "application/zip", "video/mp4")),
        )

        assertEquals(R.string.share_unsupported_type, feedback.messageRes)
        assertEquals(listOf("application/zip, video/mp4"), feedback.args)
    }
}
