package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 覆盖记忆存储层的数据正确性保证：元数据不丢、写盘失败必须上报、归档失败不得删源条目。
 * 全部走内存数据源 + 临时目录，不依赖 Android 框架。
 */
class MemoryStorageReliabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TempDirSource(private val dir: File) : MemorySource {
        override val root: File get() = dir

        override fun listMemories(): List<Memory> =
            dir.listFiles { file -> file.isFile && file.extension == "md" }
                ?.mapNotNull { MemoryParser.parse(it, MemoryScope.GLOBAL) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()

        override fun loadContent(name: String): String? =
            listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }?.content

        override fun saveMemory(
            name: String, description: String, content: String, type: MemoryType?,
            expiresAt: Long?, pending: Boolean?, keywords: List<String>?
        ): Boolean = MemoryFileStorage.save(
            dir, MemoryScope.GLOBAL, name, description, content, type, expiresAt, pending, keywords
        )

        override fun archiveContent(
            name: String, description: String, content: String, type: MemoryType?,
            keywords: List<String>, expiresAt: Long?, pending: Boolean
        ): Boolean = MemorySource.writeArchiveFile(
            dir, name, description, content, "test", type, keywords, expiresAt, pending
        ) != null

        override fun deleteMemory(name: String): Boolean =
            MemorySource.resolveMemoryFile(dir, name).delete()
    }

    @Test
    fun editMemory_keepsTypeKeywordsExpiresAtAndPending() {
        val source = TempDirSource(tempFolder.root)
        val expiresAt = System.currentTimeMillis() + 86_400_000L
        assertTrue(
            source.saveMemory("demo", "摘要", "old body", MemoryType.IDENTITY, expiresAt, true, listOf("k1", "k2"))
        )

        val result = source.editMemory("demo", listOf(MemoryEdit("old body", "new body", false)))
        assertTrue(result is MemoryEditResult.Success)

        val memory = source.listMemories().first { it.name == "demo" }
        assertEquals("new body", memory.content)
        assertEquals(MemoryType.IDENTITY, memory.type)
        assertEquals(listOf("k1", "k2"), memory.keywords)
        assertEquals(expiresAt, memory.expiresAt)
        assertTrue(memory.pending)
    }

    @Test
    fun archiveContent_thenRestore_keepsTypeKeywordsExpiresAtAndPending() {
        val source = TempDirSource(tempFolder.root)
        val expiresAt = System.currentTimeMillis() + 86_400_000L
        assertTrue(
            source.archiveContent("archived", "摘要", "body", MemoryType.WORKFLOW, listOf("a"), expiresAt, true)
        )

        val archive = source.listArchive().single()
        assertTrue(source.restoreArchive(archive.fileName))

        val memory = source.listMemories().first { it.name == "archived" }
        assertEquals("body", memory.content)
        assertEquals(MemoryType.WORKFLOW, memory.type)
        assertEquals(listOf("a"), memory.keywords)
        assertEquals(expiresAt, memory.expiresAt)
        assertTrue(memory.pending)
    }

    @Test
    fun saveMemory_reportsFalse_whenDiskWriteFails() {
        val blocker = tempFolder.newFile("blocker")
        val source = TempDirSource(File(blocker, "subdir"))
        assertFalse(source.saveMemory("x", "d", "c", null, null, null, null))
    }

    @Test
    fun merge_abortsAndKeepsSources_whenArchivingFails() {
        val dir = tempFolder.root
        val source = TempDirSource(dir)
        assertTrue(source.saveMemory("a1", "d1", "body1", MemoryType.FACT, null, null, listOf("t")))
        assertTrue(source.saveMemory("a2", "d2", "body2", MemoryType.FACT, null, null, listOf("t")))
        val group = source.listMemories().filter { it.name == "a1" || it.name == "a2" }.sortedBy { it.name }

        // 让归档目录无法创建：同名占位成普通文件，归档必然失败。
        File(dir, MemorySource.ARCHIVE_DIR).writeText("block")

        assertFalse(MemoryFileStorage.merge(group, "merged", "合并摘要", "合并正文"))
        assertTrue(File(dir, "a1.md").exists())
        assertTrue(File(dir, "a2.md").exists())
        assertFalse(File(dir, "merged.md").exists())
    }
}
