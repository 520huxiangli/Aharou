package com.aharou.feature.settings.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageUsageScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun sumsFilesRecursively() {
        val root = temp.newFolder("root")
        write(root, "a.txt", 100)
        write(File(root, "sub/deeper"), "b.bin", 400)

        assertEquals(500L, StorageUsageScanner.dirSize(root))
    }

    /** rootfs 与 node_modules 里到处是符号链接，跟随会重复计数甚至陷入环。 */
    @Test
    fun doesNotFollowSymlinks() {
        val root = temp.newFolder("root")
        val real = File(root, "real").apply { mkdirs() }
        write(real, "payload.bin", 1000)
        Files.createSymbolicLink(File(root, "link").toPath(), real.toPath())

        assertEquals(1000L, StorageUsageScanner.dirSize(root))
    }

    @Test
    fun symlinkTargetItselfIsNotCounted() {
        val root = temp.newFolder("root")
        val real = temp.newFolder("outside")
        write(real, "payload.bin", 700)
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        assertEquals(0L, StorageUsageScanner.dirSize(link))
    }

    @Test
    fun missingPathIsZero() {
        assertEquals(0L, StorageUsageScanner.dirSize(File(temp.root, "nope")))
    }

    @Test
    fun singleFileReturnsItsLength() {
        val file = write(temp.root, "single.bin", 321)
        assertEquals(321L, StorageUsageScanner.dirSize(file))
    }

    @Test
    fun cancellationStopsTraversalEarly() {
        val root = temp.newFolder("root")
        repeat(5) { write(root, "f$it.bin", 1000) }

        val total = StorageUsageScanner.dirSize(root, cancelled = { true })

        // 取消在访问首个文件之后生效，因此最多只累加一个文件。
        assertTrue("提前取消却统计了 $total 字节", total <= 1000L)
    }
}
