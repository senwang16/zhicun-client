package com.zhicun.app.presentation.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Asset
import com.zhicun.app.presentation.common.ExpandableText
import com.zhicun.app.presentation.common.displayTitle
import com.zhicun.app.presentation.common.formatTimestamp
import com.zhicun.app.presentation.common.recordTypeIconRes
import com.zhicun.app.presentation.common.recordTypeLabelRes
import com.zhicun.app.presentation.common.uiLocale

/* 合集详情页的分区（M20）：过期提示 + 来源清单。 */

/**
 * "来源内容有更新"横幅（M20）。
 *
 * 只在**已经生成过摘要且成员内容变了**时出现：刚创建的合集还在处理中，
 * 此时提示"重新生成"会让用户以为后台没开始干活。已删除来源不参与重新生成，
 * 文案里明说，避免用户以为点了会把删除的内容也带回来。
 */
@Composable
internal fun CollectionStaleBanner(
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RecordDetailTestTags.COLLECTION_STALE),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.detail_collection_stale_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.detail_collection_stale_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            when {
                canRegenerate -> TextButton(
                    onClick = onRegenerate,
                    modifier = Modifier.testTag(RecordDetailTestTags.COLLECTION_REGENERATE),
                ) {
                    Text(text = stringResource(R.string.detail_collection_regenerate))
                }

                // 云端被挡时重试是空动作（F-4 同一条纪律）：给出真正的出路
                onOpenSettings != null -> TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag(RecordDetailTestTags.COLLECTION_OPEN_SETTINGS),
                ) {
                    Text(text = stringResource(R.string.detail_open_cloud_settings))
                }
            }
        }
    }
}

/**
 * 来源清单（M20）：按创建时确定的顺序逐条展示成员原记录。
 *
 * 已删除 / 已归档来源**照常展示**并打标：合集"由哪些记录合并而来"是事实，
 * 成员被删掉不改变这条事实；是否参与重新生成由处理链路决定（已删除不参与）。
 * 整张卡片可点，跳回原记录看完整内容（合集页不复制成员的 OCR / 转写全文）。
 */
@Composable
internal fun CollectionMembersBlock(
    state: DetailCollectionState,
    onRecordClick: (String) -> Unit,
    onOpenAsset: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    onShareAsset: (Asset) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.members.forEach { member ->
            CollectionMemberCard(
                member = member,
                onOpen = { onRecordClick(member.record.id) },
                onOpenAsset = onOpenAsset,
                onShareAsset = onShareAsset,
            )
        }
    }
}

@Composable
private fun CollectionMemberCard(
    member: CollectionMemberView,
    onOpen: () -> Unit,
    onOpenAsset: (Asset) -> Unit,
    onShareAsset: (Asset) -> Unit,
) {
    val record = member.record
    val body = member.bodyText()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecordDetailTestTags.COLLECTION_MEMBER_PREFIX + record.id)
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painter = painterResource(recordTypeIconRes(record.type)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.displayTitle()
                            ?: stringResource(recordTypeLabelRes(record.type)),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.detail_collection_member_meta,
                            stringResource(recordTypeLabelRes(record.type)),
                            formatTimestamp(record.createdAt, uiLocale()),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    record.isDeleted -> MemberBadge(
                        text = stringResource(R.string.detail_collection_member_deleted),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    record.isArchived -> MemberBadge(
                        text = stringResource(R.string.detail_collection_member_archived),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (body != null) {
                ExpandableText(
                    text = body,
                    stateKey = "collection:${record.id}",
                    collapsedLines = 4,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    testTag = RecordDetailTestTags.COLLECTION_MEMBER_TEXT_PREFIX + record.id,
                )
            } else {
                Text(
                    text = stringResource(R.string.detail_collection_member_no_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (member.assets.isNotEmpty()) {
                AssetsBlock(assets = member.assets, onOpenAsset = onOpenAsset, onShareAsset = onShareAsset)
            }
            Text(
                text = stringResource(R.string.detail_collection_open_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MemberBadge(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
