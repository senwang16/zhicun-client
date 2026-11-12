package com.zhicun.app.domain.web

import java.net.InetAddress

/**
 * 域名 → IP 列表。
 *
 * 抽成接口的唯一理由是**可测**：SSRF 防护的核心逻辑是"解析出来的地址是不是内网"，
 * 而真实 DNS 在单测里既慢又不确定。注入一个假解析器，就能穷举"域名解析到 127.0.0.1"
 * 这类经典绕过手法。
 *
 * 实现必须**阻塞式**返回（`InetAddress.getAllByName` 就是阻塞的），调用方负责切到 IO 线程。
 * 解析失败请抛 [java.net.UnknownHostException]，不要返回空列表 —— 空列表无法区分
 * "域名不存在"和"解析器还没实现"。
 */
fun interface HostAddressResolver {
    fun resolve(host: String): List<InetAddress>
}
