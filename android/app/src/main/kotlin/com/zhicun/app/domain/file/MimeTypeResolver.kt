package com.zhicun.app.domain.file

/**
 * 按 URI 查询真实 MIME 类型（domain 契约，data 层用 `ContentResolver` 实现）。
 *
 * 为什么需要它：M5 的图片/PDF 选择器只给一个 `content://` URI，不给类型；
 * 而记录类型判定（图片 vs 文件）依赖类型。让 ViewModel 自己去碰 `ContentResolver`
 * 会破坏分层（AGENTS.md §2.3），所以抽成这个接口由 UseCase 使用。
 *
 * 与 `service/share/MimeTypeLookup` 的区别：那个面向 `Intent` 解析（吃 `android.net.Uri`），
 * 这个面向 domain（吃字符串 URI、可挂起），两者的实现层也不同。刻意不复用同一条接缝，
 * 是为了不让 `service` 层的类型渗进 domain。
 */
interface MimeTypeResolver {

    /** @return 具体 MIME（如 `image/png`）；查不到返回 `null`，不等于出错 */
    suspend fun resolve(uri: String): String?
}
