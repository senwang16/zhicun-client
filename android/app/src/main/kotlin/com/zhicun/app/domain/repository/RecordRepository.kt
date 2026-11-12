package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.CaptureSource
import com.zhicun.app.domain.model.ContentPart
import com.zhicun.app.domain.model.ProcessingStatus
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.model.SensitivityStatus
import kotlinx.coroutines.flow.Flow

/**
 * 记录仓储（AGENTS.md §2.3：domain 层只依赖接口，不感知 Room）。
 *
 * 返回类型一律是 domain model，Room Entity 不得越过 data 层。
 */
interface RecordRepository {

    /** 收件箱列表流：已软删除的记录不会出现在结果中，按收集时间倒序。 */
    fun observeAll(): Flow<List<Record>>

    /**
     * 收件箱流（M16）：未删除 **且未归档**，置顶优先，其余按收集时间倒序。
     *
     * 与 [observeAll] 分开而不是加参数：两者的使用方完全不同 ——
     * 收件箱只要"还在手上的"，导出与索引要"全部存过的"。一个带默认参数的过滤条件
     * 迟早会被某个调用方传错，而传错的后果是"归档后记录在导出包里消失"。
     */
    fun observeInbox(): Flow<List<Record>>

    /** 归档流（M16）：未删除且已归档，最近归档的在前。 */
    fun observeArchived(): Flow<List<Record>>

    /**
     * 归档箱内搜索（2026-10-24）。
     *
     * 归档记录退出了**主**搜索（FTS 索引与相关推荐都不含它们），但不给归档箱搜索
     * 就等于"几百条归档只能一屏屏翻"——归档退出检索的目的是"不打扰主搜索"，
     * 不是"永远找不回来"。
     *
     * @param normalized 已由 `SearchQuery.normalize` 归一化的查询串；**空串返回空流**，
     *   与主搜索同一纪律：空查询的语义是"不搜索"，绝不是"列出全部"
     * @param limit 结果上限
     */
    fun searchArchivedBySubstring(normalized: String, limit: Int): Flow<List<Record>>

    /** 按 id 取单条记录；**包含**已软删除的记录（详情页需要展示其状态）。 */
    suspend fun getById(id: String): Record?

    /**
     * 外发判闸用的记录视图（M20 合集）。
     *
     * 合集的敏感状态是成员并集，而 `records.sensitivity_status` 只是给列表徽标用的
     * 冗余快照 —— 它可能因为某条路径没来得及刷新而滞后。若闸门直接读这一列，
     * "成员的敏感标记刚升级、合集那一列还是旧的"就会出现一次真实上传。
     *
     * 因此合集在这里被替换为**未删除成员的实时并集**；其他类型原样返回。
     * 判据只在 `CollectionSensitivity` 里写一份，调度 / 重试 / 执行三层都调用本方法。
     */
    suspend fun gateRecord(record: Record): Record

    /**
     * 批量取（M10 搜索用）：一次查完，返回顺序**不保证**与入参一致。
     *
     * 已软删除与**已归档**的记录都不会出现在结果里 —— 检索结果不该包含用户删掉的、
     * 或者明确表达过"收起来"的东西（2026-10-24 裁决：归档退出搜索与相关推荐）。
     * 归档是[可逆]的，所以只影响"能不能被检索到"，数据本身仍在、仍进导出包。
     */
    suspend fun getByIds(ids: List<String>): List<Record>

    /**
     * 单条记录流（M7 起详情页使用）。
     *
     * 与 [getById] 的区别是"会变"：后台处理把 `processingStatus` 从 PENDING 改成 DONE 时，
     * 详情页必须跟着刷新，否则用户要退出再进来才看得到 OCR 结果。
     * 同样**不过滤**软删除记录 —— 详情页要能显示"已删除"横幅。
     */
    fun observeById(id: String): Flow<Record?>

    /** 新增记录。调用前必须已完成本地落盘（ARCHITECTURE §6.2）。 */
    suspend fun save(record: Record)

    /** 全量更新已有记录。 */
    suspend fun update(record: Record)

    /** 软删除：仅置标记，物理删除与附件清理由 M15 负责。 */
    suspend fun softDelete(id: String, updatedAt: Long)

    /**
     * 批量软删除（2026-10-24 删除功能）。
     *
     * 只置标记、不碰文件和派生内容：物理清理是 [com.zhicun.app.domain.usecase.CleanupDeletedRecordsUseCase]
     * 的职责（先删文件再删行），删除流程必须复用它而不是另写一份删表逻辑。
     *
     * @return 受影响行数
     */
    suspend fun softDeleteMany(ids: List<String>, updatedAt: Long): Int

    /** 更新 AI 处理状态（M6/M7/M11 使用）。 */
    suspend fun updateProcessingStatus(id: String, status: ProcessingStatus, updatedAt: Long)

    /**
     * 重算记录类型（2026-10-29 删除单个附件后剩余内容变化）。
     *
     * 只写 `type` 与 `updated_at`：调用方手里的记录是删除前的快照，
     * 整行回写会覆盖并发的敏感升级与处理状态。
     *
     * @return false = 记录不存在或已删除
     */
    suspend fun updateType(id: String, type: RecordType, updatedAt: Long): Boolean

    /**
     * 用户手动修改原文（2026-10-26 详情页编辑）。
     *
     * 递增 `recordRevision` 并作废三份云端/抓取确认（确认绑定内容版本），
     * 让"改过内容就要重新确认"成为数据层的结构性保证，不依赖调用方记得清。
     *
     * @param expectedRevision 调用方读到的版本号；版本已被推进时不写入（乐观并发）
     * @return false = 记录不存在、已删除，或版本已被其它写入推进
     */
    suspend fun updateRawText(
        id: String,
        rawText: String?,
        sensitivityStatus: SensitivityStatus,
        expectedRevision: Int,
        updatedAt: Long,
    ): Boolean

