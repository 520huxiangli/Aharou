package com.aharou.feature.settings.presentation.component

import com.aharou.feature.agent.domain.container.ContainerImageCatalog
import com.aharou.feature.agent.domain.container.ContainerImageEntry
import com.aharou.feature.agent.domain.container.ContainerImagePreinstalledSource
import com.aharou.feature.agent.domain.container.sizeBytesFor
import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerImageSizeSourceTest {
    @Test
    fun `所选源有预装产物体积时显示该源大小`() {
        val entry = ContainerImageEntry(
            id = "debian-13", name = "Debian", version = "13", distro = "debian",
            path = "debian-base/{abi}.tar.xz",
            paths = mapOf("aharou" to "debian-{abi}.tar.xz"),
            sizeBytes = 100L,
            preinstalledPaths = mapOf("aharou" to "debian-preinstalled-{abi}.tar.gz"),
            sourceSha256ByAbi = mapOf("aharou" to mapOf("arm64" to "a".repeat(64))),
            preinstalledSources = mapOf("aharou" to ContainerImagePreinstalledSource("general", "1")),
            sourceSizeBytesByAbi = mapOf("aharou" to mapOf("arm64" to 240L))
        )
        assertEquals(240L, entry.sizeBytesFor(ContainerImageCatalog.CURRENT_ABI, "aharou"))
    }

    @Test
    fun `所选源没有独立大小时回落到条目通用体积`() {
        val entry = ContainerImageEntry(
            id = "debian-13", name = "Debian", version = "13", distro = "debian",
            path = "debian-base/{abi}.tar.xz",
            paths = mapOf("aharou" to "debian-{abi}.tar.xz"),
            sizeBytes = 100L
        )
        assertEquals(100L, entry.sizeBytesFor(ContainerImageCatalog.CURRENT_ABI, "aharou"))
        assertEquals(100L, entry.sizeBytesFor(ContainerImageCatalog.CURRENT_ABI, "official"))
    }
}
