package com.zhicun.app.fake

import com.zhicun.app.domain.settings.AiSettings

/**
 * 内存版云端 AI 开关（M14）。
 *
 * 默认 `true` 与生产默认值一致，这样绝大多数既有用例不必关心开关；
 * 需要验证闸门的用例显式传 `cloudAiEnabled = false`。
 *
 * ## 为什么可变属性叫 `cloudEnabled` 而不是 `cloudAiEnabled`
 * 接口已有 `setCloudAiEnabled` 方法，Kotlin 为 `var cloudAiEnabled` 生成的
 * `setCloudAiEnabled(Z)V` 会与它 JVM 签名冲突。留一个语义相同的可写字段，
 * 读仍然走接口的 `cloudAiEnabled`。
 */
class FakeAiSettings(
    cloudAiEnabled: Boolean = true,
) : AiSettings {

    /** 测试直接改写这个值来模拟"用户在设置页拨了开关"。 */
    var cloudEnabled: Boolean = cloudAiEnabled

    override val cloudAiEnabled: Boolean
        get() = cloudEnabled

    val writes: MutableList<Boolean> = mutableListOf()

    override fun setCloudAiEnabled(enabled: Boolean) {
        writes += enabled
        cloudEnabled = enabled
    }
}
