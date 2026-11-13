package com.zhicun.app

import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/**
 * 明文 HTTP 放行范围的契约测试（2026-11-13 审核 #3）。
 *
 * ## 为什么断言的是资源本身，而不是 `NetworkSecurityPolicy`
 * 直觉上应该问平台"这个 host 允许明文吗"，但 `NetworkSecurityPolicy` 的判定依赖
 * `ApplicationConfig.getDefaultInstance()`，而 Robolectric 不会去初始化它 ——
 * 结果是**任何** host 都返回 true，测试看起来通过却什么都没验证（实测确认：
 * 放开 `api.openai.com` 也照样返回 true）。所以这里直接解析打包进 APK 的那份 XML，
 * 断言真正会下发给系统的规则。
 *
 * 这样至少能挡住三类真实回归：
 * - 有人把 `base-config` 的 `cleartextTrafficPermitted` 改成 true（等于全局放开明文）；
 * - 有人删掉某个回环写法（本地 Ollama 的某个地址会重新被系统拦掉）；
 * - 有人误加局域网/公网域名（API Key 会明文外发）。
 *
 * 真机上"这份配置是否被系统采纳"由 Manifest 的 `android:networkSecurityConfig`
 * 保证，那是清单合并的事，`assembleDebug` 会校验。
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigTest {

    /** 解析资源 XML，返回 (baseConfig 是否允许明文, domain-config 是否允许明文, 域名列表)。 */
    private data class Parsed(
        val baseCleartext: Boolean?,
        val domainCleartext: Boolean?,
        val domains: List<String>,
    )

    private fun parse(): Parsed {
        val res = RuntimeEnvironment.getApplication().resources
        val parser: XmlResourceParser = res.getXml(R.xml.network_security_config)
        var baseCleartext: Boolean? = null
        var domainCleartext: Boolean? = null
        val domains = mutableListOf<String>()
        var depth = 0
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    currentTag = parser.name
                    when (parser.name) {
                        "base-config" ->
                            baseCleartext = parser.getAttributeBooleanValue(
                                null,
                                "cleartextTrafficPermitted",
                                false,
                            )

                        "domain-config" ->
                            domainCleartext = parser.getAttributeBooleanValue(
                                null,
                                "cleartextTrafficPermitted",
                                false,
                            )

                        "domain" -> domains += parser.nextText().trim()
                    }
                }

                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth == 0) currentTag = null
                }
            }
            parser.next()
        }
        return Parsed(baseCleartext, domainCleartext, domains)
    }

    @Test
    fun baseConfig_stillForbidsCleartext() {
        val parsed = parse()
        assertEquals(
            "默认策略必须是禁止明文：放行只针对下面的回环白名单",
            false,
            parsed.baseCleartext,
        )
    }

    @Test
    fun domainConfig_permitsCleartextOnlyForLoopbackHosts() {
        val parsed = parse()
        assertEquals("回环白名单必须允许明文", true, parsed.domainCleartext)

        val expected = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
        assertEquals(
            "白名单只应包含本机回环（含模拟器宿主机别名），不得混入局域网/公网域名",
            expected,
            parsed.domains.toSet(),
        )
        assertTrue(
            "局域网与公网地址不得出现在白名单里（会明文外发 API Key）",
            parsed.domains.none { it.startsWith("192.168.") || it.startsWith("10.") && it != "10.0.2.2" },
        )
    }
}
