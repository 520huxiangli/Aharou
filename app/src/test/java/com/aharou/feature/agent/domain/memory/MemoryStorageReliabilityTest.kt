package com.aharou.feature.agent.domain.memory

import com.aharou.core.soul.SoulFile
import com.aharou.core.soul.SoulMDParser
import com.aharou.core.soul.SoulMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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

    private fun soulText(name: String): String = SoulMDParser.serialize(
        SoulFile(
            SoulMetadata.DEFAULT.copy(name = name, icon = "data:image/png;base64,dGVzdA==", style = "简洁", lang = "zh"),
            "原人格正文"
        )
    )

    @Test
    fun `后台直写不能覆盖或新建人格文件`() {
        for (fileName in listOf("SOUL.md", "soul.md", "SoUl.md")) {
            val file = File(tempFolder.root, fileName)
            assertFalse(MemoryFileStorage.write(file, "普通记忆"))
            assertFalse(file.exists())
            val original = soulText("测试人格")
            file.writeText(original)

            assertFalse(MemoryFileStorage.write(file, "普通记忆"))
            assertFalse(MemoryFileStorage.write(file, "普通记忆", MemoryFileStorage.versionOf(original)))
            assertEquals(original, file.readText())
        }
        assertEquals(3, tempFolder.root.listFiles()!!.size)
    }

    @Test
    fun `保存人格文件在创建归档之前被拒绝`() {
        val dir = tempFolder.root
        val file = File(dir, "SOUL.md")
        val original = soulText("Aharou")
        file.writeText(original)
        val result = MemoryFileStorage.saveChecked(
            dir, MemoryScope.GLOBAL, "SOUL", "摘要", "新正文", null, null, null, null,
            MemoryFileStorage.versionOf(original)
        )

        assertTrue(result is MemoryWriteResult.Failure)
        assertEquals("PROTECTED_FILE", (result as MemoryWriteResult.Failure).code)
        assertFalse(TempDirSource(dir).saveMemory("SOUL", "摘要", "新正文"))
        assertEquals(original, file.readText())
        assertFalse(File(dir, MemorySource.ARCHIVE_DIR).exists())
    }

    @Test
    fun `新建人格目标不能走普通记忆保存`() {
        val dir = tempFolder.root
        val result = MemoryFileStorage.saveChecked(
            dir, MemoryScope.GLOBAL, "SOUL", "摘要", "新正文", null, null, null, null,
            MemorySource.VERSION_NEW
        )

        assertTrue(result is MemoryWriteResult.Failure)
        assertEquals("PROTECTED_FILE", (result as MemoryWriteResult.Failure).code)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `按中文默认或自定义昵称编辑也不能改写人格文件`() {
        for (name in listOf("测试人格", "Aharou", "Custom Assistant")) {
            val dir = tempFolder.newFolder()
            val file = File(dir, "SOUL.md")
            val original = soulText(name)
            file.writeText(original)
            val source = TempDirSource(dir)
            assertEquals(name, source.listMemories().single().name)

            val result = source.editMemory(
                name, listOf(MemoryEdit("原人格正文", "新人格正文", false)),
                MemoryFileStorage.versionOf(original)
            )

            assertTrue(result is MemoryEditResult.Error)
            assertEquals("SOUL_PROTECTED", (result as MemoryEditResult.Error).code)
            assertEquals(original, file.readText())
            assertFalse(File(dir, MemorySource.ARCHIVE_DIR).exists())
        }
    }

    @Test
    fun `合并成员包含人格文件时不归档也不删除任何成员`() {
        val dir = tempFolder.root
        val soul = File(dir, "SOUL.md")
        val original = soulText("测试人格")
        soul.writeText(original)
        val source = TempDirSource(dir)
        assertTrue(source.saveMemory("other", "摘要", "普通正文", MemoryType.FACT))
        val other = File(dir, "other.md").readText()

        assertFalse(MemoryFileStorage.merge(source.listMemories(), "merged", "合并摘要", "合并正文"))

        assertEquals(original, soul.readText())
        assertEquals(other, File(dir, "other.md").readText())
        assertFalse(File(dir, "merged.md").exists())
        assertFalse(File(dir, MemorySource.ARCHIVE_DIR).exists())
    }

    @Test
    fun `合并目标是人格文件时不归档也不删除来源`() {
        val dir = tempFolder.root
        val source = TempDirSource(dir)
        assertTrue(source.saveMemory("first", "摘要", "正文一", MemoryType.FACT))
        assertTrue(source.saveMemory("second", "摘要", "正文二", MemoryType.FACT))
        val group = source.listMemories()
        val originals = group.associate { memory ->
            val file = requireNotNull(memory.file)
            file to file.readText()
        }

        assertFalse(MemoryFileStorage.merge(group, "SOUL", "摘要", "合并正文"))

        originals.forEach { (file, original) -> assertEquals(original, file.readText()) }
        assertFalse(File(dir, "SOUL.md").exists())
        assertFalse(File(dir, MemorySource.ARCHIVE_DIR).exists())
    }

    @Test
    fun `恢复归档不能覆盖人格且保留归档原件`() {
        val dir = tempFolder.root
        val file = File(dir, "SOUL.md")
        val original = soulText("测试人格")
        file.writeText(original)
        val archiveDir = tempFolder.newFolder(MemorySource.ARCHIVE_DIR)
        val archive = File(archiveDir, "old-soul.md")
        val archived = MemoryParser.format("SOUL", "摘要", "旧正文", MemoryType.FACT)
        archive.writeText(archived)

        assertFalse(TempDirSource(dir).restoreArchive(archive.name))

        assertEquals(original, file.readText())
        assertEquals(archived, archive.readText())
        assertEquals(listOf(archive.name), archiveDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `人格链接别名不能绕过写入保存编辑和恢复保护`() {
        val dir = tempFolder.newFolder("aliases")
        val soul = File(tempFolder.root, "SOUL.md")
        val original = soulText("测试人格")
        soul.writeText(original)
        val alias = File(dir, "alias.md")
        Files.createSymbolicLink(alias.toPath(), soul.toPath())
        assertTrue(isSoulFile(alias))
        assertFalse(MemoryFileStorage.write(alias, "新正文"))
        assertFalse(TempDirSource(dir).saveMemory("alias", "摘要", "新正文"))
        val source = TempDirSource(dir)
        assertEquals(alias, source.listMemories().single().file)
        val edited = source.editMemory("测试人格", listOf(MemoryEdit("原人格正文", "新正文", false)))
        assertTrue(edited is MemoryEditResult.Error)
        assertEquals("SOUL_PROTECTED", (edited as MemoryEditResult.Error).code)
        assertFalse(File(dir, MemorySource.ARCHIVE_DIR).exists())
        val archiveDir = File(dir, MemorySource.ARCHIVE_DIR).also { assertTrue(it.mkdirs()) }
        val archive = File(archiveDir, "alias-backup.md")
        val archived = MemoryParser.format("alias", "摘要", "旧正文", MemoryType.FACT)
        archive.writeText(archived)

        assertFalse(TempDirSource(dir).restoreArchive(archive.name))
        assertEquals(original, soul.readText())
        assertEquals(archived, archive.readText())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertEquals(listOf(archive.name), archiveDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `普通记忆与人格同昵称时仍可保存编辑和恢复`() {
        val dir = tempFolder.root
        val soul = File(dir, "SOUL.md")
        val original = soulText("测试人格")
        soul.writeText(original)
        val ordinaryDir = tempFolder.newFolder()
        val source = TempDirSource(ordinaryDir)
        assertTrue(source.saveMemory("测试人格", "摘要", "普通旧正文", MemoryType.FACT))
        assertEquals(
            MemoryEditResult.Success,
            source.editMemory("测试人格", listOf(MemoryEdit("普通旧正文", "普通新正文", false)))
        )
        assertEquals("普通新正文", source.loadContent("测试人格"))
        val archive = source.listArchive().single()
        assertTrue(source.restoreArchive(archive.fileName))
        assertEquals("普通旧正文", source.loadContent("测试人格"))
        assertEquals(original, soul.readText())
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
