package com.aharou.feature.agent.domain.container

import android.content.Context
import android.net.ConnectivityManager
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.presentation.ContainerDnsMode
import java.io.File

/**
 * 容器 `/etc/resolv.conf` 的写入与「跟随系统」刷新。
 *
 * 从 ContainerInstaller 拆出（该文件已到行数上限）：这里只做 DNS 文件与宿主 IPv4 地址读取，
 * 不涉及安装流程。
 */
internal object ContainerDnsFiles {
    private const val TAG = "ContainerInstaller"
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private const val MAX_DNS_SERVERS = 3
    private val ALI_DNSPOD_DNS = listOf("223.5.5.5", "223.6.6.6", "119.29.29.29")
    private val CLOUDFLARE_GOOGLE_DNS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8")

    fun serversFor(mode: ContainerDnsMode, context: Context): List<String> = when (mode) {
        ContainerDnsMode.ALI_DNSPOD -> ALI_DNSPOD_DNS
        ContainerDnsMode.CLOUDFLARE_GOOGLE -> CLOUDFLARE_GOOGLE_DNS
        ContainerDnsMode.SYSTEM -> systemDnsServers(context).ifEmpty { ALI_DNSPOD_DNS }
    }

    fun write(rootfs: File, servers: List<String>, mode: ContainerDnsMode) {
        if (servers.isEmpty()) return
        writeFile(File(rootfs, "etc/resolv.conf"), servers, mode)
    }

    /**
     * 「跟随系统」模式下把容器 DNS 刷成当前网络的 DNS（换 WiFi / 挂代理后宿主 DNS 会变）。
     * 只在文件里有 `aharou-dns-mode: SYSTEM` 标记时动，否则会冲掉用户选的固定 DNS。
     */
    fun refreshSystem(rootfs: File, context: Context) {
        val conf = File(rootfs, "etc/resolv.conf")
        val text = runCatching { if (conf.isFile) conf.readText() else null }.getOrNull() ?: return
        if (!text.contains("aharou-dns-mode: ${ContainerDnsMode.SYSTEM.name}")) return
        val servers = systemDnsServers(context).ifEmpty { return }
        val current = text.lineSequence().map { it.trim() }.filter { it.startsWith("nameserver ") }.toList()
        if (current == servers.map { "nameserver $it" }) return
        writeFile(conf, servers, ContainerDnsMode.SYSTEM)
    }

    private fun writeFile(conf: File, servers: List<String>, mode: ContainerDnsMode) {
        runCatching {
            conf.parentFile?.mkdirs()
            // 容器里常见指向 systemd-resolved 的坏链，先删再写。
            conf.delete()
            conf.writeText(servers.joinToString("") { "nameserver $it\n" } + "# aharou-dns-mode: ${mode.name}\n")
            FileLogger.i(TAG, "容器 DNS 已写入：${servers.joinToString()}（$mode）")
        }.onFailure { FileLogger.w(TAG, "写容器 DNS 失败：${it.message}") }
    }

    /** 宿主当前网络的 IPv4 DNS。只取 IPv4：蜂窝/VPN 常「能配 v6 但出不去」，写进去会先等超时。 */
    private fun systemDnsServers(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        manager.getLinkProperties(manager.activeNetwork)?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { IPV4.matches(it) }
            ?.distinct()
            ?.take(MAX_DNS_SERVERS)
            .orEmpty()
    }.getOrElse { emptyList() }
}
