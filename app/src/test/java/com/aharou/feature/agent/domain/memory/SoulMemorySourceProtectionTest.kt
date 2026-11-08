package com.aharou.feature.agent.domain.memory

import android.content.Context
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.soul.SoulFile
import com.aharou.core.soul.SoulMDParser
import com.aharou.core.soul.SoulMetadata
import com.aharou.core.soul.SoulStore
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SoulMemorySourceProtectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sources(): List<MemorySource> {
        val globalRoot = tempFolder.newFolder()
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns globalRoot
        val projectRoot = tempFolder.newFolder()
        return listOf(
            GlobalMemorySource(store),
            ProjectMemorySource(projectRoot.path, ExecutionModeHolder(), mockk(), mockk())
        )
    }

    private fun soul(name: String): SoulFile = SoulFile(
        SoulMetadata.DEFAULT.copy(name = name, icon = "data:image/png;base64,dGVzdA==", style = "简洁", lang = "zh"),
        "原人格正文\n"
    )

    @Test
    fun `全局和项目扫描排除各种昵称的人格且保留普通记忆`() {
        for (source in sources()) {
            val root = requireNotNull(source.root).also { it.mkdirs() }
            for ((fileName, name) in listOf(
                "SOUL.md" to "测试人格", "soul.md" to "Aharou", "SoUl.md" to "Custom Assistant"
            )) {
                File(root, fileName).writeText(SoulMDParser.serialize(soul(name)))
            }
            Files.createSymbolicLink(File(root, "alias.md").toPath(), File(root, "SOUL.md").toPath())
            assertTrue(source.listMemories().isEmpty())
            assertTrue(source.editMemory("测试人格", listOf(MemoryEdit("原人格正文", "新正文", false))) is MemoryEditResult.NotFound)
            assertTrue(source.saveMemory("测试人格", "普通摘要", "普通正文", MemoryType.FACT))

            assertEquals(listOf("测试人格"), source.listMemories().map { it.name })
            assertEquals(
                MemoryEditResult.Success,
                source.editMemory("测试人格", listOf(MemoryEdit("普通正文", "普通新正文", false)))
            )
            assertEquals("普通新正文", source.loadContent("测试人格"))
            assertEquals(SoulMDParser.serialize(soul("测试人格")), File(root, "SOUL.md").readText())
        }
    }

    @Test
    fun `全局和项目数据源拒绝删除人格及其链接别名`() {
        for (source in sources()) {
            val root = requireNotNull(source.root).also { it.mkdirs() }
            val original = SoulMDParser.serialize(soul("测试人格"))
            val file = File(root, "SOUL.md").also { it.writeText(original) }
            val alias = File(root, "alias.md")
            Files.createSymbolicLink(alias.toPath(), file.toPath())

            assertFalse(source.deleteMemory("SOUL"))
            assertFalse(source.deleteMemory("alias"))

            assertEquals(original, file.readText())
            assertTrue(Files.isSymbolicLink(alias.toPath()))
            assertFalse(File(root, MemorySource.ARCHIVE_DIR).exists())
            assertTrue(source.saveMemory("ordinary", "摘要", "正文"))
            assertTrue(source.deleteMemory("ordinary"))
            assertFalse(File(root, "ordinary.md").exists())
        }
    }

    @Test
    fun `人格专用保存与重读仍保留头像风格和语言`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        val cached = SoulStore.cachedMetadata.value
        val original = soul("测试人格")
        try {
            SoulStore.save(context, original)
            assertEquals(original, SoulStore.load(context))
            val updated = original.copy(body = "新的人格正文\n")
            SoulStore.save(context, updated)
            SoulStore.ensureExists(context)
            SoulStore.refreshCache(context)

            assertEquals(updated, SoulStore.load(context))
            assertEquals(original.metadata, SoulStore.cachedMetadata.value)
        } finally {
            SoulStore.save(context, SoulFile(cached, ""))
        }
    }
}
