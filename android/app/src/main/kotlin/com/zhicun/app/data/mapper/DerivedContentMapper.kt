package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType

/** [DerivedContentEntity] ↔ [DerivedContent] 映射。失败结果的 `content` 为 null、`errorMessage` 有值。 */
fun DerivedContentEntity.toDomain(): DerivedContent = DerivedContent(
    id = id,
    recordId = recordId,
    contentType = contentType,
    content = content,
    processorVersion = processorVersion,
    processedAt = processedAt,
    errorMessage = errorMessage,
    // 空串是库里的"记录级"表示，domain 用 null 表达同一个意思
    assetId = assetId.takeIf { it.isNotEmpty() },
)

/**
 * 读派生内容时的容错入口（2026-11-10 Bug 4）：无法识别的 `content_type` 返回 null（跳过该行）。
 *
 * ## 为什么在这里跳过，而不是让转换器抛异常
 * 转换器只能返回一个枚举值，没有"这一行无效"的表达；而读整批派生内容的查询
 * （`getByRecordId`）如果被一条脏行打断，整页详情报错 —— 用户连原文都看不到。
 *
 * 判据只有 [DerivedType.UNKNOWN] 一处：转换器把无法识别的库值映射成它，
 * 这里（唯一进入 domain 的入口）统一过滤，展示层因此永远见不到 UNKNOWN。
 */
fun DerivedContentEntity.toDomainOrNull(): DerivedContent? =
    if (contentType == DerivedType.UNKNOWN) null else toDomain()

fun DerivedContent.toEntity(): DerivedContentEntity = DerivedContentEntity(
    id = id,
    recordId = recordId,
    contentType = contentType,
    content = content,
    processorVersion = processorVersion,
    processedAt = processedAt,
    errorMessage = errorMessage,
    assetId = assetId ?: "",
)
