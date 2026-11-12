package com.zhicun.app.fake

import com.zhicun.app.domain.file.MimeTypeResolver

/**
 * MIME 解析器假实现：按 URI → 类型映射查表。
 *
 * 不传映射时一律返回 `null`，模拟"provider 不响应 getType"的真实情况。
 */
class FakeMimeTypeResolver(
    private val types: Map<String, String> = emptyMap(),
) : MimeTypeResolver {

    /** 置为 true 时每次查询都抛异常，用于测"查不到类型也不能让保存失败"。 */
    var throwOnResolve: Boolean = false

    val queried: MutableList<String> = mutableListOf()

    override suspend fun resolve(uri: String): String? {
        queried += uri
        if (throwOnResolve) throw SecurityException("模拟无读取权限 uri=$uri")
        return types[uri]
    }
}
