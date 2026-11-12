package com.zhicun.app.fake

import com.zhicun.app.domain.repository.DraftRepository

/** 草稿仓储假实现（M5）：内存里放一个槽位，顺带记录调用次数便于断言"保存成功后清了草稿"。 */
class FakeDraftRepository(initialText: String? = null) : DraftRepository {

    var draft: String? = initialText
        private set

    var clearCount: Int = 0
        private set

    override suspend fun loadText(): String? = draft

    override suspend fun saveText(text: String) {
        draft = text.takeIf { it.isNotBlank() }
    }

    override suspend fun clear() {
        clearCount++
        draft = null
    }
}
