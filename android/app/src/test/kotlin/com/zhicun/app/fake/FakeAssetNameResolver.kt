package com.zhicun.app.fake

import com.zhicun.app.domain.file.AssetNameResolver

/** 按 URI 返回预设文件名；未配置的 URI 返回 `null`（模拟 provider 不给名字）。 */
class FakeAssetNameResolver(
    private val names: Map<String, String> = emptyMap(),
) : AssetNameResolver {

    var throwOnResolve: Boolean = false
    val requested: MutableList<String> = mutableListOf()

    override suspend fun resolve(uri: String): String? {
        requested += uri
        if (throwOnResolve) throw IllegalStateException("模拟查询文件名失败")
        return names[uri]
    }
}
