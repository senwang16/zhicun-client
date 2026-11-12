package com.zhicun.app.domain.export

import java.io.File

/**
 * 导出选项（M15）。
 *
 * @param includeDerivedContent 是否把 AI 派生内容（摘要、标签、OCR 文本、转写、向量）一并导出。
 *   默认不导：这些内容是由原文重新生成的，且向量是给机器看的（Base64 一大坨，人读不了）。
 *   用户在导出对话框里可以勾选。
 *
 *   **这个开关也管 AI 标签**（M16 修正）。取消勾选时只导出 `TagSource.USER` 的标签：
 *   用户说的"别带 AI 内容"包含"别带模型猜出来的分类"，不能只过滤摘要与识别文字。
 */
data class ExportOptions(
    val includeDerivedContent: Boolean = false,
)

/** 导出阶段。界面按阶段给不同的文案，避免"一直在转圈但不知道在干什么"。 */
enum class ExportPhase {
    /** 读库、装配清单。 */
    COLLECTING,

    /** 逐个写附件（大文件在这里耗时最多）。 */
    WRITING_ASSETS,

    /** 收尾（写清单、关 ZIP）。 */
    FINALIZING,
}

/**
 * 导出进度。
 *
 * @param total 当前阶段的单位总数（COLLECTING 时是记录数，WRITING_ASSETS 时是附件数）
 */
data class ExportProgress(
    val phase: ExportPhase,
    val done: Int,
    val total: Int,
) {
    /** 0f..1f；total 为 0 时返回 0（而不是 NaN）。 */
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** 一个待写入 ZIP 的附件：ZIP 内路径 → 磁盘路径。 */
data class ArchiveAssetEntry(
    val entryPath: String,
    val sourcePath: String,
)

/**
 * 写完的压缩包。只有文件与体积，其余统计由 use case 汇总 ——
 * 写文件的实现不该关心"导出了几条记录"。
 */
data class WrittenArchive(
    val file: File,
    val sizeBytes: Long,
)

/**
 * 导出结果。
 *
 * @param missingAssetCount 数据库里有附件行、但磁盘上找不到文件的数量。
 *   它必须如实带回给用户："导出了 20 条，其中 2 个附件文件已丢失" ——
 *   悄悄跳过会让人以为导出的包是完整的。
 */
data class ExportedArchive(
    val file: File,
    val sizeBytes: Long,
    val recordCount: Int,
    val assetCount: Int,
    val missingAssetCount: Int,
)

/**
 * 写 ZIP 的能力（M15）。
 *
 * 放在 domain 只声明"要一个带清单和附件的压缩包"；具体写到哪个目录、怎么压缩、
 * 怎么处理单个文件失败，都由 data 层决定（这样 use case 能在纯 JVM 上测）。
 */
interface ExportArchiveWriter {

    /**
     * @param manifestJson 已经序列化好的 `manifest.json` 内容
     * @param assets 待写入的附件（顺序即写入顺序）
     */
    suspend fun write(
        manifestJson: String,
        assets: List<ArchiveAssetEntry>,
        onProgress: (ExportProgress) -> Unit = {},
    ): WrittenArchive

    /**
     * 清空已生成的导出包（2026-11-01 审核 S-4，自海外轨移植）。
     *
     * 导出包是**全部记录的明文副本**，只服务于"刚导出就要分享"那一次动作；
     * 重试分享的引用只活在 ViewModel 内存里，新进程里不可能还有用。
     * 冷启动清空后，任何时刻最多只存一份明文副本。
     *
     * 删不掉不抛异常：下一次导出/冷启动还会再试，不值得因此打断用户操作。
     */
    suspend fun clear()
}
