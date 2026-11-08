package com.aharou.feature.agent.domain.container

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerImagePreinstalledTest {
    private val official = "a".repeat(64)
    private val derived = "b".repeat(64)
    private val entry = ContainerImageEntry(
        id = "alpine", name = "Alpine", version = "3.22", path = "base-{abi}.tar.gz",
        sizeBytes = 10,
        paths = mapOf("aharou" to "original-{abi}.tar.gz"),
        preinstalledPaths = mapOf("aharou" to "preinstalled-{abi}.tar.gz"),
        sha256ByAbi = mapOf("arm64" to official),
        sourceSha256ByAbi = mapOf("aharou" to mapOf("arm64" to derived)),
        sourceSizeBytesByAbi = mapOf("aharou" to mapOf("arm64" to 100L)),
        preinstalledSources = mapOf("aharou" to ContainerImagePreinstalledSource("general", "1"))
    )

    @Test
    fun `官方原包与派生来源各用自己的摘要和大小`() {
        assertEquals(official, entry.sha256For("arm64"))
        assertEquals(official, entry.sha256For("arm64", "official"))
        assertEquals(derived, entry.sha256For("arm64", "aharou"))
        assertEquals(10L, entry.sizeBytesFor("arm64"))
        assertEquals(100L, entry.sizeBytesFor("arm64", "aharou"))
    }

    @Test
    fun `预装来源没有摘要时不能借用官方摘要`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(sourceSha256ByAbi = emptyMap()).sha256For("arm64", "aharou")
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.sha256For("x86_64", "aharou")
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.sha256For("riscv64", "aharou")
        }
    }

    @Test
    fun `派生路径缺预装标签或摘要非法都明确失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(preinstalledSources = emptyMap()).sha256For("x86_64", "aharou")
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(sourceSha256ByAbi = mapOf("aharou" to mapOf("arm64" to "invalid")))
                .sha256For("arm64", "aharou")
        }
    }

    @Test
    fun `派生包缺大小时不展示原包大小`() {
        assertEquals(0L, entry.sizeBytesFor("x86_64", "aharou"))
        assertEquals(0L, entry.copy(sourceSizeBytesByAbi = emptyMap()).sizeBytesFor("arm64", "aharou"))
    }

    @Test
    fun `旧清单保持原包镜像摘要和大小兼容`() {
        val legacy = Json.decodeFromString<ContainerImageEntry>(
            """{"id":"a","name":"A","version":"1","path":"p","sizeBytes":10,"sha256ByAbi":{"arm64":"$official"}}"""
        )
        assertTrue(legacy.preinstalledPaths.isEmpty())
        assertTrue(legacy.sourceSha256ByAbi.isEmpty())
        assertTrue(legacy.sourceSizeBytesByAbi.isEmpty())
        assertTrue(legacy.preinstalledSources.isEmpty())
        assertEquals(official, legacy.sha256For("arm64", "aharou"))
        assertNull(legacy.sha256For("x86_64", "aharou"))
        assertEquals(10L, legacy.sizeBytesFor("arm64", "aharou"))
    }

    @Test
    fun `新清单解析来源摘要大小与配方元数据`() {
        val parsed = Json.decodeFromString<ContainerImageEntry>(
            """{"id":"a","name":"A","version":"1","path":"p",
                "paths":{"aharou":"original-{abi}.tar.gz"},
                "preinstalledPaths":{"aharou":"preinstalled-{abi}.tar.gz"},
                "sha256ByAbi":{"arm64":"$official"},
                "sourceSha256ByAbi":{"aharou":{"arm64":"$derived"}},
                "sourceSizeBytesByAbi":{"aharou":{"arm64":100}},
                "preinstalledSources":{"aharou":{"profile":"general","recipeRevision":"1"}}} """
        )
        assertEquals(derived, parsed.sha256For("arm64", "aharou"))
        assertEquals(100L, parsed.sizeBytesFor("arm64", "aharou"))
        assertEquals(ContainerImagePreinstalledSource("general", "1"), parsed.preinstalledSources["aharou"])
        assertEquals("preinstalled-{abi}.tar.gz", parsed.pathFor("arm64", "aharou"))
        assertEquals("p", parsed.pathFor("arm64", "official"))
    }

    @Test
    fun `没有预装路径时新元数据不改变原包的摘要大小与路径`() {
        val original = entry.copy(preinstalledPaths = emptyMap())
        assertEquals(official, original.sha256For("arm64", "aharou"))
        assertEquals(10L, original.sizeBytesFor("arm64", "aharou"))
        assertEquals("original-{abi}.tar.gz", original.pathFor("arm64", "aharou"))
    }

    @Test
    fun `旧客户端忽略预装字段后仍下载原包路径并使用原包摘要`() {
        val raw = Json.encodeToString(ContainerImageEntry.serializer(), entry)
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<LegacyImageEntry>(raw)
        assertEquals("original-{abi}.tar.gz", legacy.paths["aharou"] ?: legacy.path)
        assertEquals(official, legacy.sha256ByAbi["arm64"])
        assertEquals(10L, legacy.sizeBytes)
    }

    @Test
    fun `派生路径缺摘要或元数据时不生成下载路径`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(sourceSha256ByAbi = emptyMap()).pathFor("arm64", "aharou")
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(preinstalledSources = emptyMap()).pathFor("arm64", "aharou")
        }
    }

    @Serializable
    private data class LegacyImageEntry(
        val path: String,
        val paths: Map<String, String> = emptyMap(),
        val sha256ByAbi: Map<String, String> = emptyMap(),
        val sizeBytes: Long = 0
    )
}
