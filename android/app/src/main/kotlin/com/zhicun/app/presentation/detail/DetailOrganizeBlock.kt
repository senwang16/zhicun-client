package com.zhicun.app.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhicun.app.R
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagSource

/**
 * 详情页的"整理"分区（M16）：收藏 / 置顶 + 用户标签增删。
 *
 * 单独成文件而不是塞进 `RecordDetailScreen.kt`：那个文件已经有 900 多行，
 * AGENTS.md §1 规定单文件不超过 400 行，再往里堆只会让后来的人不敢改。
 *
 * ## 为什么这两个开关没有做成开关（Switch）
 * Switch 表达的是"某个能力持续开着/关着"，而收藏与置顶是**一次性标记**：
 * 用户点一下就该看到结果已经生效（按钮文案/底色变化），而不是盯着一个滑块确认它弹过去了。
 *
 * ## 归档为什么挪去了顶栏（2026-10-29）
 * 归档不是"整理这条记录的内容"，而是把它移出收件箱 —— 和删除同属"整条记录的操作"。
 * 三处入口（详情页、收件箱、归档箱）里，详情页把它放进右上角 ⋮ 菜单，
 * 与删除记录并列；正文里的整理区只留下高频的收藏/置顶。
 */
@Composable
fun DetailOrganizeBlock(
    record: Record,
    tags: List<Tag>,
    tagInput: String,
    tagError: TagInputError?,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onTagInputChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 点整理区其它地方 = 放弃正在输入的标签（2026-10-24 用户反馈）：
            // 收起焦点，由输入框的失焦监听清空未提交内容。子级的点击（按钮/标签片）
            // 会先消费掉，不会走到这里；滑动手势不构成 tap，不影响滚动。
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 标题画在这里而不是由 DetailBody 的 SectionTitle 提供：槽位为空时
        // （其他调用方不关心整理区）就不会留下一个没有内容的小标题
        Text(
            text = stringResource(R.string.detail_section_organize),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleAction(
                label = stringResource(
                    if (record.isFavorite) R.string.detail_organize_favorited else R.string.detail_organize_favorite,
                ),
                active = record.isFavorite,
                onClick = onToggleFavorite,
                tag = DetailOrganizeTestTags.FAVORITE,
                modifier = Modifier.weight(1f),
            )
            ToggleAction(
                label = stringResource(
                    if (record.isPinned) R.string.detail_organize_pinned else R.string.detail_organize_pin,
                ),
                active = record.isPinned,
                onClick = onTogglePinned,
                tag = DetailOrganizeTestTags.PIN,
                modifier = Modifier.weight(1f),
            )
        }

        // 归档的影响要说清楚：用户最怕的是"点了归档，东西是不是没了"。
        // 只在已归档时显示，未归档时多一句话只是噪音。
        if (record.isArchived) {
            Text(
                text = stringResource(R.string.detail_organize_archived_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(DetailOrganizeTestTags.ARCHIVED_HINT),
            )
        }

        UserTagEditor(
            tags = tags,
            tagInput = tagInput,
            tagError = tagError,
            onTagInputChange = onTagInputChange,
            onAddTag = onAddTag,
            onRemoveTag = onRemoveTag,
        )
    }
}

/**
 * 用户标签区：可删的标签片 + 一个输入框。
 *
 * 只列出 [TagSource.USER] 的标签：AI 标签由模型产出、删了也会在下次处理时回来，
 * 把"能删"和"删了没用"混在一起会让用户觉得按钮坏了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserTagEditor(
    tags: List<Tag>,
    tagInput: String,
    tagError: TagInputError?,
    onTagInputChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    val userTags = tags.filter { it.source == TagSource.USER }
    val focusManager = LocalFocusManager.current

    if (userTags.isNotEmpty()) {
        // FlowRow（2026-10-24 用户反馈 U-7）：标签多时换行，不再挤成一排截断
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DetailOrganizeTestTags.USER_TAGS),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            userTags.forEach { tag ->
                RemovableTagChip(
                    name = tag.name,
                    onRemove = { onRemoveTag(tag.id) },
                    modifier = Modifier.testTag(DetailOrganizeTestTags.removeTagTag(tag.name)),
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = tagInput,
            onValueChange = onTagInputChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    // 失焦即取消（2026-10-24 用户反馈）：没提交的内容不该悄悄留着，
                    // 否则用户以为还在编辑，回头一看标签没加上。清空输入同时会
                    // 带走错误提示（ViewModel 里两者一起置空）。
                    if (!state.isFocused && tagInput.isNotEmpty()) onTagInputChange("")
                }
                .testTag(DetailOrganizeTestTags.TAG_INPUT),
            placeholder = { Text(text = stringResource(R.string.detail_organize_tag_placeholder)) },
            singleLine = true,
            isError = tagError != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // 回车与按钮行为一致；键盘不主动收起 —— 用户常常要连加两三个标签
            keyboardActions = KeyboardActions(onDone = { onAddTag() }),
            trailingIcon = {
                // 有未提交内容时给一个显式的取消入口（2026-10-24 用户反馈）：
                // 只靠“点别处”对不熟悉的人不够直觉。清空即取消，与搜索框同一文案。
                if (tagInput.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            onTagInputChange("")
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.testTag(DetailOrganizeTestTags.TAG_CANCEL),
                    ) {
                        Text(text = stringResource(R.string.search_clear))
                    }
                }
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = onAddTag,
            // 空白输入时禁用：点了没反应比按钮变灰更让人困惑
            enabled = tagInput.isNotBlank(),
            modifier = Modifier.testTag(DetailOrganizeTestTags.TAG_ADD),
        ) {
            Text(text = stringResource(R.string.detail_organize_tag_add))
        }
    }

    if (tagError != null) {
        Text(
            text = stringResource(
                when (tagError) {
                    TagInputError.TooLong -> R.string.detail_organize_tag_too_long
                    TagInputError.Duplicate -> R.string.detail_organize_tag_duplicate
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(DetailOrganizeTestTags.TAG_ERROR),
        )
    }
}

/** 带删除按钮的标签片。整片可点即删除，不做单独的 × 热区（手机上太小点不准）。 */
@Composable
private fun RemovableTagChip(
    name: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onRemove,
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            // 文案里带"×"是给用户看的删除暗示；无障碍会读成"标签名 ×"，语义仍清楚
            text = "$name  ×",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/** 三态按钮：未激活时是描边文字，激活后是实心主色。 */
@Composable
private fun ToggleAction(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(tag),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 测试用 tag。 */
object DetailOrganizeTestTags {
    const val FAVORITE = "detail_organize_favorite"
    const val PIN = "detail_organize_pin"
    const val ARCHIVED_HINT = "detail_organize_archived_hint"
    const val USER_TAGS = "detail_organize_user_tags"
    const val TAG_INPUT = "detail_organize_tag_input"
    const val TAG_ADD = "detail_organize_tag_add"
    const val TAG_CANCEL = "detail_organize_tag_cancel"
    const val TAG_ERROR = "detail_organize_tag_error"

    /** 每个标签片的删除热区按标签名区分，测试才能点到"删哪一个"。 */
    fun removeTagTag(name: String): String = "detail_organize_remove_$name"
}

/** 标签输入的错误提示（表现层类型：领域层的 `AddTagResult` 带成功分支，不该漏到 UI）。 */
enum class TagInputError {
    TooLong,
    Duplicate,
}
