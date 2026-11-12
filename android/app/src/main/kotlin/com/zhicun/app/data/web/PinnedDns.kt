package com.zhicun.app.data.web

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * 把连接目标钉死在"校验时解析出来的地址"上（M8 审核整改：DNS 重绑定）。
 *
 * ## 要解决的问题
 * 只做"请求前解析一次并判定"是不够的：`SafeUrlChecker` 解析得到 93.184.216.34 并放行，
 * 紧接着 OkHttp 自己又解析一次同名域名 —— 两次解析之间攻击者可以把 DNS 记录切到
 * `127.0.0.1`。校验通过的是 A，实际连的是 B，SSRF 防护就此失效。
 *
 * 解决办法是让"第二次解析"不存在：[UrlCheck.Allowed.pinnedAddresses][com.zhicun.app.domain.web.UrlCheck.Allowed.pinnedAddresses]
 * 把校验过的地址带出来，注入成这次请求专用的 [Dns]。
 *
 * ## 为什么对未知主机抛异常而不是回落到系统解析
 * 失败要失败得安全（fail-closed）。只要出现"校验时没见过的 hostname"，
 * 就说明有代码路径绕过了逐跳校验；此时回落到系统 DNS 等于把这个漏洞重新打开。
 * 抛 `UnknownHostException` 会让 OkHttp 直接失败，任务落 `FAILED` 并在界面留痕 ——
 * 这是可接受的结果，静默放行不是。
 */
internal class PinnedDns(
    private val host: String,
    private val addresses: List<InetAddress>,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.equals(host, ignoreCase = true)) return addresses
        throw UnknownHostException("主机未通过 SSRF 校验，拒绝解析：$hostname")
    }
}
