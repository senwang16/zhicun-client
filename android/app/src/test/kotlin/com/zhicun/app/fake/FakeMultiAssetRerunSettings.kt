package com.zhicun.app.fake

import com.zhicun.app.domain.settings.MultiAssetRerunSettings

/** 内存版多附件补跑标记（v8 升级）。 */
class FakeMultiAssetRerunSettings(
    override var completed: Boolean = false,
) : MultiAssetRerunSettings {

    override fun markCompleted() {
        completed = true
    }
}
