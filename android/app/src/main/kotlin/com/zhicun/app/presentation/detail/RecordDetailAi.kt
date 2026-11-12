package com.zhicun.app.presentation.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.zhicun.app.R
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.presentation.theme.AiBlockBorder
import com.zhicun.app.presentation.theme.RawTextStyle
import com.zhicun.app.presentation.theme.Teal50

/** 详情页最多展示的标签数（与卡片一致，PRD §6.3）。 */
private const val MAX_DETAIL_TAGS = 5

/** 标签编辑框接受的分隔符：顿号、中文/英文逗号（与占位提示一致）。 */
private val TAG_NAME_SEPARATORS = Regex("[,，、]")

/**
 * AI 派生结果块（M11）：短标题 + 摘要 + 标签；待确认时额外显示"未上传"横幅。
 *
 * ## 横幅与摘要不是二选一（2026-10-23 整改 F-3）
 * 最初把"待确认"整块替换成横幅，理由是"没请求过网关，摘要本来就不存在"。
 * 这话只在**首次**确认前成立：记录确认过、摘要已生成，之后又来了新内容（典型是转写完成），
 * 确认范围不够 → 横幅重新出现（R-2）→ 摘要被一起藏起来，用户看到的是"我的摘要没了"。
 * 正确语义是：横幅说明"有新内容还没确认"，摘要照旧展示。
 * 只有**确实没有摘要**时才不渲染软底卡 —— 那时说"正在生成摘要"才是误导。
 *
 * 用户确认过**当前内容版本与当前内容范围**后，上传闸门放行，这里恢复正常展示派生结果；
 * 敏感标记本身不洗白，信息区仍会显示"疑似敏感"。
 *
 * ## 云端根本不可用时不给确认按钮（2026-10-23 审查 R-3）
 * 开关关着或网关没配置时，"确认上传"点了也不会发生任何事（Worker 再次判闸即取消），
 * 而横幅会因 `cloudConfirmed` 变真而消失 —— 用户点了一次，界面说"好了"，实际什么都没跑。
 * 所以这种情况只说明原因、不给按钮；按钮出现即代表"点了真的会上传"。
 *
 * ## 标题为什么对网页记录不显示
 * 网页的 `TITLE` 是 M8 从页面里抽的真实标题，已经在"网页正文"分区展示过一次；
 * 这里再显示一遍是重复信息。
 */
