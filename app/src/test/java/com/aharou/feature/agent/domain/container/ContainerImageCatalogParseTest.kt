package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 清单解析入口 [ContainerImageCatalog.parseContainerImageCatalog]（纯 JVM，不依赖 Robolectric）。
 *
 * 回归点：这处解析是从 `load()` 的三级回退里抽出来的公共入口，容错口径必须与原实现一致——
 * 非法输入返回 null 而不抛异常，空清单解析成功（是否采纳由各级回退自己决定，语义不在这里改）。
 * 内置 assets 是最后一档兜底，条目与源表的引用完整性写错会让设置页少一整个发行版或下载到 404。
 */
class ContainerImageCatalogParseTest {

    /** Gradle 单测的工作目录是模块根 app/，直接读真清单。 */
    private val assetFile = File("src/main/assets/container-images.json")

    @Test
    fun `空值_非法JSON_结构不符一律返回null不抛异常`() {
        assertNull(ContainerImageCatalog.parseContainerImageCatalog(null))
        assertNull(ContainerImageCatalog.parseContainerImageCatalog(""))
        assertNull(ContainerImageCatalog.parseContainerImageCatalog("   \n"))
        assertNull(ContainerImageCatalog.parseContainerImageCatalog("{"))
        assertNull(ContainerImageCatalog.parseContainerImageCatalog("这根本不是 JSON"))
        assertNull("顶层是数组不是对象", ContainerImageCatalog.parseContainerImageCatalog("[]"))
        assertNull("images 类型不符", ContainerImageCatalog.parseContainerImageCatalog("""{"images":"nope"}"""))
        assertNull("sources 类型不符", ContainerImageCatalog.parseContainerImageCatalog("""{"sources":[]}"""))
        assertNull("eol 类型不符", ContainerImageCatalog.parseContainerImageCatalog(
            """{"images":[{"id":"x","name":"X","version":"1","path":"p","eol":20000101}]}"""
        ))
    }

    @Test
    fun `空对象与空清单解析成功_条目为空`() {
        val empty = ContainerImageCatalog.parseContainerImageCatalog("{}")
        assertNotNull(empty)
        assertTrue(empty!!.sources.isEmpty())
        assertTrue(empty.images.isEmpty())

        val blankList = ContainerImageCatalog.parseContainerImageCatalog("""{"sources":{},"images":[]}""")
        assertNotNull(blankList)
        assertTrue(blankList!!.images.isEmpty())
    }

    @Test
    fun `未知字段被忽略_带新字段的清单照常解析`() {
        val json = """
            {"sources":{"s":{"name":{"zh":"源"},"distros":{"alpine":"https://x"}}},
             "images":[{"id":"a-1","name":"A","version":"1","path":"p","eol":"2030-01-01",
                        "futureField":42,"nested":{"k":"v"}}]}
        """.trimIndent()
        val data = ContainerImageCatalog.parseContainerImageCatalog(json)!!
        val entry = data.images.single()
        assertEquals("a-1", entry.id)
        assertEquals("2030-01-01", entry.eol)
        assertNull(entry.supportEnds)
    }

    @Test
    fun `缺EOL字段的旧清单_按无数据处理且展示名不变`() {
        val json = """{"sources":{},"images":[{"id":"x","name":"X","version":"1.2","path":"p"}]}"""
        val entry = ContainerImageCatalog.parseContainerImageCatalog(json)!!.images.single()
        assertNull(entry.eol)
        assertNull(entry.supportEnds)
        assertEquals(ContainerImageEolStatus.NO_DATA, entry.eolStatus(LocalDate.of(2026, 10, 20)))
        assertEquals("X 1.2", entry.displayLabel())
    }

    @Test
    fun `内置清单_条目数达标_id唯一_展示名就是名称加空格加版本`() {
        assertTrue("找不到内置清单：${assetFile.absolutePath}", assetFile.isFile)
        val data = ContainerImageCatalog.parseContainerImageCatalog(assetFile.readText())
        assertNotNull("内置清单必须能解析", data)

        val images = data!!.images
        assertTrue("内置清单条目数应 ≥ 12，实际 ${images.size}", images.size >= 12)
        assertEquals("条目 id 必须唯一", images.size, images.map { it.id }.distinct().size)

        for (entry in images) {
            assertEquals("${entry.name} ${entry.version}", entry.displayLabel())
        }
    }

    @Test
    fun `内置清单_源表引用完整`() {
        val data = ContainerImageCatalog.parseContainerImageCatalog(assetFile.readText())!!
        val sourceIds = data.sources.keys
        assertTrue("official 源必须存在", "official" in sourceIds)

        for (entry in data.images) {
            assertTrue("${entry.id} 的 distro 为空", entry.distro.isNotBlank())
            assertTrue("${entry.id} 的 path 为空", entry.path.isNotBlank())
            // Arch/Manjaro 精简包官方没有，只在自建源提供，故要求「至少一个源有非空前缀」而不是必须 official
            assertTrue(
                "${entry.id} 的 distro=${entry.distro} 在任何源的 distros 表里都没有非空前缀",
                data.sources.values.any { it.distros[entry.distro]?.isNotBlank() == true }
            )
            assertTrue("${entry.id} 缺 abiNames 的 arm64", entry.abiNames.containsKey("arm64"))
            // 只在 official 源提供的条目要求 x86_64；Arch/Manjaro 只有 aarch64 的 rootfs
            if (data.sources.getValue("official").distros[entry.distro]?.isNotBlank() == true) {
                assertTrue("${entry.id} 缺 abiNames 的 x86_64", entry.abiNames.containsKey("x86_64"))
            }
            for (sourceId in entry.paths.keys) {
                assertTrue("${entry.id} 的 paths 引用了 sources 表里没有的源 $sourceId", sourceId in sourceIds)
            }
        }
    }

    @Test
    fun `内置清单_EOL字段形状可控`() {
        val images = ContainerImageCatalog.parseContainerImageCatalog(assetFile.readText())!!.images
        assertTrue(
            "真清单里至少应有一条带常规支持结束日，否则新字段没接上",
            images.any { parseCatalogDate(it.eol) != null }
        )
        for (entry in images) {
            val eol = parseCatalogDate(entry.eol)
            if (entry.eol != null) {
                assertNotNull("${entry.id} 的 eol=${entry.eol} 不是 yyyy-MM-dd", eol)
            }
            val supportEnds = parseCatalogDate(entry.supportEnds)
            if (supportEnds != null && eol != null) {
                assertTrue(
                    "${entry.id} 的延伸支持结束日 $supportEnds 早于常规支持结束日 $eol",
                    !supportEnds.isBefore(eol)
                )
            } else if (supportEnds != null) {
                fail("${entry.id} 有延伸支持结束日却没有可解析的常规支持结束日")
            }
            // 兜底：任何条目都必须能算出状态（不抛异常）
            entry.eolStatus(LocalDate.of(2026, 10, 20))
        }
    }
}
