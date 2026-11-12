package com.zhicun.app.data.web

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * DNS 钉死（M8 审核整改）。
 *
 * 这层是"校验过的地址 = 唯一允许连接的地址"的落地点：
 * 只要它对未知主机回落到系统解析，DNS 重绑定就重新成立，所以负向用例比正向用例更重要。
 */
class PinnedDnsTest {

    private val pinned = InetAddress.getByName("93.184.216.34")

    @Test
    fun Lookup_pinnedHost_returnsValidatedAddresses() {
        val dns = PinnedDns("example.com", listOf(pinned))

        assertEquals(listOf(pinned), dns.lookup("example.com"))
    }

    @Test
    fun Lookup_hostMatchIsCaseInsensitive() {
        val dns = PinnedDns("Example.COM", listOf(pinned))

        assertEquals(listOf(pinned), dns.lookup("example.com"))
    }

    @Test
    fun Lookup_otherHost_failsClosedInsteadOfResolvingSystemDns() {
        val dns = PinnedDns("example.com", listOf(pinned))

        // 关键断言：抛出而不是去查系统 DNS —— 出现未校验的主机名说明有路径绕过了逐跳校验
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun Lookup_nonAsciiPunycodeMismatch_failsClosed() {
        val dns = PinnedDns("xn--fiqs8s.example", listOf(pinned))

        assertThrows(UnknownHostException::class.java) { dns.lookup("中国.example") }
    }
}
