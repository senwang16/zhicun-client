package com.zhicun.app.domain.usecase

import com.zhicun.app.domain.repository.TagRepository
import javax.inject.Inject

/** 添加用户标签的结果（M16）。界面据此决定"什么都不说"还是"告诉用户为什么没加上"。 */
sealed interface AddTagResult {

    /** @param name 规范化之后真正存进去的名字（可能与用户输入的空白不同） */
    data class Added(val name: String) : AddTagResult

    /** 只有空白字符：不算错误，但也确实什么都没加。 */
    data object EmptyName : AddTagResult

    /** 超长。不截断：悄悄改掉用户打的东西比拒绝更让人困惑。 */
    data object NameTooLong : AddTagResult

    /** 这条记录已经挂过同名标签。 */
    data object Duplicate : AddTagResult
}

/**
 * 给记录添加一个用户标签（M16）。
 *
 * ## 为什么规范化必须在用例里，而不是 UI 里顺手 trim
 * 标签是**跨记录共享**的东西：`tags` 表上 `(name, source)` 唯一。如果一次存成 `"会议 "`、
 * 另一次存成 `"会议"`，数据库里就会有两行，界面看起来是同一个标签却点哪个都筛不全。
 * 放一处、测一处，才不会出现"某个入口忘了 trim"。
 *
 * ## 为什么超长直接拒绝而不是截断
 * 截断的结果是用户打了一段话，界面上出现一个被砍掉一半的词 —— 他不知道发生了什么，
 * 也不知道自己该改什么。拒绝 + 提示长度上限，是更诚实的失败。
 */
class AddUserTagUseCase @Inject constructor(
    private val tagRepository: TagRepository,
) {

    suspend operator fun invoke(
        recordId: String,
        rawName: String,
        now: Long = System.currentTimeMillis(),
    ): AddTagResult {
        val name = normalize(rawName)
        if (name.isEmpty()) return AddTagResult.EmptyName
        if (name.length > MAX_NAME_LENGTH) return AddTagResult.NameTooLong

        return if (tagRepository.addUserTag(recordId, name, now)) {
            AddTagResult.Added(name)
        } else {
            AddTagResult.Duplicate
        }
    }

    companion object {
        /**
         * 单个标签的长度上限。
         *
         * 标签是"扫一眼就能分类"的东西，超过这个长度已经不是标签而是摘要了；
         * 同时它也约束了 chips 在窄屏上的换行行为（411dp 上大约放得下 8 个中文字）。
         */
        const val MAX_NAME_LENGTH: Int = 20

        /**
         * 把空白压成一个空格，再去首尾空白。
         *
         * 顺序不能反：`String.trim()` 用的是 `Char.isWhitespace()`，它**不认**
         * 全角空格 `U+3000` 与不换行空格 `U+00A0`（中文输入法、从 Word/网页复制都常带）。
         * 先统一压成半角空格再 trim，才能保证"　会议　"与"会议"进数据库时是同一个名字。
         *
         * 换行也算空白：标签在界面上是单行 chip，带换行会让列表高度跳变；
         * 压成空格至少不丢信息（用户仍能看出他原本打了两个词）。
         */
        fun normalize(raw: String): String = raw.replace(WHITESPACE, " ").trim()

        /**
         * `\s` 只覆盖 ASCII 空白。全角空格与 NBSP 必须显式列出，
         * 否则中文场景下"看起来一样的标签"会存成两行。
         */
        private val WHITESPACE = Regex("[\\s\\u00A0\\u3000]+")
    }
}
