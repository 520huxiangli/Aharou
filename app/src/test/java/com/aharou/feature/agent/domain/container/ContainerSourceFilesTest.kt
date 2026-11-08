package com.aharou.feature.agent.domain.container

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerSourceFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `Debian 生成主仓库与安全仓库且固定代号`() {
        val text = requireNotNull(ContainerSourceFiles.chinaAptSources("debian", "trixie", "arm64", "mirrors.huaweicloud.com"))
        assertTrue(text.contains("URIs: http://mirrors.huaweicloud.com/debian\n"))
        assertTrue(text.contains("Suites: trixie trixie-updates"))
        assertTrue(text.contains("URIs: http://mirrors.huaweicloud.com/debian-security"))
        assertTrue(text.contains("Suites: trixie-security"))
        assertEquals(2, Regex("Types: deb").findAll(text).count())
    }

    @Test
    fun `Ubuntu arm64 走 ports 而 amd64 走主仓库`() {
        val arm = requireNotNull(ContainerSourceFiles.chinaAptSources("ubuntu", "stonking", "arm64", "mirrors.huaweicloud.com"))
        val amd = requireNotNull(ContainerSourceFiles.chinaAptSources("ubuntu", "stonking", "amd64", "mirrors.huaweicloud.com"))
        assertTrue(arm.contains("/ubuntu-ports\n"))
        assertTrue(amd.contains("/ubuntu\n"))
        assertTrue(arm.contains("stonking-security"))
    }

    @Test
    fun `未知发行版保持镜像自带源`() {
        assertNull(ContainerSourceFiles.chinaAptSources("alpine", "v3.24", "arm64", "mirrors.huaweicloud.com"))
        assertNull(ContainerSourceFiles.chinaAptSources("arch", "rolling", "arm64", "mirrors.huaweicloud.com"))
    }

    @Test
    fun `写入 Debian 源时旧源被移到备份目录且新源生效`() {
        val rootfs = folder.newFolder()
        File(rootfs, "etc/apt/sources.list.d").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=debian\nVERSION_CODENAME=trixie\n")
        File(rootfs, "etc/apt/sources.list.d/debian.sources").writeText("URIs: http://deb.debian.org/debian\n")
        File(rootfs, "lib").mkdirs()
        File(rootfs, "lib/ld-linux-aarch64.so.1").writeText("")

        val messages = mutableListOf<String>()
        assertTrue(ContainerSourceFiles.writeChinaMirror(rootfs, "mirrors.huaweicloud.com") { messages += it })

        val written = File(rootfs, "etc/apt/sources.list.d/aharou-mirror.sources").readText()
        assertTrue(written.contains("mirrors.huaweicloud.com/debian"))
        assertTrue(File(rootfs, "etc/apt/mirror-backup/debian.sources").isFile)
        assertTrue(File(rootfs, "etc/apt/sources.list.d/debian.sources").exists().not())
        assertTrue(messages.single().contains("国内 apt 源"))
    }

    @Test
    fun `Alpine 仍按 apk 源处理`() {
        val rootfs = folder.newFolder()
        File(rootfs, "etc/apk").mkdirs()
        File(rootfs, "etc/apk/repositories").writeText("https://dl-cdn.alpinelinux.org/alpine/v3.22/main\n")
        assertTrue(ContainerSourceFiles.writeChinaMirror(rootfs, "mirrors.huaweicloud.com") {})
        val text = File(rootfs, "etc/apk/repositories").readText()
        assertTrue(text.contains("mirrors.huaweicloud.com/alpine/v3.22/main"))
    }

    @Test
    fun `缺少发行版代号时不改写源`() {
        val rootfs = folder.newFolder()
        File(rootfs, "etc/apt").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=debian\n")
        assertEquals(false, ContainerSourceFiles.writeChinaMirror(rootfs, "mirrors.huaweicloud.com") {})
        assertTrue(File(rootfs, "etc/apt/sources.list.d/aharou-mirror.sources").exists().not())
    }
}
