package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 纯 JVM 测试：测速器只拼地址与比耗时，不依赖 Android 框架。
class MirrorSpeedProbeTest {
    private val probe = MirrorSpeedProbe()

    @Test
    fun `Alpine 探测地址使用容器真实分支和架构`() {
        assertEquals(
            "http://m.example/alpine/v3.24/main/aarch64/APKINDEX.tar.gz",
            probe.probeUrl("m.example", "alpine", "aarch64", "3.24")
        )
    }

    @Test
    fun `Debian 与 Ubuntu 使用发行版代号且 Ubuntu arm64 走 ports`() {
        assertEquals(
            "http://m.example/debian/dists/trixie/Release",
            probe.probeUrl("m.example", "debian", "arm64", "trixie")
        )
        assertEquals(
            "http://m.example/ubuntu-ports/dists/noble/Release",
            probe.probeUrl("m.example", "ubuntu", "arm64", "noble")
        )
        assertEquals(
            "http://m.example/ubuntu/dists/noble/Release",
            probe.probeUrl("m.example", "ubuntu", "amd64", "noble")
        )
    }

    @Test
    fun `未知发行版不生成探测地址`() {
        assertNull(probe.probeUrl("m.example", "unknown", "arm64", "x"))
    }

    @Test
    fun `选择实际耗时最短的可达镜像`() {
        assertEquals("b.example", probe.fastestOf(listOf("a.example" to 120L, "b.example" to 35L)))
        assertEquals("a.example", probe.fastestOf(listOf("a.example" to 120L)))
        assertNull(probe.fastestOf(emptyList()))
    }

    @Test
    fun `候选数量不会因单个失败而被误判为空`() {
        val measured = listOf("a.example" to -1L, "b.example" to 88L)
        assertTrue(probe.fastestOf(measured) == "b.example")
    }
}
