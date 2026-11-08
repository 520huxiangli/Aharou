package com.aharou.feature.im.data.napcat

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NapCatRootfsArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `预装gzip的点斜杠根层和硬链接正常保留`() {
        val entries = normalized(gzip = true, prefix = "./")
        assertEquals(listOf("usr/bin/tool", "usr/bin/tool-copy"), entries.map { it.name })
        assertEquals("tool payload", entries.first().content)
        assertEquals("usr/bin/tool", entries.last().hardLink)
    }

    @Test
    fun `预装gzip首项根隐藏文件不会被误认成外包目录`() {
        val entries = normalized(gzip = true, prefix = "./", hiddenFirst = true)
        assertEquals(listOf(".dockerenv", ".root-meta", "usr/bin/tool", "usr/bin/tool-copy"), entries.map { it.name })
        assertEquals("metadata", entries[1].content)
        assertEquals("tool payload", entries[2].content)
        assertEquals("usr/bin/tool", entries.last().hardLink)
    }

    @Test
    fun `官方xz外包目录及硬链接目标同时剥离前缀`() {
        val entries = normalized(gzip = false, prefix = "./debian-trixie-aarch64/")
        assertEquals(listOf("usr/bin/tool", "usr/bin/tool-copy"), entries.map { it.name })
        assertEquals("tool payload", entries.first().content)
        assertEquals("usr/bin/tool", entries.last().hardLink)
    }

    private fun normalized(gzip: Boolean, prefix: String, hiddenFirst: Boolean = false): List<Entry> {
        val source = temporary.newFile(if (gzip) "source.tar.gz" else "source.tar.xz")
        val compressed = if (gzip) GzipCompressorOutputStream(source.outputStream())
            else XZCompressorOutputStream(source.outputStream())
        TarArchiveOutputStream(compressed).use { tar ->
            if (hiddenFirst) {
                addFile(tar, ".dockerenv", "")
                addFile(tar, ".root-meta", "metadata")
            }
            tar.putArchiveEntry(TarArchiveEntry(prefix))
            tar.closeArchiveEntry()
            addFile(tar, "${prefix}usr/bin/tool", "tool payload")
            val hardLink = TarArchiveEntry("${prefix}usr/bin/tool-copy", TarConstants.LF_LINK).apply {
                linkName = "${prefix}usr/bin/tool"
            }
            tar.putArchiveEntry(hardLink)
            tar.closeArchiveEntry()
        }
        val target = File(temporary.root, "normalized.tar.gz")
        NapCatRootfs.normalize(source, target, preinstalled = gzip)
        assertFalse(File(target.path + ".part").exists())
        return TarArchiveInputStream(GzipCompressorInputStream(target.inputStream())).use { tar ->
            buildList {
                var entry = tar.nextEntry
                while (entry != null) {
                    val current = entry
                    add(Entry(current.name, if (current.isLink) current.linkName else null,
                        if (current.isFile) tar.readBytes().toString(Charsets.UTF_8) else ""))
                    entry = tar.nextEntry
                }
            }
        }
    }

    private fun addFile(tar: TarArchiveOutputStream, name: String, text: String) {
        val content = text.toByteArray(Charsets.UTF_8)
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
        tar.write(content)
        tar.closeArchiveEntry()
    }

    private data class Entry(val name: String, val hardLink: String?, val content: String)
}
