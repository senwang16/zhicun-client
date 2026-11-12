package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.Tag
import com.zhicun.app.domain.model.TagUsage
import kotlinx.coroutines.flow.Flow

/** 一条记录与它名下的某个标签（平铺形式，供列表页一次性取回）。 */
data class RecordTag(
    val recordId: String,
    val tag: Tag,
)

/**
 * 标签仓储（M11）。
 *
 * 核心约束（PRD §6.3 / M11 验收）：**AI 重新处理只能覆盖 AI 标签，不得动用户标签**。
 * 这条约束落在 [replaceAiTags] 的实现里 —— 先按 `source = AI` 解除关联，再按新结果挂上，
 * 用户标签的关联一行都不碰。
 */
interface TagRepository {

    /** 某条记录的全部标签（AI + 用户），按创建时间升序。 */
    suspend fun getByRecordId(recordId: String): List<Tag>

    /**
     * 用新的 AI 标签替换旧的 AI 标签。
     *
     * @param names 已经去重、截断过的标签名（上限由调用方决定，Worker 里是 5）
     */
    suspend fun replaceAiTags(recordId: String, names: List<String>, now: Long = System.currentTimeMillis())

    /** 全量"记录-标签"流，供收件箱一次性拿到所有卡片要显示的标签（避免 N+1 查询）。 */
    fun observeAllWithRecord(): Flow<List<RecordTag>>

    /**
     * 标签使用概览（M21 搜索页）：按使用记录数降序，**只统计未删除、未归档的记录**，
     * 同名 AI/用户标签合并为一条。
     */
    fun observeTagUsage(limit: Int): Flow<List<TagUsage>>

    /**
     * 按记录 id 批量取标签（M21 决策 3）。
     *
     * 用于结果页的"结果标签"：一次取回整批结果的标签，调用方在内存里按名字聚合，
     * 避免每条记录各查一次。
     */
    suspend fun getForRecords(recordIds: List<String>): List<RecordTag>

    /**
     * 给记录挂一个用户标签（M16）。
     *
     * 同名同源标签复用同一行（`tags` 表上 `(name, source)` 唯一），与 AI 标签共用一套复用规则。
     * 名字的规范化由 `AddUserTagUseCase` 负责，这里收到的应当已经是干净的名字。
     *
     * @return false = 名字为空白，或这条记录已经挂过同名标签（界面要说"已经有了"）
     */
    suspend fun addUserTag(recordId: String, name: String, now: Long = System.currentTimeMillis()): Boolean

    /**
     * 解除记录与某个标签的关联（M16）。
     *
     * **只删关联，不删标签行**：同名标签可能还被别的记录挂着。
     * 不按 `source` 限制 —— 用户不该被"这是 AI 加的，你不能删"挡住；
     * 代价是重新跑 AI 处理时它可能又回来（见 M16 已知限制）。
     */
    suspend fun removeTag(recordId: String, tagId: String)

    /**
     * 摘掉记录上的全部 AI 标签（2026-10-26 原文编辑后使其失效）。
     *
     * 只清 AI 关联，用户标签一行不碰 —— 与 [replaceAiTags] 同一条硬约束。
     * 原文变了之后旧的 AI 标签已经不代表内容，先摘掉比让它们挂到重新生成完成更诚实。
     */
    suspend fun removeAiTags(recordId: String)
}
