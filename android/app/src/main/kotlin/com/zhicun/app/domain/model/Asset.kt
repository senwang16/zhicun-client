package com.zhicun.app.domain.model

/**
 * 音频附件的 MIME 前缀。
 *
 * 这个字面量原来抄在四个文件里（分类、转写、详情页两块），改一处漏三处不会报错，
 * 只会让"某条路径认不出这是录音"。统一放在 [Asset] 旁边，各处 import 同一份。
 */
const val AUDIO_MIME_PREFIX = "audio/"

/**
 * 记录关联的附件（Domain 模型，对应 ARCHITECTURE.md §4.2 / PRD §9.2）。
 *
 * `localPath` 必须是 App 私有目录下的**绝对路径**；`saveStatus = SAVED` 之前不得对外宣称附件可用
 * （ARCHITECTURE §6.2 先落盘后处理）。`originalUri` 仅作追溯，外部 URI 失效不影响本地记录。
 */
data class Asset(
    val id: String,
    val recordId: String,
    val mimeType: String,
    val localPath: String,
    val fileSize: Long,
    val fileHash: String?,
    val originalUri: String?,
    val saveStatus: AssetSaveStatus,
    /** 系统提供的原始文件名，仅用于展示（磁盘名仍是清洗后的版本）；拿不到时为 null。 */
    val fileName: String? = null,
    /** 保存时的选择顺序，0 起；老数据全为 0，退化为按 id 排。 */
    val orderIndex: Int = 0,
) {
    /** 是不是录音。 */
    val isAudio: Boolean get() = mimeType.startsWith(AUDIO_MIME_PREFIX, ignoreCase = true)

    /**
     * 这个附件**会不会被上传到云端**（2026-10-23 审查 R-2）。
     *
     * 目前只有录音：转写走云端 ASR，音频字节就是请求体。图片**不上传** ——
     * OCR 用 ML Kit 本地模型，图片字节从不出设备，所以它不算"用户需要确认的一块内容"。
     * 将来若新增"图片上传"，判据改这一个属性，确认范围与外发闸门会一起跟上。
     */
    val isUploadable: Boolean get() = isAudio
}

/** 附件落盘状态。MISSING 表示文件曾经保存成功但后续丢失（如用户清理了私有目录）。 */
enum class AssetSaveStatus {
    SAVED,
    FAILED,
    MISSING,
}
