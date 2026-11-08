package com.aharou.feature.im.data.napcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatAptSourceTest {
    @Test
    fun `三个仓库均使用国内镜像且固定发行版`() {
        val lines = NapCatAptSource.sources.trim().lines()
        assertEquals(listOf(
            "deb http://mirrors.huaweicloud.com/debian trixie main contrib",
            "deb http://mirrors.huaweicloud.com/debian trixie-updates main contrib",
            "deb http://mirrors.huaweicloud.com/debian-security trixie-security main contrib"
        ), lines)
        assertFalse(NapCatAptSource.sources.contains("deb.debian.org"))
        assertFalse(NapCatAptSource.sources.contains("security.debian.org"))
        assertFalse(NapCatAptSource.sources.contains("stable"))
    }

    @Test
    fun `更新与安装使用相同专用源和索引缓存`() {
        val update = NapCatAptSource.arguments("update")
        val install = NapCatAptSource.arguments("install", listOf("xvfb", "ca-certificates"))
        val options = listOf(
            "-o", "Dir::Etc::sourcelist=/napcat/${NapCatAptSource.SOURCE_FILE}",
            "-o", "Dir::Etc::sourceparts=-",
            "-o", "Dir::State::lists=/napcat/${NapCatAptSource.LISTS_DIRECTORY}/",
            "-o", "Dir::Cache::pkgcache=",
            "-o", "Dir::Cache::srcpkgcache="
        )
        assertEquals(options, update.take(options.size))
        assertEquals(options, install.take(options.size))
        assertEquals(listOf("install", "-y", "--no-install-recommends", "xvfb", "ca-certificates"), install.drop(options.size))
    }

    @Test
    fun `索引部分失败必须返回非成功而不是继续装依赖`() {
        assertEquals(listOf("update", "--error-on=any"), NapCatAptSource.arguments("update").takeLast(2))
    }

    @Test
    fun `保留签名认证且不固定架构排除任一官方支持架构`() {
        val config = NapCatAptSource.sources + NapCatAptSource.arguments("install", listOf("xvfb")).joinToString(" ")
        listOf("trusted=yes", "allow-unauthenticated", "AllowInsecureRepositories", "Verify-Peer=false", "arch=")
            .forEach { assertFalse(config.contains(it, ignoreCase = true)) }
        assertTrue(NapCatAptSource.SOURCE_FILE.startsWith("apt/"))
        assertTrue(NapCatAptSource.LISTS_DIRECTORY.startsWith("apt/"))
    }

    @Test
    fun `不接受与安装流程无关的 apt 操作`() {
        assertThrows(IllegalArgumentException::class.java) { NapCatAptSource.arguments("upgrade") }
        assertThrows(IllegalArgumentException::class.java) { NapCatAptSource.arguments("update", listOf("xvfb")) }
    }
}