@Composable
internal fun AiBlock(
    record: Record,
    derived: List<DerivedContent>,
    tags: List<Tag>,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** 云端为什么不可用（null = 可用）；决定"未上传"文案与确认按钮是否出现。 */
    cloudBlockReason: CloudUploadGate.Reason? = null,
    /**
     * 是否要显示"待确认"横幅（2026-10-23 审查 R-2）。
     *
     * 由 ViewModel 用 `Record.cloudConfirmedFor` 算出：它同时看版本号**和**确认范围，
     * 因此"确认过原文、之后才出现转写"这种情况也会重新弹出横幅。
     * 界面不自己写 `!record.cloudConfirmed` —— 那是版本级判据，会让这种情况再也点不了确认。
     */
    cloudConfirmPending: Boolean,
    /** 现在点"重新生成"会不会真的做事（2026-10-23 整改 F-4）。 */
    canRetry: Boolean = true,
    /**
     * 空摘要但记录确实有正文（2026-11-05 线上回归兜底）。
     *
     * 这种记录状态是 SUCCESS、`hasFailure` 为假，原本既不显示失败文案也不给重试入口，
     * 用户只能删掉重建。并进 [failed] 判据即可：文案从"没有可摘要的文字内容"
     * 变成"整理失败"，同时"重新生成"按钮出现。
     */
    canRetryEmptySummary: Boolean = false,
    /**
     * 网页正文尚未抓取、且这次确认会一并抓取（2026-10-24 用户反馈）。
     *
     * 文案必须说清"确认后除了上传，还会去抓这个网页" —— 一次点击同时授权两件事，
     * 用户才知道自己点下去会发生什么。
     */
    webFetchPending: Boolean = false,
    /** 点"去设置里打开云端 AI"入口；null = 不画这个按钮。 */
    onOpenSettings: (() -> Unit)? = null,
    /**
     * 标签行编辑保存（U-7，2026-10-24 用户反馈）：传入编辑框解析出的**全量**标签名。
     * 改名/增删统一走差量落库；AI 标签改名后由 ViewModel 转存为用户标签
     * （AI 标签会被下次摘要重算覆盖，用户标签才是持久的）。
     */
    onTagsEdited: (List<String>) -> Unit = {},
    /** 复制 AI 摘要（2026-10-26 用户裁决：每个模块各自复制）。 */
    onCopyText: ((String) -> Unit)? = null,
) {
    val summary = derived.firstOrNull { it.contentType == DerivedType.SUMMARY }
    val title = derived.firstOrNull { it.contentType == DerivedType.TITLE }?.content?.trim()
    // 云端通路本身不通时，确认是空动作：只说明原因，不给按钮
    val canConfirm = cloudBlockReason != CloudUploadGate.Reason.CLOUD_DISABLED &&
        cloudBlockReason != CloudUploadGate.Reason.NOT_CONFIGURED

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (cloudConfirmPending) {
            PendingBanner(
                canConfirm = canConfirm,
                onConfirm = onConfirm,
                hasExistingSummary = summary != null,
                fetchIncluded = webFetchPending,
                onOpenSettings = onOpenSettings,
            )
        }

        // 有摘要就照旧渲染（见 KDoc）：待确认 ≠ 没有内容
        if (!cloudConfirmPending || summary != null) {
            // 2026-10 改版：摘要与标签合进一张软底卡（浅色 Teal50 + 淡青边框，
            // 暗色回落 surfaceVariant），作为详情页第一区块（设计稿 02）
            val dark = isSystemInDarkTheme()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (dark) MaterialTheme.colorScheme.surfaceVariant else Teal50,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (dark) MaterialTheme.colorScheme.outlineVariant else AiBlockBorder,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column {
                    DerivedBlock(
                        heading = if (record.type == RecordType.WEB_LINK) null else title?.takeIf { it.isNotEmpty() },
                        text = summary?.content?.trim().orEmpty(),
                        partialHint = null,
                        failureText = stringResource(R.string.detail_ai_failed),
                        pendingText = stringResource(R.string.detail_ai_pending),
                        emptyText = stringResource(R.string.detail_ai_empty),
                        retryText = stringResource(R.string.detail_ai_retry),
                        retryTag = RecordDetailTestTags.AI_RETRY,
                        textTag = RecordDetailTestTags.AI_SUMMARY,
                        // PARTIAL（本地产出成功、云端失败）同样要显示失败与重试，否则这块永远"正在生成"
                        // 另外把"空摘要但有正文"也算进来（2026-11-05）：那种记录状态是成功，
                        // 单看 hasFailure 会让它既显示"没有可摘要的文字内容"又没有重试入口。
                        failed = record.processingStatus.hasFailure || canRetryEmptySummary,
                        hasResult = summary != null,
                        onRetry = onRetry,
                        blockedText = if (record.processingStatus == ProcessingStatus.LOCAL_ONLY) {
                            cloudBlockedText(cloudBlockReason)
                        } else {
                            null
                        },
                        containerColor = Color.Transparent,
                        canRetry = canRetry,
                        onOpenSettings = onOpenSettings,
                        onCopyText = onCopyText,
                    )

                    if (tags.isNotEmpty()) {
                        AiTagsRow(
                            tags = tags,
                            onTagsEdited = onTagsEdited,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        } else if (tags.isNotEmpty()) {
            // 待确认且没有摘要，但已有标签：标签独立成行，不跟着软底卡一起消失
            AiTagsRow(tags = tags, onTagsEdited = onTagsEdited)
        }
    }
}

/**
 * "本地已保存、未上传云端"横幅（2026-10-23 审查 R-2 / 整改 F-3）。
 *
 * 只在确有未确认内容时出现；[canConfirm] 为 false（云端关着 / 网关没配置）时
 * 只说明原因、不给按钮 —— 那种情况点了也不会发生任何事，按钮出现即代表"点了真的会上传"（R-3）。
 */
@Composable
private fun PendingBanner(
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** 下方是否还渲染着已生成的摘要：是则补一句说明，免得用户以为"摘要被藏了"。 */
    hasExistingSummary: Boolean = false,
    /** 云端不通时的出路：跳到设置。null = 不画按钮（只留文字说明）。 */
    onOpenSettings: (() -> Unit)? = null,
    /**
     * 这次确认是否同时放行网页抓取（2026-10-24 用户反馈）：
     * 决定文案是"只上传"还是"上传 + 抓取"。
     */
    fetchIncluded: Boolean = false,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RecordDetailTestTags.AI_SENSITIVE),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.detail_ai_sensitive_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (fetchIncluded) {
                        R.string.detail_ai_sensitive_hint_web
                    } else {
                        R.string.detail_ai_sensitive_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (hasExistingSummary) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.detail_ai_sensitive_has_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (canConfirm) {
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.testTag(RecordDetailTestTags.AI_CONFIRM),
                ) {
                    Text(
                        text = stringResource(
                            if (fetchIncluded) {
                                R.string.detail_ai_sensitive_confirm_web
                            } else {
                                R.string.detail_ai_sensitive_confirm
                            },
                        ),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.detail_ai_sensitive_cloud_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag(RecordDetailTestTags.AI_CONFIRM_UNAVAILABLE),
                )
                // 说明里既然让用户"到设置里打开后再回来"，就得给一条能点过去的路（F-4）
                if (onOpenSettings != null) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(RecordDetailTestTags.OPEN_SETTINGS_BANNER),
                    ) {
                        Text(text = stringResource(R.string.detail_open_cloud_settings))
                    }
                }
            }
        }
    }
}

