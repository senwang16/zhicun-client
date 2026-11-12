package com.zhicun.app.domain.web

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSRF 防护规则测试（M8 验收："localhost/私有 IP 被拒绝，不发出请求"）。
 *
 * 这里只测判定，不测"有没有真的发请求"（那是 `OkHttpWebPageLoaderTest` 的事）。
 * 覆盖三类绕过手法：
 * 1. **协议绕过**：`file:` / `ftp:` / 没有协议头；
 * 2. **字面量绕过**：127.0.0.1、10.x、169.254.169.254（云元数据）、100.64.x（CGNAT）、fc00::；
 * 3. **解析绕过**：域名本身人畜无害，但解析出来是内网地址（如 `127.0.0.1.nip.io`）。
 */
class SafeUrlCheckerTest {

    private fun checkerReturning(vararg ips: String) = SafeUrlChecker(
        HostAddressResolver { ips.map { InetAddress.getByName(it) } },
    )

    private val publicDns = SafeUrlChecker(
        HostAddressResolver { _ -> listOf(InetAddress.getByName("93.184.216.34")) },
    )

    private fun reasonOf(check: UrlCheck): String {
        assertTrue("应当被拒绝，实际 $check", check is UrlCheck.Rejected)
        return (check as UrlCheck.Rejected).reason
    }

    @Test
    fun PublicHttpUrl_isAllowed() = runTest {
        val check = publicDns.check("https://example.com/post/1?q=2")

        assertTrue("公网地址应放行，实际 $check", check is UrlCheck.Allowed)
        assertEquals("https://example.com/post/1?q=2", (check as UrlCheck.Allowed).url)
    }

    @Test
    fun SchemeAndHost_areLowercased() = runTest {
        val check = publicDns.check("  HTTPS://Example.COM/Path  ")

        assertEquals(
            "https://example.com/Path",
            (check as UrlCheck.Allowed).url,
        )
    }

    @Test
    fun NonHttpSchemes_areRejected() = runTest {
        listOf(
            "file:///etc/passwd",
            "ftp://example.com/a",
            "javascript:alert(1)",
            "content://docs/1",
            "android.resource://com.zhicun.app/1",
        ).forEach { raw ->
            assertTrue("$raw 应被拒绝", publicDns.check(raw) is UrlCheck.Rejected)
        }
    }

    @Test
    fun MissingScheme_isRejected() = runTest {
        assertTrue(publicDns.check("example.com/post") is UrlCheck.Rejected)
    }

    @Test
    fun LocalhostAndLoopback_areRejected() = runTest {
        listOf(
            "http://localhost:8080/",
            "http://LOCALHOST/",
            "http://api.localhost/",
            "http://127.0.0.1/",
            "http://127.1.2.3/",
            "http://[::1]/",
            "http://0.0.0.0/",
        ).forEach { raw ->
            assertTrue("$raw 应被拒绝，实际 ${publicDns.check(raw)}", publicDns.check(raw) is UrlCheck.Rejected)
        }
    }

    @Test
    fun PrivateIpRanges_areRejected() = runTest {
        listOf(
            "10.0.0.1", // A 类私网
            "172.16.0.1", // B 类私网
            "172.31.255.254",
            "192.168.1.1", // C 类私网
            "169.254.169.254", // 云元数据服务
            "100.64.0.1", // 运营商级 NAT
            "198.18.0.1", // 基准测试保留段
            "0.1.2.3", // 0/8
            "255.255.255.255", // 广播
        ).forEach { ip ->
            val check = publicDns.check("http://$ip/admin")
            assertTrue("$ip 应被拒绝，实际 $check", check is UrlCheck.Rejected)
        }
    }

    @Test
    fun PrivateIpv6_areRejected() = runTest {
        listOf("http://[fc00::1]/", "http://[fd12:3456::1]/", "http://[fe80::1]/").forEach { raw ->
            assertTrue("$raw 应被拒绝，实际 ${publicDns.check(raw)}", publicDns.check(raw) is UrlCheck.Rejected)
        }
    }

