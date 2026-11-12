package com.zhicun.app.domain.file

/**
 * 按 URI 查询原始文件名（domain 契约，data 层用 `ContentResolver` + `OpenableColumns` 实现）。
 *
 * 新建页在用户选完文件后就要显示"这张图叫什么"，必须赶在保存之前查一次；
 * 保存时导入器还会再查一次作为权威值（解析阶段拿不到属于正常降级）。
 *
 * 与 [MimeTypeResolver] 分开：两者是独立的查询，失败互不影响 ——
 * 查得到类型但拿不到名字（部分 provider 只给类型）很常见，合成一个方法会逼调用方
 * 在"名字丢了"和"类型丢了"之间做错误的取舍。
 */
interface AssetNameResolver {

    /** @return 原始文件名；拿不到返回 `null`（不等于出错，界面回退"图片 N"） */
    suspend fun resolve(uri: String): String?
}
