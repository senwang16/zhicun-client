package com.zhicun.app.service.share

import android.net.Uri

/**
 * 按 URI 查询真实 MIME 类型（`content://` 在系统里到底是什么文件）。
 *
 * 为什么不用 `android.content.ContentResolver` 直接当参数：它的 `getType` 在 SDK 里是
 * **`final` 方法**，测试里既不能 override、`ShadowContentResolver` 也没有 setType 之类的
 * 注入点，于是"通配声明类型要回查真实类型"这条最重要的分支就永远测不了。
 * 抽成一个单方法接口后，生产代码传 `contentResolver::getType`，测试传一个字面量 lambda。
 *
 * 实现在调用方（`ShareReceiverActivity`）：只有那里才拿得到有权限的 `ContentResolver`。
 */
fun interface MimeTypeLookup {

    /** @return 具体类型（例如 `image/png`）；查不到返回 `null`（不等于"出错"） */
    fun typeOf(uri: Uri): String?
}
