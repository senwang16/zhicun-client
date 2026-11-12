package com.zhicun.app.domain.web

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个 URL 是否允许被请求。 */
sealed interface UrlCheck {

    /**
     * 通过校验，[url] 是规范化后的地址（协议与主机小写、去掉首尾空白与末尾点）。
     *
     * [pinnedAddresses] 是**校验时实际检查过的那些 IP**。调用方发请求时必须把连接目标
     * 钉死在这份地址上（OkHttp 侧即注入自定义 `Dns`），否则域名会在连接时被重新解析一次，
     * 攻击者用极短 TTL 的 DNS 记录就能让"校验通过的公网地址"变成"实际连到的内网地址"
     * （DNS 重绑定，M5–M8 审核发现）。为空表示"没有可钉的地址"（仅字面量解析失败等异常情况），
     * 此时调用方应自行决定是否继续。
     */
    data class Allowed(
        val url: String,
        val pinnedAddresses: List<InetAddress> = emptyList(),
    ) : UrlCheck

    /** 拒绝原因，会写进任务错误信息，因此必须是人话（不出现异常类名）。 */
    data class Rejected(val reason: String) : UrlCheck
}

/**
 * 抓取前的地址校验（M8）。
 *
 * 抽成接口是为了让 [com.zhicun.app.data.web.OkHttpWebPageLoader] 的测试能对着
 * 本地 HTTP 服务跑：真实校验会拒绝 `127.0.0.1`，而测试服务恰好只监听本机。
 * 生产实现是 [SafeUrlChecker]，规则全在那里，测试见 `SafeUrlCheckerTest`。
 */
interface UrlChecker {

    suspend fun check(rawUrl: String): UrlCheck
}

/**
 * SSRF 防护（M8）。
 *
 * 用户存进来的链接来自任意 App，其中可能有 `http://192.168.1.1/` 这类内网地址。
 * 如果后台直接去抓，就等于把用户所在的内网变成了我们的探测通道 —— 既可能扫到路由器管理页，
 * 也可能打到云环境的元数据服务（169.254.169.254）。所以**发请求之前必须过这一关**，
 * 而且**每次重定向之后都要重新过一遍**（否则 `https://evil.com → http://127.0.0.1/`
 * 就能绕过首次检查）。
 *
 * 三层判定：
 * 1. **协议**：只放 http 与 https，`file:` / `ftp:` / `javascript:` 一律拒绝；
 * 2. **主机名形态**：localhost、`.local` / `.internal` / `.home.arpa`、单标签主机名（`http://router/`）拒绝；
 * 3. **解析后的真实地址**：回环、私网、链路本地、多播、CGNAT、IPv6 唯一本地全部拒绝。
 *    这一步不能只看字面量 —— `http://127.0.0.1.nip.io/` 这种域名解析出来就是回环地址。
 *
 * 第 3 步解析出来的地址会随 [UrlCheck.Allowed.pinnedAddresses] 一起返回，
 * 由请求层（`OkHttpWebPageLoader` + `PinnedDns`）钉死连接目标。**只校验不钉死是不够的**：
 * 校验与连接之间再做一次 DNS 就可能拿到另一个地址（DNS 重绑定）。
 * 把"校验过的地址"变成"唯一允许连接的地址"，两次解析的差异才真正被消除。
 */
