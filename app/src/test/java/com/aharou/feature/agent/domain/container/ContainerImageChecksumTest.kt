package com.aharou.feature.agent.domain.container

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载校验与「有新版」判定的纯 JVM 用例。
 *
 * 回归点：清单里的 sha256ByAbi 由同步脚本写入，老版本 App 与旧清单里没有这个字段——
 * 缺字段必须解析成空表（→ 跳过校验），绝不能因为缺字段把合法下载判成校验失败；
 * 下载记录里的版本号在升级前是空串，也必须不参与判定，避免把刚下好的镜像误报成「有新版」。
 *
 * 这里直接用 [Json] 解码而不是走 `ContainerImageCatalog.parseContainerImageCatalog`：
 * 后者在 companion 里读 `android.os.Build.SUPPORTED_ABIS`，纯 JVM 单测里拿到的是 null。
 */
class ContainerImageChecksumTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(raw: String): ContainerImageEntry =
        json.decodeFromString<List<ContainerImageEntry>>("[$raw]").single()

    @Test
    fun `清单带sha256ByAbi_按ABI解析`() {
        val e = entry(
            """{"id":"a","name":"A","version":"1","path":"p",
                "sha256ByAbi":{"arm64":"aa11","x86_64":"bb22"}}"""
        )
        assertEquals(mapOf("arm64" to "aa11", "x86_64" to "bb22"), e.sha256ByAbi)
        assertEquals("aa11", e.sha256For("arm64"))
        assertEquals("bb22", e.sha256For("x86_64"))
    }

    @Test
    fun `sha256For_缺键或空白一律返回null`() {
        val e = entry(
            """{"id":"a","name":"A","version":"1","path":"p",
                "sha256ByAbi":{"arm64":"  ","x86_64":""}}"""
        )
        assertNull("空白哈希要当作没有", e.sha256For("arm64"))
        assertNull("空哈希要当作没有", e.sha256For("x86_64"))
        assertNull("没有该 ABI 的键", e.sha256For("riscv64"))
    }

    @Test
    fun `老清单没有sha256ByAbi_解析为空表`() {
        val e = entry("""{"id":"a","name":"A","version":"1","path":"p"}""")
        assertTrue("缺字段必须是空表，否则老清单会全部校验失败", e.sha256ByAbi.isEmpty())
        assertNull(e.sha256For("arm64"))
    }

    @Test
    fun `hasNewerVersion_空值与相等都不算有新版`() {
        assertFalse("旧记录没有版本号：宁可漏提示也不误报", hasNewerVersion(null, "3.22.6"))
        assertFalse(hasNewerVersion("", "3.22.6"))
        assertFalse(hasNewerVersion("   ", "3.22.6"))
        assertFalse("版本一致", hasNewerVersion("3.22.6", "3.22.6"))
        assertFalse("目录版本为空时判不了", hasNewerVersion("3.22.6", ""))
    }

    @Test
    fun `hasNewerVersion_版本不同即有新版`() {
        assertTrue(hasNewerVersion("3.22.5", "3.22.6"))
        assertTrue("补丁号回退也算不一致（重建的镜像）", hasNewerVersion("3.22.7", "3.22.6"))
    }

    @Test
    fun `displayLabel_传入下载时的版本时以它为准`() {
        val e = entry("""{"id":"a","name":"Alpine","version":"3.22.6","path":"p"}""")
        assertEquals("Alpine 3.22.6", e.displayLabel())
        assertEquals("Alpine 3.22.5", e.displayLabel("3.22.5"))
        assertEquals("空白版本回退到清单版本", "Alpine 3.22.6", e.displayLabel("  "))
    }

    @Test
    fun `内置清单的哈希字段形状可控_Debian不写哈希`() {
        // 哈希要等同步脚本跑一次才落进清单，这里只保证「有哈希就必须是 64 位十六进制」
        val file = File("src/main/assets/container-images.json")
        assertTrue("找不到内置清单：${file.absolutePath}", file.isFile)
        val images = json.decodeFromString<ContainerImageCatalogData>(file.readText()).images
        assertTrue("内置清单条目数应 ≥ 12，实际 ${images.size}", images.size >= 12)

        val hex = "0123456789abcdef"
        for (e in images) {
            for (abi in e.abiNames.keys) {
                val hash = e.sha256For(abi) ?: continue
                assertEquals("${e.id} 的 $abi 哈希长度不对", 64, hash.length)
                assertEquals("${e.id} 的 $abi 哈希不是十六进制", hash.lowercase(), hash.lowercase().filter { it in hex })
            }
        }
        assertTrue(
            "Debian 的产物地址是 Jenkins lastSuccessfulBuild，同版本重跑会换字节，清单里不该写哈希",
            images.filter { it.distro == "debian" }.all { it.sha256ByAbi.isEmpty() }
        )
    }
}
