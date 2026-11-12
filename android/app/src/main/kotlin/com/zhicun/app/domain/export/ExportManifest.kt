package com.zhicun.app.domain.export

import com.zhicun.app.domain.model.Asset
import com.zhicun.app.domain.model.AssetSaveStatus
import com.zhicun.app.domain.model.DerivedContent
import com.zhicun.app.domain.model.Record
import com.zhicun.app.domain.model.Tag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 导出清单的 schema 版本（M15）。
 *
 * **只增不改**：已经导出的文件会被用户保存很久，新版 App 不一定要能读，
 * 但同一个版本号必须永远表示同一种结构。加字段要升版本，读的时候按版本分流。
 */
object ExportSchema {

    /**
     * 版本历史：
     * - 1：M15 初版；
     * - 2（2026-10-24）：`ExportRecord` 增加 `archivedAt` —— 归档记录仍会进导出包
     *   （换手机不该丢数据），但读取方要能分辨哪些是归档的。
     * - 3（2026-10-28，M20）：`ExportRecord` 增加 `collectionMembers` —— 合集只存成员引用，
     *   不复制内容；已删除 / 已不存在的成员不写入（读取方拿到的引用一定可解析）。
     */
    const val VERSION: Int = 3
}

/**
 * 清单序列化的**唯一**配置。
 *
 * - `encodeDefaults`：保证 `schemaVersion` 一定出现在文件里。缺版本号的清单等于没有版本号，
 *   而 `schemaVersion` 有默认值，不开这个开关会被整段省略。
 * - `explicitNulls = false`：`rawText` 为 null 时整段省略，文件更小也更好读。
 * - `prettyPrint`：用户可能自己打开 `manifest.json` 看一眼（它就是个文本文件）。
 *
 * 放在文件级而不是 use case 的私有伴生对象里：测试要断言"清单里写了什么"，
 * 自己另建一份 `Json` 就等于测了另一套配置。
 */
internal val EXPORT_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 导出清单（ZIP 内的 `manifest.json`）。
 *
 * ## 为什么枚举一律存成字符串
 * 直接序列化枚举会让"改个枚举名"变成"旧导出文件解析失败"。这里显式写死字符串，
 * 结构上与 App 内部的枚举解耦：将来 `RecordType.VOICE_NOTE` 改名，导出文件里
 * 仍然是 `"VOICE_NOTE"`，第三次打开也还是能读懂。
 *
 * ## 为什么 `derived` 可以为空列表
 * AI 派生内容是可选导出项（PRD：用户可选是否包含 AI 摘要与标签）。
 * 不导出时保留空列表而不是缺字段，这样读取方只需要处理一种结构。
 */
@Serializable
data class ExportManifest(
    val schemaVersion: Int = ExportSchema.VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val includeDerivedContent: Boolean,
    val recordCount: Int,
    val assetCount: Int,
    val missingAssetCount: Int,
    val records: List<ExportRecord>,
)

@Serializable
data class ExportRecord(
    val id: String,
    val type: String,
    val captureSource: String,
    val rawText: String? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val sourceApp: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sensitivityStatus: String,
    /**
     * 归档时间（schema v2，2026-10-24）；`null`/缺省 = 未归档。
     *
     * 导出的语义是**备份**，所以归档记录照样打进包 —— 用户换手机时不能因为
     * "我把它收起来了"就少一批数据。但归档在 App 里的表现是"搜不到、不参与相似推荐"，
     * 读取方需要这个字段才能重建同样的视图，否则会把归档记录当成普通记录倒回去。
     *
     * 用时间戳而不是布尔：将来想按归档时间分组/排序时不必再升一次 schema。
     */
    val archivedAt: Long? = null,
    val tags: List<ExportTag> = emptyList(),
    val assets: List<ExportAsset> = emptyList(),
    val derived: List<ExportDerived> = emptyList(),
    /**
     * 合集成员（schema v3，2026-10-28）：按 `position` 升序的引用列表。
     *
     * 合集本身在包里的 `rawText` 是用户写的合集说明，成员内容仍由各自的记录承载 ——
     * 导出**不复制**成员内容，与 App 内的实时引用语义一致。
     * 已删除成员不出现：它们的记录不在包里，留下引用只会让读取方解析到空。
     */
    val collectionMembers: List<ExportCollectionMember> = emptyList(),
)

/** 合集成员引用（schema v3）。只存 id 与顺序，不存内容。 */
@Serializable
data class ExportCollectionMember(
    val recordId: String,
    val position: Int,
    val addedAt: Long,
)

@Serializable
data class ExportTag(
    val name: String,
    /** `USER` / `AI`：用户能分辨哪些是自己打的、哪些是模型猜的。 */
    val source: String,
)