class SafeUrlChecker(
    private val resolver: HostAddressResolver,
) : UrlChecker {

    override suspend fun check(rawUrl: String): UrlCheck {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return UrlCheck.Rejected("链接为空")

        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return UrlCheck.Rejected("不是合法的链接")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return UrlCheck.Rejected("链接缺少协议头")
        if (scheme != "http" && scheme != "https") {
            return UrlCheck.Rejected("只支持 http 与 https 链接")
        }

        // URI.getHost() 对 IPv6 字面量返回带方括号的形式（如 [::1]），
        // 不剥掉的话会当成域名去解析，等于把回环地址交给了 DNS —— 直接漏判。
        val host = uri.host
            ?.lowercase(Locale.ROOT)
            ?.removeSurrounding("[", "]")
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }
            ?: return UrlCheck.Rejected("链接缺少主机名")

        rejectLocalHostName(host)?.let { return it }

        val literal = parseIpLiteralOrNull(host)
        if (literal != null) {
            return if (isBlockedAddress(literal)) {
                UrlCheck.Rejected("拒绝访问内网地址")
            } else {
                // 字面量 IP 本身就是连接目标，钉死它不需要任何后续解析
                UrlCheck.Allowed(rebuild(scheme, host, uri), pinnedAddresses = listOf(literal))
            }
        }

        // 域名：必须解析成真实 IP 再判 —— 只写 host 白名单挡不住 127.0.0.1.nip.io 这类解析绕过
        val addresses = try {
            withContext(Dispatchers.IO) { resolver.resolve(host) }
        } catch (e: CancellationException) {
            // 任务被取消时 withContext 会抛取消异常；若被下面的 catch(Exception) 吞掉，
            // 调用方会把"已取消"当成"域名解析失败"继续处理，取消信号就丢了。
            throw e
        } catch (e: UnknownHostException) {
            return UrlCheck.Rejected("域名无法解析")
        } catch (e: Exception) {
            // 解析器内部错误（比如系统 DNS 不可用）：当作拒绝，不要当成"可以通过"
            return UrlCheck.Rejected("域名解析失败")
        }
        if (addresses.isEmpty()) return UrlCheck.Rejected("域名无法解析")
        if (addresses.any(::isBlockedAddress)) {
            return UrlCheck.Rejected("拒绝访问内网地址")
        }

        // 把刚校验过的这批地址交出去钉死：请求层不得再让系统 DNS 解析一次
        return UrlCheck.Allowed(rebuild(scheme, host, uri), pinnedAddresses = addresses)
    }

    /**
     * 地址是否属于"不该由 App 主动访问"的范围。
     *
     * `InetAddress` 自带的四个判断覆盖了大部分（回环、任意本地、链路本地、站点本地、多播），
     * 这里再补三块它不管的：CGNAT（100.64/10）、IPv6 唯一本地（fc00::/7）、
     * 保留段（0/8、192.0.0/24、198.18/15、240/4）。
     */
    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true
            if (first == 192 && second == 0) return true
            if (first == 198 && (second == 18 || second == 19)) return true
            if (first >= 240) return true
        }

        if (address is Inet6Address && bytes.isNotEmpty()) {
            // fc00::/7：前 7 位是 1111110
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    /** 主机名层面的拒绝：这些名字连解析都不必做。 */
    private fun rejectLocalHostName(host: String): UrlCheck.Rejected? {
        if (host == "localhost") return UrlCheck.Rejected("拒绝访问本机地址")
        val localSuffixes = listOf(".localhost", ".local", ".internal", ".home.arpa", ".localdomain")
        if (localSuffixes.any { host.endsWith(it) }) return UrlCheck.Rejected("拒绝访问内网主机名")
        // 单标签主机名（没有点）几乎只存在于内网，公网域名不可能长这样
        if (!host.contains('.') && !host.contains(':')) {
            return UrlCheck.Rejected("拒绝访问内网主机名")
        }
        return null
    }

    /**
     * 只解析**字面量 IP**，绝不触发 DNS。
     *
     * 这一点很关键：如果这里用 `InetAddress.getByName(host)` 去试，任何域名都会触发一次
     * 真实解析，等于"为了判断要不要解析而先解析一次"。所以先用形态判断（点分四段 / 含冒号），
     * 是字面量才交给 `getByName`（纯数字字符串不会走 DNS）。
     */
    private fun parseIpLiteralOrNull(host: String): InetAddress? {
        val looksLikeIpv4 = IPV4_LITERAL.matches(host)
        val looksLikeIpv6 = host.contains(':')
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            null
        }
    }

    /** 把协议与主机统一成小写，路径与查询串保持原样。 */
    private fun rebuild(scheme: String, host: String, uri: URI): String {
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        // IPv6 字面量在 URL 里必须带方括号。上面为了判内网把方括号剥掉了，
        // 重建时必须补回，否则 `https://2606:4700:4700::1111/` 会被 OkHttp
        // 当成"主机 2606 + 非法端口"而解析失败 —— 公网 IPv6 网页直接抓不到。
        val urlHost = if (host.contains(':')) "[$host]" else host
        return "$scheme://$urlHost$port$path$query"
    }

    private companion object {
        val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