    @Test
    fun PublicIpLiteral_isAllowed() = runTest {
        // 字面量公网 IP 不应触发 DNS 解析，也不该被误杀
        val checker = SafeUrlChecker(
            HostAddressResolver { throw AssertionError("字面量 IP 不该走 DNS 解析") },
        )

        val check = checker.check("https://93.184.216.34/path")

        assertTrue("公网字面量 IP 应放行，实际 $check", check is UrlCheck.Allowed)
    }

    @Test
    fun PublicIpv6Literal_keepsBracketsInRebuiltUrl() = runTest {
        // 2026-10-28：host 为了判内网被剥掉方括号，rebuild 必须补回，
        // 否则 OkHttp 会把 `2606:...` 当成非法端口，公网 IPv6 页面抓不到
        val checker = SafeUrlChecker(
            HostAddressResolver { throw AssertionError("字面量 IP 不该走 DNS 解析") },
        )

        val check = checker.check("https://[2606:4700:4700::1111]/path?q=1")

        assertTrue("公网 IPv6 字面量应放行，实际 $check", check is UrlCheck.Allowed)
        val allowed = check as UrlCheck.Allowed
        assertEquals("https://[2606:4700:4700::1111]/path?q=1", allowed.url)
        assertEquals(
            InetAddress.getByName("2606:4700:4700::1111"),
            allowed.pinnedAddresses.single(),
        )
    }

    @Test
    fun CancellationDuringResolve_isPropagated() = runTest {
        // 取消不是"解析失败"：被 catch(Exception) 吞掉会让已取消的网页处理继续跑
        val checker = SafeUrlChecker(
            HostAddressResolver { throw CancellationException("cancelled") },
        )

        var caught: CancellationException? = null
        try {
            checker.check("https://example.com/")
        } catch (e: CancellationException) {
            caught = e
        }

        assertNotNull("取消必须原样抛出，不能被当作域名解析失败", caught)
        assertEquals("cancelled", caught?.message)
    }

    @Test
    fun SingleLabelHost_isRejected() = runTest {
        // 内网主机名（路由器管理页那种）
        assertTrue(publicDns.check("http://router/") is UrlCheck.Rejected)
    }

    @Test
    fun InternalHostSuffixes_areRejected() = runTest {
        listOf(
            "http://nas.local/",
            "http://jenkins.internal/",
            "http://printer.home.arpa/",
        ).forEach { raw ->
            assertTrue("$raw 应被拒绝", publicDns.check(raw) is UrlCheck.Rejected)
        }
    }

    @Test
    fun DomainResolvingToPrivateAddress_isRejected() = runTest {
        // 经典绕过：域名看着正常，解析出来指向内网
        val checker = checkerReturning("127.0.0.1")

        val check = checker.check("https://127.0.0.1.nip.io/")

        assertTrue("解析到回环必须拒绝，实际 $check", check is UrlCheck.Rejected)
    }

    @Test
    fun DomainResolvingToMixedAddresses_isRejected() = runTest {
        // 一个公网 + 一个内网：只要有一个落在内网就拒绝，不能"随机挑一个连"
        val checker = checkerReturning("93.184.216.34", "192.168.0.10")

        assertTrue(checker.check("https://evil.example.com/") is UrlCheck.Rejected)
    }

    @Test
    fun UnknownHost_isRejected() = runTest {
        val checker = SafeUrlChecker(HostAddressResolver { throw UnknownHostException("no such host") })

        assertEquals("域名无法解析", reasonOf(checker.check("https://nope.example/")))
    }

    @Test
    fun EmptyResolverResult_isRejected() = runTest {
        val checker = SafeUrlChecker(HostAddressResolver { emptyList() })

        assertTrue(checker.check("https://empty.example/") is UrlCheck.Rejected)
    }

    @Test
    fun BlankUrl_isRejected() = runTest {
        assertTrue(publicDns.check("   ") is UrlCheck.Rejected)
    }

    @Test
    fun MissingHost_isRejected() = runTest {
        assertTrue(publicDns.check("http:///path") is UrlCheck.Rejected)
    }
}
