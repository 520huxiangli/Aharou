package com.aharou.core.net

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已注入的 hosts 块里写了连不上的 IP」这条自愈路径。
 *
 * 回归的是：块没过期、也覆盖了全部目标域名，但里面的 IP 探活不通 —— 这时必须重解析重注入，
 * 否则容器里的 git 会一直失败到这个块过期（REFRESH_AFTER_MS，7 天）为止。
 *
 * 注意：重注入要真的解析出可用 IP（探活 GitHub 的 443），所以本用例依赖外网；
 * 离线环境下会失败，这是刻意的——离线时它也修不好 hosts。
 */
class GitHubDnsFallbackTest {

    private val begin = "# >>> Aharou DNS 兜底（解析失败时注入，整段删掉即还原）"
    private val end = "# <<< Aharou DNS 兜底"

    /** 192.0.2.1 属 RFC5737 的 TEST-NET-1，保证连不上。 */
    private val deadIp = "192.0.2.1"

    private fun rootfsWithBlock(stamp: Long, vararg entries: Pair<String, String>): File {
        val rootfs = Files.createTempDirectory("dns-fallback-test").toFile()
        File(rootfs, "etc/hosts").apply { parentFile?.mkdirs() }.writeText(
            buildString {
                appendLine("127.0.0.1 localhost")
                appendLine(begin)
                appendLine("# injected-at: $stamp")
                entries.forEach { (ip, host) -> appendLine("$ip $host") }
                appendLine(end)
            }
        )
        return rootfs
    }

    private fun hostsText(rootfs: File) = File(rootfs, "etc/hosts").readText()

    private fun freshBlockWithDeadIps() = rootfsWithBlock(
        System.currentTimeMillis(),
        deadIp to "github.com",
        deadIp to "codeload.github.com",
        deadIp to "api.github.com",
    )

    @Test
    fun rewritesHostsWhenInjectedIpsAreUnreachable() {
        val rootfs = freshBlockWithDeadIps()

        val injected = GitHubDnsFallback.ensureResolvable(rootfs)

        assertNotNull("块里的 IP 探活不通时应重新注入", injected)
        val text = hostsText(rootfs)
        assertFalse("失效 IP 不该继续留在块里", text.contains(deadIp))
        assertTrue("重注入后仍要覆盖默认域名", text.contains("github.com"))
        assertTrue("块外原有条目不能被清掉", text.contains("127.0.0.1 localhost"))
    }
}
