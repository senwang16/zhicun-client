package com.zhicun.app.data.web

import com.zhicun.app.domain.web.HostAddressResolver
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用系统 DNS 解析域名。
 *
 * `InetAddress.getAllByName` 是阻塞调用，且**会**触发真实 DNS 查询 ——
 * 因此只允许由 [com.zhicun.app.domain.web.SafeUrlChecker] 在 IO 线程上调用，
 * 并且只对"已确认不是字面量 IP"的主机名调用。
 */
@Singleton
class InetHostAddressResolver @Inject constructor() : HostAddressResolver {

    override fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()
}
