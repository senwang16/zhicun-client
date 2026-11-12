package com.zhicun.app.service.share

import androidx.annotation.StringRes
import com.zhicun.app.R
import com.zhicun.app.domain.model.SaveRecordResult
import com.zhicun.app.domain.model.RecordInput

/**
 * 分享结果的反馈文案选择（纯逻辑，Input → 字符串资源 id）。
 *
 * 单独抽出来的理由：`Activity` 里直接写 when 分支的话，这段"用户到底看到哪句话"
 * 的逻辑只能靠真机点一遍验证；抽成纯函数后可以在单元测试里穷举所有组合。
 */
data class ShareFeedback(
    @param:StringRes val messageRes: Int,
    val args: List<Any> = emptyList(),
)

/**
 * 保存成功后的文案。
 *
 * 三种"部分成功"必须分开说：用户最关心的是"我的图和文件到底存住了没有"，
 * 一句笼统的"已保存"会让人误以为附件也在。
 */
fun savedFeedback(result: SaveRecordResult): ShareFeedback = when {
    result.failedAssetCount > 0 && result.skippedAssetCount > 0 -> ShareFeedback(
        R.string.share_saved_with_failed_and_skipped,
        listOf(result.failedAssetCount, result.skippedAssetCount),
    )

    result.failedAssetCount > 0 -> ShareFeedback(
        R.string.share_saved_with_failed_assets,
        listOf(result.failedAssetCount),
    )

    result.skippedAssetCount > 0 -> ShareFeedback(
        R.string.share_saved_with_skipped,
        listOf(result.skippedAssetCount),
    )

    result.savedAssetCount > 0 -> ShareFeedback(
        R.string.share_saved_with_assets,
        listOf(result.savedAssetCount),
    )

    else -> ShareFeedback(R.string.share_saved)
}

/** 没收到可保存内容时的文案：区分"什么都没有"和"类型不支持"。 */
fun emptyFeedback(payload: RecordInput): ShareFeedback =
    if (payload.unsupportedMimeTypes.isEmpty()) {
        ShareFeedback(R.string.share_nothing_received)
    } else {
        ShareFeedback(
            R.string.share_unsupported_type,
            listOf(payload.unsupportedMimeTypes.distinct().joinToString(", ")),
        )
    }
