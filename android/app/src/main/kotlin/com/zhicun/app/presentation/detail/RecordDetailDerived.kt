package com.zhicun.app.presentation.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.settings.CloudUploadGate
import com.zhicun.app.presentation.common.ExpandableText
import com.zhicun.app.presentation.theme.RawTextStyle


/**
 * 派生内容的通用展示块。
 *
 * 判定顺序（顺序很重要）：
 * 1. **有文字**：展示并允许选中复制 —— 派生内容和原文一样，用户存下来就是为了以后取用；
 * 2. **处理失败**：说明失败、原内容仍可查看，并给重试按钮；
 * 3. **按策略没做**（[blockedText] 非空：云端关闭 / 网关未配置 / 敏感未确认）：
 *    中性色说明原因，同样给重试按钮 —— 用户改完设置就能重跑，不该被困在原地；
 * 4. **处理过但没有内容**（无字的图、抓不到正文的页面）：说明结果为空，
 *    此时**不给**重试按钮 —— 重试多少次还是空，只会让人反复点击；
 * 5. **还在排队/处理中**：显示"正在…"，不转圈 —— 详情页首屏已有内容可看，
 *    为后台任务加转圈反而暗示"页面没加载好"。
 *
 * ## 为什么失败与"按策略没做"分开传
 * 两者的后果一样（这块内容没有），但性质相反：一个是出错了，一个是用户的选择。
 * 用同一个红字"处理失败"会把"你自己关掉了开关"说成"系统坏了"（2026-10-23 审查 R-5）。
 *
 * ## 重试按钮只在"点了真有用"时出现
 * 云端被挡时，纯云端任务的记录（文本/文件/语音）点重试什么都不会发生
 * （`RetryProcessingUseCase` 一个可跑任务都排不出来，直接返回 false）。
 * 这种按钮比没有按钮更糟：用户会反复点，从而以为"App 坏了"。
 * 因此 [canRetry] 为 false 时换成 [onOpenSettings] 的设置入口（2026-10-23 整改 F-4）。
 */
@Composable
internal fun DerivedBlock(
    heading: String?,
    text: String,
    partialHint: String?,
    failureText: String,
    pendingText: String,
    emptyText: String,
    retryText: String,
    retryTag: String,
    textTag: String,
    failed: Boolean,
    hasResult: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 按策略未生成时的说明（[failed] 为 true 时不生效：真失败优先说明）。 */
    blockedText: String? = null,
    /**
     * 按策略没做时是否仍给重试按钮。
     *
     * 绝大多数情况给（用户改完设置就能重跑）；但"敏感被拦"这一种给不了出路 ——
     * 重试会被同一道闸再拦一次，真正的出口是去确认上传，所以传 false，
     * 免得用户对着一个永远没反应的按钮点。
     */
    retryableWhenBlocked: Boolean = true,
    /**
     * 现在点重试会不会真的做事（2026-10-23 整改 F-4）。
     *
     * false = 这条记录的任务全是云端任务、而云端被挡着：点了 `RetryProcessingUseCase`
     * 会返回 false 且什么都不改，界面却像是"点了应该有反应"。这种按钮不给，
     * 换成 [onOpenSettings] 的设置入口。
     */
    canRetry: Boolean = true,
    /** 点"去设置里打开云端 AI"入口；null = 不给入口（没人接收就不该画按钮）。 */
    onOpenSettings: (() -> Unit)? = null,
    /**
     * 复制这一块的内容（2026-10-26 用户裁决：每个模块各自复制）。
     * `null` = 调用方没有剪贴板能力（预览/测试），不画按钮。
     */
    onCopyText: ((String) -> Unit)? = null,
    /** 容器底色（2026-10 改版）：AI 区块传软底/透明，其余调用点用默认白卡。 */
    containerColor: Color = MaterialTheme.colorScheme.surface,
    /** 容器边框（2026-10 改版）：仅 AI 软底卡使用。 */
    border: BorderStroke? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        border = border,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (!heading.isNullOrEmpty()) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            when {
                text.isNotEmpty() -> {
                    if (partialHint != null) {
                        Text(
                            text = partialHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // 转写/OCR 与原文共用收起/展开（2026-10-24 用户反馈）：
                    // 一小时的转写几万字，不收起的话附件区要滚很久才看得到
                    ExpandableText(
                        text = text,
                        stateKey = textTag,
                        style = RawTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        testTag = textTag,
                    )
                }

                failed -> Text(
                    text = failureText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                blockedText != null -> Text(
                    text = blockedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                hasResult -> Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Text(
                    text = pendingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 失败 / 按策略没做时始终给重试入口：即便上面展示的是上一次的旧结果，
            // 用户也应能重跑（2026-10-23 审查 R-1：PARTIAL 记录此前完全没有入口）
            val showRetry = canRetry && (failed || (blockedText != null && retryableWhenBlocked))
            val showSettings = !canRetry && onOpenSettings != null
            val showCopy = onCopyText != null && text.isNotEmpty()
            if (showCopy || showRetry || showSettings) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showCopy) {
                        CopyTextButton(
                            text = text,
                            onCopy = onCopyText,
                            // 由 textTag 派生：各分区已有唯一的 textTag，不必再维护一份复制 tag 表
                            testTag = "${textTag}_copy",
                        )
                    }
                    if (showRetry) {
                        DetailPillButton(
                            label = retryText,
                            iconRes = R.drawable.ic_action_refresh,
                            onClick = onRetry,
                            modifier = Modifier.testTag(retryTag),
                        )
                    } else if (showSettings) {
                        // 重试是空动作（云端被挡、本地没任务可跑）→ 给真正的出路（F-4）
                        DetailPillButton(
                            label = stringResource(R.string.detail_open_cloud_settings),
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag(RecordDetailTestTags.OPEN_SETTINGS),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "为什么没上传"的界面文案（2026-10-23 审查 R-5）。
 *
 * 三种原因都说成"疑似含敏感信息"，等于把"你自己关了开关"和"网关没配置"
 * 一起说成"你的内容有问题" —— 既吓人又失真。文案与 [CloudUploadGate.Reason] 一一对应，
 * 新增原因时编译器会在这里报错，不会悄悄退回成一句错话。
 */
@Composable
internal fun cloudBlockedText(reason: CloudUploadGate.Reason?): String = stringResource(
    when (reason) {
        CloudUploadGate.Reason.CLOUD_DISABLED -> R.string.detail_cloud_blocked_cloud_off
        CloudUploadGate.Reason.NOT_CONFIGURED -> R.string.detail_cloud_blocked_not_configured
        CloudUploadGate.Reason.SENSITIVE -> R.string.detail_cloud_blocked_sensitive
        // null = 调用方没算出门禁原因（例如旧状态由更早的版本写入）：说一句不含判断的中性话
        null -> R.string.detail_cloud_blocked_unknown
    },
)
