package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.DerivedContentEntity
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.DerivedType

/**
 * 只有生成类结果才记录输出语言（P11 / R-I18N-08）。
 *
 * OCR 文本、语音转写、网页正文都是**原始内容**：它们忠实于来源，不会因为用户切换界面语言
 * 而变成另一门语言。把它们也标上语言，会让"这条转写是英文"被误读成"模型翻译成了英文"。
 */
private val LANGUAGE_BEARING_TYPES = setOf(
    DerivedType.TITLE,
    DerivedType.SUMMARY,
    DerivedType.TAGS,
)

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
    outputLanguage = outputLanguage.takeIf { contentType in LANGUAGE_BEARING_TYPES },
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
    // 原始内容不标记语言：即便 domain 层被误塞了值，落库也回到中文缺省，
    // 避免"转写结果被标成生成语言"这种不可逆的脏数据。
    outputLanguage = outputLanguage.takeIf { contentType in LANGUAGE_BEARING_TYPES } ?: "zh",
)