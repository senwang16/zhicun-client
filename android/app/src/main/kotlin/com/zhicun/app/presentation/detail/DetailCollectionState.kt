package com.zhicun.app.presentation.detail

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.CollectionMember
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.RecordType
import com.zhicun.app.domain.repository.AssetRepository
import com.zhicun.app.domain.repository.CollectionRepository
import com.zhicun.app.domain.usecase.GetDerivedContentUseCase

/**
 * 合集详情页要展示的成员数据（M20）。
 *
 * 与收件箱列表的 `Record` 分开建模：成员卡片需要**原记录 + 派生内容 + 附件**三样一起排，
 * 而这三样各查一次在本页是可控的（成员只有几条）。实时引用意味着这里存的是引用结果，
 * 不是拷贝 —— 每次打开/记录变化都重新读，成员改了正文这里就跟着变。
 */
data class DetailCollectionState(
    val members: List<CollectionMemberView> = emptyList(),
    /** 有成员内容与上次生成时的快照不一致（含"从未生成"）。 */
    val stale: Boolean = false,
)

/** 一条成员来源：关系行、当前原记录、派生正文与附件。 */
data class CollectionMemberView(
    val member: CollectionMember,
    val record: Record,
    val derived: List<DerivedContent> = emptyList(),
    val assets: List<Asset> = emptyList(),
)

/**
 * 成员卡片上展示的正文。
 *
 * 网页成员用 `PAGE_TEXT`（抓回来的正文）而不是用户当时分享的那行链接/短评；
 * 其余类型用 `rawText`。OCR / 转写全文**不在合集页展示**（M20 范围之外），
 * 成员卡片保留"查看原记录"入口。
 */
internal fun CollectionMemberView.bodyText(): String? {
    val pageText = derived
        .firstOrNull { it.contentType == DerivedType.PAGE_TEXT }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (record.type == RecordType.WEB_LINK && pageText != null) return pageText
    return record.rawText?.trim()?.takeIf { it.isNotEmpty() } ?: pageText
}

/**
 * 读取合集成员与过期状态；非合集返回空状态。
 *
 * 查不到原记录的关系行不会出现在结果里（物理清理会级联删除，读到缺失只可能是极端并发）：
 * 界面宁可不显示，也不画一张点进去 404 的空壳卡片。
 */
internal suspend fun loadDetailCollectionState(
    record: Record,
    collectionRepository: CollectionRepository,
    getDerivedContent: GetDerivedContentUseCase,
    assetRepository: AssetRepository,
): DetailCollectionState {
    if (record.type != RecordType.COLLECTION) return DetailCollectionState()
    val members = collectionRepository.getMembersWithRecords(record.id).map { item ->
        CollectionMemberView(
            member = item.member,
            record = item.record,
            derived = getDerivedContent(item.record.id),
            assets = assetRepository.getByRecordId(item.record.id),
        )
    }
    return DetailCollectionState(
        members = members,
        stale = collectionRepository.isStale(record.id),
    )
}
