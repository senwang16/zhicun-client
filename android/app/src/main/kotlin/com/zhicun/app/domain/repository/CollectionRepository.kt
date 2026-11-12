package com.zhicun.app.domain.repository

import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.CollectionMemberWithRecord
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.SensitivityStatus

/**
 * 合集成员关系仓储（M20）。
 *
 * 合集本身仍是一条普通 [Record]（`RecordType.COLLECTION`），因此记录本身的读写继续走
 * [RecordRepository]；这里只负责"谁在合集里"以及由成员变化引起的合集状态刷新。
 *
 * 查询成员时**不过滤已软删除的记录**：产品口径是"已删除来源保留内容 + 打标记 +
 * 重新生成时排除"，过滤掉它们会让合集悄悄少几条来源。是否参与生成由调用方按
 * `record.isDeleted` 判断。
 */
interface CollectionRepository {

    /**
     * 同一事务写入合集记录与成员关系（M20）。
     *
     * 分开写会出现"合集已存在、成员还没写"的中间态：此时详情页是一张空合集，
     * 而处理任务可能已经开始跑，摘要读到的成员集合是空的。
     *
     * @param memberRecordIds 已按阅读顺序排好；调用方负责数量下限与类型校验
     */
    suspend fun create(collection: Record, memberRecordIds: List<String>, now: Long)

    /** 成员关系，按 `position` 升序。 */
    suspend fun getMembers(collectionId: String): List<CollectionMember>

    /**
     * 成员关系 + 当前原记录，按 `position` 升序。
     *
     * 物理清理会级联删除关系行，因此正常情况下不会缺记录；若读到缺失（极端并发），
     * 该成员会被跳过而不是返回一个空壳。
     */
    suspend fun getMembersWithRecords(collectionId: String): List<CollectionMemberWithRecord>

    /** 反查某条记录属于哪些合集（成员内容/敏感状态变化后刷新用）。 */
    suspend fun getMemberships(recordId: String): List<CollectionMember>

    /**
     * 生成成功后把**所有成员**（含已删除）的快照指纹更新为当前值。
     *
     * 已删除是事实标记，会一直显示；但"有新变化未反映"的横幅应该随重新生成消失，
     * 因此已删除成员也要写一次当前指纹。
     */
    suspend fun markGenerated(collectionId: String, now: Long)

    /**
     * 成员内容或敏感状态变化后刷新所属合集。
     *
     * 两件事一起做：
     * 1. 重算合集 `sensitivity_status`（未删除成员的并集），让列表徽标与状态聚合跟上；
     * 2. **作废合集的云端确认** —— 用户确认的是"当时那份成员内容"，成员变了就必须重新确认。
     *
     * 写库本身也会推动详情页重新加载，实现"回到合集页即看到最新内容"。
     */
    suspend fun refreshForMemberChange(memberRecordId: String, now: Long)

    /** 未删除成员的实时敏感并集；空合集返回 `UNKNOWN`。 */
    suspend fun effectiveSensitivity(collectionId: String): SensitivityStatus

    /**
     * 是否有成员的当前内容与上次生成时的快照不一致。
     *
     * 从未生成（指纹为 null）也算过期：合集刚创建、摘要还没跑出来时，
     * 界面不该显示"最新"。
     */
    suspend fun isStale(collectionId: String): Boolean
}
