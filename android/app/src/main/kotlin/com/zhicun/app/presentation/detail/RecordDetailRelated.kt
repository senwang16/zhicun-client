package com.zhicun.app.presentation.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.usecase.RelatedRecords
import com.zhicun.app.presentation.common.displayTitleOrFallback
import com.zhicun.app.presentation.common.formatTimestamp
import com.zhicun.app.presentation.common.recordTypeLabelRes
import kotlin.math.roundToInt

/** 详情页"相关记录"分区的测试 tag。 */
object RelatedRecordsTestTags {
    const val BLOCK = "detail_related_block"
    const val ITEM = "detail_related_item"
    const val SCORE = "detail_related_score"
}

/**
 * 详情页"相关记录"分区（M18，2026-10-24 重做展示）。
 *
 * 调用方只在 [RelatedRecords.isEmpty] 为 false 时渲染本块：没有向量（本地模式 /
 * 尚未处理完）或没有过相似度下限的记录时，"相关记录"四个字下面空着一排占位是最差的体验。
 *
 * ## 标题为什么走 [displayTitleOrFallback] 而不是裸 `rawText` 首行
 * 旧实现取 `rawText` 第一行，而语音的转写、图片的 OCR 都落在 `derived_contents`、
 * **不写 `raw_text`** —— 于是这两类记录的卡片标题是空白，整张卡片只剩一个时间戳，
 * 看起来像随机塞进来的。兜底标题（"10-24 00:32 录音"）与收件箱卡片用的是同一套规则
 * （`RecordFormatter` 的约定：列表与详情必须同一个判据）。
 *
 * ## 为什么显示分数
 * 只显示"相关"而没有任何量化的后果，是用户无法验证推荐是否合理 ——
 * 一旦混进一条噪声，整块的可信度就没了。百分比同时也是调阈值的观测手段。
 */
@Composable
internal fun RelatedRecordsBlock(
    related: RelatedRecords,
    onRecordClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RelatedRecordsTestTags.BLOCK),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            // 有被截断的候选才报总数：总数恰好等于展示条数时，"共 5 条"是一句废话
            text = if (related.total > related.items.size) {
                stringResource(R.string.detail_section_related_count, related.total)
            } else {
                stringResource(R.string.detail_section_related)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        related.items.forEach { item ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(RelatedRecordsTestTags.ITEM)
                    .clickable { onRecordClick(item.record.id) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.record.displayTitleOrFallback(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.detail_related_meta,
                                stringResource(recordTypeLabelRes(item.record.type)),
                                formatTimestamp(item.record.createdAt),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.detail_related_score,
                            (item.score * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .testTag(RelatedRecordsTestTags.SCORE),
                    )
                }
            }
        }
    }
}
