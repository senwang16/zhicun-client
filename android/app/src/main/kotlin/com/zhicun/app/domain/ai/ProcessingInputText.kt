package com.zhicun.app.domain.ai

import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record

/**
 * 一次外发的输入：正文 + **它由哪些内容块拼成**（2026-10-23 审查 R-2）。
 *
 * 两者必须一起产出，不能分两次算：敏感确认要校验"这次发的东西是不是用户看过的那批"，
 * 如果"拼正文"和"报清单"各写一套判断，迟早出现"发了 OCR 文本却只报了 rawText"
 * —— 而那正是这次要修的漏洞本身。
 */
data class ProcessingInput(
    val text: String,
    /** 实际用到的内容块；`text` 为空时为空集。 */
    val parts: Set<ContentPart>,
)

/**
 * 合集摘要的一个成员输入：原记录 + 它的派生内容。
 *
 * 把两者绑在一起传，是为了让"哪些成员参与"与"每个成员读到什么"来自同一份数据结构；
 * 分两个平行列表迟早会错位，而错位的后果是把 A 的正文算到 B 名下。
 */
data class CollectionMemberInput(
    val record: Record,
    val derived: List<DerivedContent>,
)

/**
 * "记录里所有可读的文字"的拼装规则（M11 摘要 / M12 向量共用）。
 *
 * 抽出来是因为两条链路都要回答同一个问题：**这条记录到底讲了什么**。
 * 各写一份的后果是"摘要读到了转写文本、向量却只读了原文"，检索与展示对不上。
 */
object ProcessingInputText {

    /** 会产生正文的派生内容，按可信度排序。 */
    private val BODY_TYPES = listOf(
        DerivedType.PAGE_TEXT,
        DerivedType.OCR_TEXT,
        DerivedType.TRANSCRIPTION,
    )

    /** 已经是"浓缩结论"的派生内容，向量化时优先用它们。 */
    private val DIGEST_TYPES = listOf(
        DerivedType.TITLE,
        DerivedType.SUMMARY,
        DerivedType.TAGS,
    )

    /**
     * 摘要的输入：原文 + 来源标题 + 正文类派生内容。
     *
     * 顺序是刻意的：`rawText` 是用户真正写下的东西，比 OCR/转写更可信；
     * 网页正文（`PAGE_TEXT`）排在 OCR 前面是因为它本身就是"整篇正文"，
     * 而 OCR 文本可能是截图里零散的几行。
     *
     * 多附件记录会有**多条同类型**派生行（一张图一条 OCR、一段录音一条转写）。
     * 这里收集同类型全部行、按仓储返回顺序（附件顺序）拼接，并加"第 N 张图片："
     * 之类的前缀，让模型知道这是几份不同的材料而不是一段连续文字。
     * 只有一段时不加前缀 —— 单文件记录的行为与多附件改造前完全一致，
     * 不给每条单图记录的摘要凭空多出一句"第 1 张图片"。
     */
    fun summaryInput(record: Record, derived: List<DerivedContent>): ProcessingInput {
        val parts = mutableSetOf<ContentPart>()
        val chunks = mutableListOf<String>()

        record.rawText?.trim()?.takeIf { it.isNotEmpty() }?.let {
            chunks += it
            parts += ContentPart.RAW_TEXT
        }
        record.sourceTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
            chunks += it
            parts += ContentPart.SOURCE_TITLE
        }

        BODY_TYPES.forEach { type ->
            val segments = derived
                .filter { it.contentType == type }
                .mapNotNull { it.content?.trim()?.takeIf(String::isNotEmpty) }
            if (segments.isEmpty()) return@forEach

            chunks += if (segments.size == 1) {
                segments.single()
            } else {
                segments.mapIndexed { index, text ->
                    val prefix = type.segmentPrefix(index + 1)
                    if (prefix == null) text else prefix + text
                }.joinToString(PARAGRAPH_SEPARATOR)
            }
            type.toContentPart()?.let { part -> parts += part }
        }

