package com.zhicun.app.domain.web

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSRF 校验输出"可钉地址"的测试（M8 审核整改）。
 *
 * 只校验不钉死等于没堵住 DNS 重绑定：`SafeUrlChecker` 必须把**它实际检查过的那批 IP**
 * 带出来，请求层才有东西可钉。
 */
class SafeUrlCheckerPinningTest {

    private val publicIp = InetAddress.getByName("93.184.216.34")

    @Test
    fun Check_domainAllowed_carriesResolvedAddressesAsPins() = runTest {
        val checker = SafeUrlChecker(HostAddressResolver { _ -> listOf(publicIp) })

        val allowed = checker.check("https://example.com/a") as UrlCheck.Allowed

        assertEquals(listOf(publicIp), allowed.pinnedAddresses)
    }

    @Test
    fun Check_ipLiteral_carriesItselfAsPinWithoutDns() = runTest {
        // 字面量 IP 不该触发 DNS，但仍要把自己交出去当钉子
        val checker = SafeUrlChecker(
            HostAddressResolver { throw AssertionError("字面量 IP 不该走 DNS 解析") },
        )

        val allowed = checker.check("https://93.184.216.34/path") as UrlCheck.Allowed

        assertEquals(1, allowed.pinnedAddresses.size)
        assertEquals(publicIp, allowed.pinnedAddresses.single())
    }

    @Test
    fun Check_rejected_neverExposesAddresses() = runTest {
        val checker = SafeUrlChecker(
            HostAddressResolver { _ -> listOf(InetAddress.getByName("127.0.0.1")) },
        )

        val check = checker.check("https://127.0.0.1.nip.io/")

        assertTrue("解析到回环应拒绝", check is UrlCheck.Rejected)
    }

    @Test
    fun Check_emptyPinsAreNotSilentlyAllowedAsPinned() = runTest {
        // 防御性断言：合法放行时 pins 不应为空，否则请求层会退回不钉死的默认路径
        val checker = SafeUrlChecker(HostAddressResolver { _ -> listOf(publicIp) })

        val allowed = checker.check("http://example.com/") as UrlCheck.Allowed

        assertTrue(allowed.pinnedAddresses.isNotEmpty())
    }
}