/** AI 区块的标签行：用户标签排前面，胶囊 chip（卡片与详情共用此排序规则）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiTagsRow(
    tags: List<Tag>,
    onTagsEdited: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // 只有"曾经拿到过焦点"才允许失焦取消：进入编辑的首帧还没聚焦，
    // 此时若因为别的原因触发 onFocusChanged(false)，会把用户刚打开的编辑框关掉。
    var hadFocus by remember { mutableStateOf(false) }

    fun exitEditing() {
        editing = false
        focusManager.clearFocus()
    }

    if (editing) {
        // 返回键先退出编辑，而不是直接退出详情页（2026-10-26 用户反馈）
        BackHandler { exitEditing() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .testTag(RecordDetailTestTags.AI_TAGS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            // 点页面其它位置即放弃本次编辑（2026-10-26 用户反馈）。
                            // 保存按钮不是焦点目标，点它不会先触发这里。
                            exitEditing()
                        }
                    }
                    .testTag(RecordDetailTestTags.AI_TAGS_EDIT),
                placeholder = {
                    Text(
                        text = stringResource(R.string.detail_tags_edit_hint),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            DetailPillButton(
                label = stringResource(R.string.detail_tags_save),
                iconRes = R.drawable.ic_action_check,
                emphasized = true,
                onClick = {
                    editing = false
                    // 空输入视为取消（不是"清空全部标签"）——删标签走"整理"分区，那里有逐个删除
                    val names = draft.split(TAG_NAME_SEPARATORS)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (names.isNotEmpty()) onTagsEdited(names)
                },
                modifier = Modifier.testTag(RecordDetailTestTags.AI_TAGS_SAVE),
            )
            // 显式取消（2026-10-26 用户反馈）：失焦虽然能取消，但对不熟悉的人不够直观
            DetailPillButton(
                label = stringResource(R.string.detail_tags_cancel),
                iconRes = R.drawable.ic_close,
                onClick = { exitEditing() },
                modifier = Modifier.testTag(RecordDetailTestTags.AI_TAGS_CANCEL),
            )
        }
        return
    }

    // 换行（2026-10-24 用户反馈）：标签多时按 FlowRow 折行，不再挤成一排把末尾压扁
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RecordDetailTestTags.AI_TAGS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 用户标签排前面：自己打的标签比模型猜的更能代表分类意图
        val ordered = tags.sortedBy { if (it.source == TagSource.USER) 0 else 1 }
        ordered.take(MAX_DETAIL_TAGS).forEach { tag ->
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    // 点击任意标签进入编辑（U-7，2026-10-24 用户反馈）。
                    // 编辑的是**全部**标签（joinToString 不 take(5)）：只拿可见子集保存，
                    // 会把第 6 个起的标签悄悄删掉。
                    .clickable {
                        draft = ordered.joinToString("、") { it.name }
                        // 上一轮编辑留下的 hadFocus 不能沿用，否则新一轮首帧就可能被判定为失焦
                        hadFocus = false
                        editing = true
                    }
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
