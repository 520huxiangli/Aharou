package com.aharou.feature.agent.domain.skill

import com.aharou.testutil.TestFileAccessProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillImporterTest {

    private val provider = TestFileAccessProvider()

    private fun tempRoot(): File = Files.createTempDirectory("skill-import-test").toFile()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun importZip(root: File, bytes: ByteArray, existing: Set<String> = emptySet(), fallback: String = "archive") =
        SkillImporter.importArchive(provider, root.absolutePath, existing, ByteArrayInputStream(bytes), fallback)

    @Test
    fun importMarkdown_writesSkillMd() {
        val root = tempRoot()
        try {
            val report = SkillImporter.importMarkdown(
                provider, root.absolutePath, emptySet(),
                text = "---\nname: pdf-report\ndescription: 生成报告\n---\n正文步骤",
                fallbackName = "ignored"
            )

            assertEquals(listOf("pdf-report"), report.imported)
            val file = File(root, "pdf-report/SKILL.md")
            assertTrue(file.exists())
            val parsed = SkillParser.parse(provider, File(root, "pdf-report").absolutePath)!!
            assertEquals("pdf-report", parsed.name)
            assertEquals("生成报告", parsed.description)
            assertEquals("正文步骤", parsed.instructions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importMarkdown_usesFallbackNameWhenFrontmatterMissing() {
        val root = tempRoot()
        try {
            val report = SkillImporter.importMarkdown(provider, root.absolutePath, emptySet(), "只有正文", "from-file")
            assertEquals(listOf("from-file"), report.imported)
            assertTrue(File(root, "from-file/SKILL.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importMarkdown_rejectsInvalidNameAndConflictAndEmpty() {
        val root = tempRoot()
        try {
            val invalid = SkillImporter.importMarkdown(
                provider, root.absolutePath, emptySet(),
                "---\nname: a/b\n---\n正文", "x"
            )
            assertEquals(SkillImportError.INVALID_NAME, invalid.fatal)

            val conflict = SkillImporter.importMarkdown(
                provider, root.absolutePath, setOf("taken"),
                "---\nname: taken\n---\n正文", "x"
            )
            assertEquals(SkillImportError.NAME_CONFLICT, conflict.fatal)

            val empty = SkillImporter.importMarkdown(provider, root.absolutePath, emptySet(), "", "blank")
            assertEquals(SkillImportError.EMPTY_CONTENT, empty.fatal)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_singleSkillKeepsExtraFiles() {
        val root = tempRoot()
        try {
            val bytes = zipOf(
                "wrapper/my-skill/SKILL.md" to "---\nname: my-skill\ndescription: d\n---\n正文",
                "wrapper/my-skill/run.py" to "print(1)"
            )
            val report = importZip(root, bytes)

            assertEquals(listOf("my-skill"), report.imported)
            assertTrue(File(root, "my-skill/SKILL.md").exists())
            assertTrue(File(root, "my-skill/run.py").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_multipleSkills() {
        val root = tempRoot()
        try {
            val bytes = zipOf(
                "skills/a/SKILL.md" to "---\nname: a\n---\nA",
                "skills/b/SKILL.md" to "---\nname: b\n---\nB",
                "README.md" to "not a skill"
            )
            val report = importZip(root, bytes)

            assertEquals(listOf("a", "b"), report.imported.sorted())
            assertTrue(File(root, "a/SKILL.md").exists())
            assertTrue(File(root, "b/SKILL.md").exists())
            assertFalse(File(root, "README.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_nestedSkillNotDuplicatedInParent() {
        val root = tempRoot()
        try {
            val bytes = zipOf(
                "outer/SKILL.md" to "---\nname: outer\n---\nO",
                "outer/helper.txt" to "keep",
                "outer/inner/SKILL.md" to "---\nname: inner\n---\nI",
                "outer/inner/extra.txt" to "inner-only"
            )
            val report = importZip(root, bytes)

            assertEquals(listOf("inner", "outer"), report.imported.sorted())
            assertTrue(File(root, "outer/helper.txt").exists())
            // inner 的额外文件只落在 inner，不出现在 outer 里
            assertTrue(File(root, "inner/extra.txt").exists())
            assertFalse(File(root, "outer/inner/extra.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_rejectsZipSlipEntries() {
        val root = tempRoot()
        try {
            val bytes = zipOf(
                "good/SKILL.md" to "---\nname: good\n---\nG",
                "../evil.txt" to "pwned"
            )
            val report = importZip(root, bytes)

            assertEquals(listOf("good"), report.imported)
            assertFalse(File(root.parentFile, "evil.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_conflictIsSkippedNotOverwritten() {
        val root = tempRoot()
        try {
            File(root, "dup").mkdirs()
            File(root, "dup/SKILL.md").writeText("---\nname: dup\n---\noriginal")
            val bytes = zipOf("dup/SKILL.md" to "---\nname: dup\n---\nnew")
            val report = importZip(root, bytes, existing = setOf("dup"))

            assertTrue(report.imported.isEmpty())
            assertEquals(SkillImportError.NAME_CONFLICT, report.failures.single().error)
            assertEquals("---\nname: dup\n---\noriginal", File(root, "dup/SKILL.md").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_noSkillReportsFatal() {
        val root = tempRoot()
        try {
            val report = importZip(root, zipOf("README.md" to "x", "src/main.kt" to "y"))
            assertEquals(SkillImportError.NO_SKILL_FOUND, report.fatal)
            assertTrue(report.imported.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importArchive_invalidArchiveReportsFatal() {
        val root = tempRoot()
        try {
            val report = importZip(root, "definitely not a zip".toByteArray())
            assertEquals(SkillImportError.INVALID_ARCHIVE, report.fatal)
        } finally {
            root.deleteRecursively()
        }
    }
}