    /**
     * 记录"用户确认可以上传云端"（2026-10-23 复核 F-8）。
     *
     * 只写 `cloud_confirmed_revision` / `cloud_confirmed_scope` / `processing_status` /
     * `updated_at` 四列 —— **不能**用 [update] 整行回写：确认与派生正文的敏感升级
     * （`elevateSensitivity`）可能并发，整行覆盖会把刚升上去的 `suspected` 冲掉，
     * 等于替用户放行了本该拦下的内容。
     *
     * @param scope 这次确认覆盖的内容块（见 `Record.cloudConfirmedScope`）；空集写成 `NULL`
     * @return false = 记录在此期间被删除，调用方不应再排任务
     */
    suspend fun confirmCloudProcessing(
        id: String,
        revision: Int,
        scope: Set<ContentPart>,
        status: ProcessingStatus,
        updatedAt: Long,
    ): Boolean

    /**
     * 记录"用户允许抓取这个网页"（2026-10-23 复核 F-5）。
     *
     * 与 [confirmCloudProcessing] 分开两个方法而不是加一个布尔参数：两者的授权对象不同
     * （URL 出网 vs 正文上云），且抓取确认**不看**云端通路、**不**改处理状态。
     * 一个带默认值的 `confirm(cloud = true)` 迟早在某处传错，而传错的方向恰好是
     * "多授权了一次上传"，没有任何测试会红。
     *
     * @return false = 记录在此期间被删除
     */
    suspend fun confirmWebFetch(id: String, revision: Int, updatedAt: Long): Boolean

    /**
     * 按处理状态取记录 id（2026-10-23 审查 R-3）。
     *
     * 用于"云端开关重新打开后，把停在 `LOCAL_ONLY` 的记录找回来补投"：
     * 此前开关由关变开只写偏好，那些记录既不会重排任务，界面上也没有重算入口，
     * 属于单向门。只返回 id，完整记录与门禁判断由调用方做。
     */
    suspend fun idsByProcessingStatus(status: ProcessingStatus): List<String>

    /**
     * 查一条正文归一化后与 [text] 完全一致的未删除记录（2026-10-27，PRD v1.2 §2.4）。
     *
     * ## 跨来源
     * 不再限制 `captureSource`：分享、手动新建、悬浮录音里已经存过的同一段文字，
     * 再从剪贴板收录一次也算重复。判重只用于**剪贴板入口**——分享与手动新建是
     * 用户明确表达"我要再存一份"的动作，不该被拦下。
     *
     * ## 归一化边界
     * 仅统一换行符 + 去首尾空白（[com.zhicun.app.domain.clipboard.ClipboardText.dedupKey]）。
     * 实现内部会再归一化一次（幂等），调用方传原文也不会漏判；
     * URL 参数、中间空格一律不动；判据是 `=` 而不是 `LIKE`，前缀相同不算重复。
     *
     * 已软删除的记录不参与匹配：删掉就是不存在，再复制一次应当能重新收藏。
     *
     * @param text 待查正文（原文或已归一化均可）
     * @return 命中记录的 id；`null` = 没有重复
     */
    suspend fun findDuplicate(text: String): String?

    /**
     * 按任务表聚合出记录的处理状态（2026-10-23 QODER P2-1）。
     *
     * 记录的状态由**全部任务**的集合决定，单个任务无权覆盖。`cloudActive` 表示
     * "当前策略允许这条记录上云"（开关 + 网关配置 + 敏感确认共同决定，由调用方
     * 用 `CloudUploadGate` 算出）：它只影响"全部任务已完成"这一档的取值 ——
     * 云端可用 → DONE；不可用 → LOCAL_ONLY（"仅本地"不能因为本地任务跑完而消失）。
     */
    suspend fun recomputeProcessingStatus(id: String, cloudActive: Boolean, updatedAt: Long)

    /**
     * 敏感命中升级（2026-10-23 QODER P1-1/P2-6）。
     *
     * 派生正文（OCR / 转写 / 网页正文）复查命中后调用：升级为 `SUSPECTED` 并作废
     * 已有的云端确认。只动 `sensitivityStatus`、`cloudConfirmedRevision` 与 `updatedAt`，
     * **不覆盖整行其它字段**（调用时拿到的是任务开始时读取的陈旧记录）。
     */
    suspend fun elevateSensitivity(id: String, updatedAt: Long)

    /**
     * 收藏 / 取消收藏（M16）。
     *
     * 只动标记与 `updatedAt`，**不碰**任何内容字段：收藏是"用户怎么看这条记录"，
     * 不是对内容的修改，不该让 AI 派生结果变成过期状态。
     *
     * @return false = 记录不存在或已删除
     */
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long): Boolean

    /**
     * 批量收藏 / 取消收藏（2026-10-24：整理模式支持批量收藏）。
     *
     * 与 [setArchived] 同一形态：一次 `UPDATE ... IN (...)`，不逐条写。
     * 只动标记与 `updatedAt`，不碰内容字段（理由同 [setFavorite]）。
     *
     * @return 实际改动的条数（空列表为 0，不访问数据库）
     */
    suspend fun setFavorite(ids: List<String>, favorite: Boolean, updatedAt: Long): Int

    /** 置顶 / 取消置顶（M16）。@return false = 记录不存在或已删除 */
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Boolean

    /**
     * 批量归档 / 取消归档（M16）。
     *
     * @param archivedAt 归档时间；传 `null` 表示把记录移回收件箱
     * @return 实际改动的条数（传空列表时为 0，不访问数据库）
     */
    suspend fun setArchived(ids: List<String>, archivedAt: Long?, updatedAt: Long): Int
}