@Serializable
data class ExportAsset(
    /** ZIP 内的相对路径（`assets/<recordId>/<assetId>.<ext>`）；文件缺失时该条目仍保留。 */
    val entryPath: String,
    val mimeType: String,
    val fileSize: Long,
    /** false = 数据库里有这条附件记录，但文件已经不在磁盘上（用户清理/迁移丢文件）。 */
    val filePresent: Boolean,
)

@Serializable
data class ExportDerived(
    val contentType: String,
    val content: String? = null,
    val processorVersion: String,
    val processedAt: Long,
    val errorMessage: String? = null,
)

/** 附件在 ZIP 里的固定目录，读取方据此找文件。 */
internal const val ASSETS_DIR: String = "assets"

/**
 * 附件在 ZIP 内的路径。
 *
 * 用 `assetId` 而不是原始文件名：原始文件名可能重复、可能含 `/`、可能是中文
 * （不同解压工具处理不一致）。扩展名按 MIME 推，推不出来就不带。
 */
internal fun assetEntryPath(recordId: String, asset: Asset): String {
    val ext = assetExtension(asset.mimeType)
    return "$ASSETS_DIR/$recordId/${asset.id}$ext"
}

internal fun assetExtension(mimeType: String): String = when {
    mimeType.equals("image/jpeg", ignoreCase = true) -> ".jpg"
    mimeType.equals("image/png", ignoreCase = true) -> ".png"
    mimeType.equals("image/webp", ignoreCase = true) -> ".webp"
    mimeType.equals("image/gif", ignoreCase = true) -> ".gif"
    mimeType.equals("application/pdf", ignoreCase = true) -> ".pdf"
    mimeType.equals("audio/mp4", ignoreCase = true) -> ".m4a"
    mimeType.equals("audio/mpeg", ignoreCase = true) -> ".mp3"
    mimeType.equals("audio/amr", ignoreCase = true) -> ".amr"
    mimeType.equals("audio/wav", ignoreCase = true) -> ".wav"
    mimeType.equals("text/plain", ignoreCase = true) -> ".txt"
    mimeType.equals("text/html", ignoreCase = true) -> ".html"
    else -> ""
}

/**
 * 一个附件"能不能打进包"的唯一判据。
 *
 * 两条都要满足：**保存状态是 SAVED**（`FAILED` 意味着当初就没复制完整，打进去的是坏文件），
 * 并且**磁盘上真的还在**。
 *
 * 单独抽出来是为了让清单（`filePresent`）与 ZIP 内容（写不写这个附件）用同一条规则。
 * 两处各写一遍的话，会出现"清单说文件不在了、包里却有一个"这种自相矛盾的导出 ——
 * 而清单是读取方唯一的入口，它一旦说了谎，包里那个文件永远不会被发现。
 */
internal fun isAssetAvailable(asset: Asset, fileExists: (Asset) -> Boolean): Boolean =
    asset.saveStatus == AssetSaveStatus.SAVED && fileExists(asset)

/** 把一条记录及其关联装配成清单条目。 */
internal fun buildExportRecord(
    record: Record,
    tags: List<Tag>,
    assets: List<Asset>,
    derived: List<DerivedContent>,
    collectionMembers: List<ExportCollectionMember> = emptyList(),
    // 放在最后：现有调用方都用尾随 lambda 传它，挪到中间会让所有调用点失效
    filePresent: (Asset) -> Boolean,
): ExportRecord = ExportRecord(
    id = record.id,
    type = record.type.name,
    captureSource = record.captureSource.name,
    // 原文照抄，不做任何清洗：导出的意义就是"拿到我当初存的东西"
    rawText = record.rawText,
    sourceUrl = record.sourceUrl,
    sourceTitle = record.sourceTitle,
    sourceApp = record.sourceApp,
    createdAt = record.createdAt,
    updatedAt = record.updatedAt,
    sensitivityStatus = record.sensitivityStatus.name,
    archivedAt = record.archivedAt,
    tags = tags.map { ExportTag(name = it.name, source = it.source.name) },
    assets = assets.map {
        ExportAsset(
            entryPath = assetEntryPath(record.id, it),
            mimeType = it.mimeType,
            fileSize = it.fileSize,
            filePresent = isAssetAvailable(it, filePresent),
        )
    },
    derived = derived.map {
        ExportDerived(
            contentType = it.contentType.name,
            content = it.content,
            processorVersion = it.processorVersion,
            processedAt = it.processedAt,
            errorMessage = it.errorMessage,
        )
    },
    collectionMembers = collectionMembers,
)