        return ProcessingInput(chunks.joinToString(PARAGRAPH_SEPARATOR).trim(), parts)
    }

    /**
     * 向量化的输入：**优先用已生成的标题/摘要/标签**，没有才退回正文。
     *
     * 道理很直接：向量要表达"这条记录讲什么"，而摘要就是模型对同一问题的回答，
     * 比几千字原文更集中，也省下大量输入 token。摘要此时还没生成（两条任务并行入队）
     * 就退回正文 —— 有向量远好过没向量，摘要出来后再点"重新处理"会重算。
     */
    fun embeddingInput(record: Record, derived: List<DerivedContent>): ProcessingInput =
        digestInput(record, derived) ?: summaryInput(record, derived)

    /**
     * 合集摘要的输入（M20）：合集说明 + 每个**未删除**成员的摘要输入。
     *
     * ## 为什么是"拼接"而不是另建一张摘要表
     * 合集本身仍是一条记录，摘要是它的 `derived_contents.SUMMARY`。这样全文检索、
     * 语义检索、详情页与导出都不需要为新实体写第二套读取路径。
     *
     * ## 公平预算
     * 总长仍受 [MAX_INPUT_CHARS] 约束；说明先占，剩下的平均分给成员。不平均分的话，
     * 前两条成员会吃掉全部预算，后面的成员等于没被总结 —— 用户会觉得"合集摘要只看了开头"。
     * 预算小到写不下任何字时不硬塞（避免把半句话当正文），只保留能读到的部分。
     *
     * 已删除成员在这里被排除：产品口径是"保留展示 + 重新生成时排除"。
     * 归档成员**参与**：归档只改变是否出现在收件箱，不改变内容。
     */
    fun collectionSummaryInput(
        collection: Record,
        descriptionDerived: List<DerivedContent>,
        members: List<CollectionMemberInput>,
    ): ProcessingInput {
        val own = summaryInput(collection, descriptionDerived)
        val ownText = own.text.take(MAX_INPUT_CHARS)
        val chunks = mutableListOf<String>()
        val parts = mutableSetOf<ContentPart>()
        if (ownText.isNotBlank()) {
            chunks += ownText
            parts += own.parts
        }

        val active = members.filterNot { it.record.isDeleted }
        if (active.isEmpty()) {
            return ProcessingInput(chunks.joinToString(PARAGRAPH_SEPARATOR).trim(), parts)
        }

        val budget = (MAX_INPUT_CHARS - ownText.length).coerceAtLeast(0)
        val perMember = budget / active.size
        if (perMember > 0) {
            active.forEach { member ->
                val input = summaryInput(member.record, member.derived)
                val text = input.text.take(perMember)
                if (text.isBlank()) return@forEach
                chunks += text
                parts += input.parts
            }
        }

        return ProcessingInput(chunks.joinToString(PARAGRAPH_SEPARATOR).trim(), parts)
    }

    /**
     * 合集向量化的输入：合集摘要已生成时优先用它，否则退回 [collectionSummaryInput]。
     *
     * 与单条记录同一策略：摘要还没跑完就先拿正文算一个向量，有向量远好过没向量；
     * 摘要出来后再点"重新生成"会重算。
     */
    fun collectionEmbeddingInput(
        collection: Record,
        descriptionDerived: List<DerivedContent>,
        members: List<CollectionMemberInput>,
    ): ProcessingInput =
        digestInput(collection, descriptionDerived)
            ?: collectionSummaryInput(collection, descriptionDerived, members)

    /**
     * 合集"此刻存在"的全部文字内容块（M20 确认范围）。
     *
     * 成员音频的 `ATTACHMENT` 刻意不算：合集摘要是把成员**文字**发去网关，
     * 不会二次上传音频字节。把它算进来会让"确认上传"这句话变成对用户撒谎。
     */
    fun collectionPresentParts(
        collection: Record,
        descriptionDerived: List<DerivedContent>,
        members: List<CollectionMemberInput>,
    ): Set<ContentPart> {
        val parts = presentParts(
            record = collection,
            derived = descriptionDerived,
            attachmentUploadable = false,
        ).toMutableSet()
        members.forEach { member ->
            parts += presentParts(
                record = member.record,
                derived = member.derived,
                attachmentUploadable = false,
            )
        }
        return parts
    }

    /** 标题/摘要/标签的优先输入；一个都没有时返回 null。 */
    private fun digestInput(record: Record, derived: List<DerivedContent>): ProcessingInput? {
        val parts = mutableSetOf<ContentPart>()
        val digest = DIGEST_TYPES.mapNotNull { type ->
            derived.firstOrNull { it.contentType == type }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.also { _ -> type.toContentPart()?.let { part -> parts += part } }
        }
        return if (digest.isEmpty()) {
            null
        } else {
            ProcessingInput(digest.joinToString("\n").trim(), parts)
        }
    }

    /** 只要正文（既有调用点的快捷方式，内容与 [summaryInput] 完全一致）。 */
    fun forSummary(record: Record, derived: List<DerivedContent>): String =
        summaryInput(record, derived).text

    /** 只要正文（既有调用点的快捷方式，内容与 [embeddingInput] 完全一致）。 */
    fun forEmbedding(record: Record, derived: List<DerivedContent>): String =
        embeddingInput(record, derived).text

    /**
     * 记录里**此刻存在**的全部内容块 —— 用户点"确认上传"时被记进
     * [com.zhicun.app.domain.model.Record.cloudConfirmedScope]（2026-10-23 R-2）。
     *
     * 刻意写成"已有两条外发路径的并集"，而不是另起一套 `if (rawText != null) ...`：
     * 确认范围和实际外发必须用同一份判断，否则会出现"记进去的块跟发出去的块不是一批"，
     * 而那正是 R-2 要修的漏洞（两个地方各记一次，迟早对不上）。
     *
     * @param attachmentUploadable 这条记录是否有**会被上传**的附件（目前只有语音音频）。
     *   图片字节从不出设备（OCR 在本地做），算进来只会把范围撑大、不改变安全性，
     *   但会让"已确认内容"这句话变得不准确，所以由调用方按实际类型判定。
     */
    fun presentParts(
        record: Record,
        derived: List<DerivedContent>,
        attachmentUploadable: Boolean,
    ): Set<ContentPart> {
        val parts = summaryInput(record, derived).parts + embeddingInput(record, derived).parts
        return if (attachmentUploadable) parts + ContentPart.ATTACHMENT else parts
    }

    /** 合集一次送入网关的文字上限（与 `SummarizeWorker.MAX_INPUT_CHARS` 同一量级）。 */
    const val MAX_INPUT_CHARS: Int = 4_000

    /** 成员之间用空行分隔，让模型能看出"这是一段新内容"。 */
    private const val PARAGRAPH_SEPARATOR = "\n\n"

    /** 派生类型 → 内容块。`EMBEDDING` 不是可读文字，不参与外发内容。 */
    private fun DerivedType.toContentPart(): ContentPart? = when (this) {
        DerivedType.PAGE_TEXT -> ContentPart.PAGE_TEXT
        DerivedType.OCR_TEXT -> ContentPart.OCR_TEXT
        DerivedType.TRANSCRIPTION -> ContentPart.TRANSCRIPTION
        // 网页标题 / 分享来源标题在界面上同一个位置展示，确认范围也按同一块算
        DerivedType.TITLE -> ContentPart.SOURCE_TITLE
        DerivedType.SUMMARY -> ContentPart.SUMMARY
        DerivedType.TAGS -> ContentPart.TAGS
        DerivedType.EMBEDDING -> null
        // 无法识别的库值已在仓储层被跳过（2026-11-10 Bug 4）；真走到这里说明
        // 有人绕过仓储直接塞了脏数据，按"不是可外发文字"处理，绝不外发。
        DerivedType.UNKNOWN -> null
    }

    /**
     * 多段同类正文的分段前缀（`index` 从 1 起）。
     *
     * 只有"这是第几份材料"对模型有意义时才加：同一段录音的多个分片、网页正文等
     * 加了前缀反而像噪声。
     */
    private fun DerivedType.segmentPrefix(index: Int): String? = when (this) {
        DerivedType.OCR_TEXT -> "第 $index 张图片："
        DerivedType.TRANSCRIPTION -> "第 $index 段音频："
        else -> null
    }
}
