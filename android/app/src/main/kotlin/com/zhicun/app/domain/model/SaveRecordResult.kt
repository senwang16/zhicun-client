package com.zhicun.app.domain.model

/**
 * 一次保存的结果，供 UI/接收页决定提示文案。
 *
 * 不用 `Boolean` 表示成功：分享场景天然是"部分成功"——记录存下了但某个附件复制失败，
 * 这时既不应当报"失败"（记录确实在库），也不能报"成功"（用户以为图片也存好了）。
 *
 * @param recordId 已创建记录的 id
 * @param savedAssetCount 成功复制的附件数
 * @param failedAssetCount 复制失败的附件数（记录仍保留，附件标注 `FAILED`）
 * @param skippedAssetCount 因类型不支持而未接收的附件数
 */
data class SaveRecordResult(
    val recordId: String,
    val savedAssetCount: Int,
    val failedAssetCount: Int,
    val skippedAssetCount: Int,
) {
    val hasFailures: Boolean get() = failedAssetCount > 0 || skippedAssetCount > 0
}
