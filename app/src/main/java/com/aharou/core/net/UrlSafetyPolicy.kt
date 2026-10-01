package com.aharou.core.net

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 联网工具的地址安全策略：拒绝环回、私网、链路本地等不该被 Agent 访问的地址，
 * 避免抓取工具被当成打内网或本机服务的跳板（SSRF）。
 *
 * 只作用于工具侧的抓取，不影响用户自己配置的 provider 端点——那些走各自协议的 HTTP 客户端。
 */
object UrlSafetyPolicy {

    /**
     * [host] 可以是 IP 字面量或域名（域名会先解析再判定）。命中禁用网段返回 true。
     * 名字解析不出来时不拦截——交给请求本身报 DNS 错误，避免把「网络不通」伪装成安全拒绝。
     */
    fun isBlockedHost(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.isEmpty()) return true
        val addresses = try {
            InetAddress.getAllByName(clean)
        } catch (e: UnknownHostException) {
            return false
        }
        return addresses.any { isBlockedAddress(it) }
    }

    private fun isBlockedAddress(addr: InetAddress): Boolean {
        // isSiteLocalAddress 覆盖 10/8、172.16/12、192.168/16；isLinkLocalAddress 覆盖 169.254/16 与 fe80::/10
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }

        val bytes = addr.address
        // IPv4-mapped IPv6（::ffff:127.0.0.1）不命中上面几个判定，取出后 4 字节按 IPv4 再判一次
        val v4 = when {
            bytes.size == 4 -> bytes
            bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte() -> bytes.copyOfRange(12, 16)
            else -> null
        } ?: return false

        val b0 = v4[0].toInt() and 0xFF
        val b1 = v4[1].toInt() and 0xFF
        return when {
            b0 == 0 -> true                                     // 0.0.0.0/8
            b0 == 100 && b1 in 64..127 -> true                  // 100.64.0.0/10 运营商级 NAT
            b0 == 192 && b1 == 0 && (v4[2].toInt() and 0xFF) == 0 -> true // 192.0.0.0/24 保留
            b0 == 198 && b1 in 18..19 -> true                   // 198.18.0.0/15 基准测试保留
            b0 >= 240 -> true                                   // 240.0.0.0/4 保留
            else -> false
        }
    }
}
