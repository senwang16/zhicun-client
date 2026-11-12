package com.zhicun.app.data.file

import android.net.Uri
import androidx.core.net.toUri

/**
 * 日志脱敏（2026-10-22 审核：外部 URI 与路径不得整串进日志）。
 *
 * `content://` URI 的 path/query 可能携带资源标识与临时授权信息，
 * 文件路径可能暴露用户内容线索；日志里保留**定位所需的类别信息**
 * （来源 provider、文件名），丢掉其余部分。仅用于日志，不做业务判断。
 */
internal object LogSanitizer {

    /** URI：保留 `scheme://authority`（能看出来源 App），path/query 一律丢弃。 */
    fun uri(raw: Uri): String =
        "${raw.scheme ?: "?"}://${raw.authority ?: "<no-authority>"}/**"

    /** 字符串形式的 URI，内部解析后走 [uri]；解析失败按未知处理。 */
    fun uri(raw: String): String = runCatching {
        uri(raw.toUri())
    }.getOrDefault("<uri>")

    /** 路径：只保留最后一段（本项目的落盘文件名都经过 safeFileName 白名单化）。 */
    fun path(raw: String): String =
        raw.substringAfterLast('/').ifBlank { "<blank>" }
}
