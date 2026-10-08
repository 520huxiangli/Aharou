package com.aharou.feature.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * FileItem：文件类型判定与元数据装配。
 *
 * 这是 sandbox 里少数与 Android 运行时无关的逻辑：类型标志（文本/图片/音视频/PDF/CSV/JSON/
 * APK/压缩包/Office）与图标名都只依赖扩展名，`from(File)` 只依赖 java.io / java.nio。
 * 覆盖边界：无扩展名按文本、大小写不敏感、符号链接解引用、断链被忽略、目录体积归零。
 * 不测 [FileItem.formattedSize]（走 android.text.format.Formatter，需真实 Context）。
 */
class FileItemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(name: String, isDirectory: Boolean = false, modifiedMs: Long = 0L) =
        FileItem(
            file = File(name),
            name = name,
            isDirectory = isDirectory,
            isSymlink = false,
            size = 0L,
            modifiedMs = modifiedMs,
        )

    // ---------- iconRes ----------

    @Test
    fun iconRes_directoryIsFolder() {
        assertEquals("folder", item("docs", isDirectory = true).iconRes)
    }

    @Test
    fun iconRes_mapsKnownExtensions() {
        assertEquals("text", item("a.txt").iconRes)
        assertEquals("terminal", item("a.sh").iconRes)
        assertEquals("code", item("a.py").iconRes)
        assertEquals("image", item("a.png").iconRes)
        assertEquals("audio", item("a.mp3").iconRes)
        assertEquals("video", item("a.mp4").iconRes)
        assertEquals("archive", item("a.zip").iconRes)
        assertEquals("pdf", item("a.pdf").iconRes)
        assertEquals("package", item("a.apk").iconRes)
        assertEquals("database", item("a.db").iconRes)
        assertEquals("library", item("a.so").iconRes)
    }

    @Test
    fun iconRes_unknownExtension_isFile() {
        assertEquals("file", item("a.xyz").iconRes)
    }

    @Test
    fun iconRes_isCaseInsensitive() {
        assertEquals("code", item("A.PY").iconRes)
        assertEquals("image", item("B.PNG").iconRes)
    }

    // ---------- 类型标志 ----------

    @Test
    fun isTextFile_coversTextLikeExtensions() {
        assertTrue(item("a.txt").isTextFile)
        assertTrue(item("a.md").isTextFile)
        assertTrue(item("a.sh").isTextFile)
        assertTrue(item("a.py").isTextFile)
        assertTrue(item("a.toml").isTextFile)
        assertTrue(item("a.gitignore").isTextFile)
    }

    @Test
    fun isTextFile_extensionlessCountsAsText() {
        assertTrue(item("Makefile").isTextFile)
    }

    @Test
    fun isTextFile_binaryExtensionsAreNotText() {
        assertFalse(item("a.png").isTextFile)
        assertFalse(item("a.mp4").isTextFile)
        assertFalse(item("a.zip").isTextFile)
    }

    @Test
    fun isImageFile_coversRasterFormatsButNotSvg() {
        assertTrue(item("a.png").isImageFile)
        assertTrue(item("a.jpg").isImageFile)
        assertTrue(item("a.jpeg").isImageFile)
        assertTrue(item("a.gif").isImageFile)
        assertTrue(item("a.bmp").isImageFile)
        assertTrue(item("a.webp").isImageFile)
        assertTrue(item("a.ico").isImageFile)
        assertTrue(item("A.JPEG").isImageFile)
        assertFalse(item("a.svg").isImageFile)
    }

    @Test
    fun isPreviewable_isTextOrImageOnly() {
        assertTrue(item("a.txt").isPreviewable)
        assertTrue(item("a.png").isPreviewable)
        assertFalse(item("a.mp4").isPreviewable)
        assertFalse(item("a.pdf").isPreviewable)
        assertFalse(item("a.zip").isPreviewable)
    }

    @Test
    fun remainingTypeFlags_matchTheirExtensions() {
        assertTrue(item("a.md").isMarkdownFile)
        assertTrue(item("a.mkd").isMarkdownFile)
        assertFalse(item("a.txt").isMarkdownFile)

        assertTrue(item("a.html").isHtmlFile)
        assertTrue(item("a.xhtml").isHtmlFile)

        assertTrue(item("a.mp3").isAudioFile)
        assertFalse(item("a.mp4").isAudioFile)
        assertTrue(item("a.mp4").isVideoFile)
        assertTrue(item("a.pdf").isPdfFile)

        assertTrue(item("a.csv").isCsvFile)
        assertTrue(item("a.tsv").isCsvFile)
        assertTrue(item("a.json").isJsonFile)
        assertFalse(item("a.txt").isJsonFile)
    }

    @Test
    fun apkArchiveAndOfficeFlags_areDistinct() {
        // APK 先于压缩包判定（调用点用 isApkFile 优先），故两标志同时为真属预期
        assertTrue(item("a.apk").isApkFile)
        assertTrue(item("a.apks").isApkFile)
        assertTrue(item("a.xapk").isApkFile)
        assertTrue(item("a.apk").isArchiveFile)

        assertTrue(item("a.zip").isArchiveFile)
        assertTrue(item("a.jar").isArchiveFile)
        assertTrue(item("a.aar").isArchiveFile)
        // rar 在 iconRes 里算压缩包，但不在可浏览的归档集合内
        assertFalse(item("a.rar").isArchiveFile)

        assertTrue(item("a.docx").isOfficeFile)
        assertTrue(item("a.odt").isOfficeFile)
        assertFalse(item("a.pdf").isOfficeFile)
    }

    // ---------- from(File) ----------

    @Test
    fun from_nonexistentFile_returnsNull() {
        assertNull(FileItem.from(File(tmp.root, "nope.txt")))
    }

    @Test
    fun from_regularFile_reportsSizeAndTimestamp() {
        val file = File(tmp.root, "a.txt").apply { writeText("hello") }

        val item = FileItem.from(file)!!

        assertEquals("a.txt", item.name)
        assertFalse(item.isDirectory)
        assertFalse(item.isSymlink)
        assertEquals(5L, item.size)
        assertEquals(file.lastModified(), item.modifiedMs)
    }

    @Test
    fun from_directory_reportsZeroSize() {
        val dir = tmp.newFolder("d")

        val item = FileItem.from(dir)!!

        assertTrue(item.isDirectory)
        assertEquals(0L, item.size)
    }

    @Test
    fun from_symlinkToFile_resolvesTargetForFlagAndSize() {
        val target = File(tmp.root, "real.txt").apply { writeText("abcd") }
        val link = File(tmp.root, "link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val item = FileItem.from(link)!!

        assertTrue(item.isSymlink)
        assertFalse(item.isDirectory)
        assertEquals(4L, item.size)
    }

    @Test
    fun from_symlinkToDirectory_isDirectoryTrue() {
        val target = tmp.newFolder("realdir")
        val link = File(tmp.root, "linkdir")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val item = FileItem.from(link)!!

        assertTrue(item.isSymlink)
        assertTrue(item.isDirectory)
        assertEquals(0L, item.size)
    }

    @Test
    fun from_brokenSymlink_isIgnoredBecauseExistenceFollowsLink() {
        val link = File(tmp.root, "broken")
        Files.createSymbolicLink(link.toPath(), File(tmp.root, "missing").toPath())

        assertNull(FileItem.from(link))
    }

    // ---------- formattedDate ----------

    @Test
    fun formattedDate_nonPositiveTimestamp_isEmpty() {
        assertEquals("", item("a.txt", modifiedMs = 0L).formattedDate)
        assertEquals("", item("a.txt", modifiedMs = -5L).formattedDate)
    }

    @Test
    fun formattedDate_yesterday_isLiteralYesterday() {
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        assertEquals("Yesterday", item("a.txt", modifiedMs = yesterday).formattedDate)
    }

    @Test
    fun formattedDate_farPast_fallsBackToShortDate() {
        val longAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        assertTrue(Regex("\\d{4}").containsMatchIn(item("a.txt", modifiedMs = longAgo).formattedDate))
    }
}
