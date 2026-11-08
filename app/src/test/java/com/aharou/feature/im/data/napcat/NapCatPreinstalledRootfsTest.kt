package com.aharou.feature.im.data.napcat

import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatPreinstalledRootfsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val armSource = NapCatRootfs.url("aarch64")
    private val x86Source = NapCatRootfs.url("x86_64")
    private val armDigest = NapCatRootfs.sha256("aarch64")
    private val x86Digest = NapCatRootfs.sha256("x86_64")
    private val derivedDigest = "c".repeat(64)
    private val armMirror = "https://cnb.example/base-arm.tar.xz"
    private val preinstalled = "https://cnb.example/preinstalled-arm.tar.gz"

    private fun item(
        architecture: String = "aarch64",
        source: String = armSource,
        url: String = preinstalled,
        digest: String = derivedDigest,
        profile: String = "napcat",
        revision: String = "1"
    ): String = """{
        "architecture":"$architecture","profile":"$profile","recipeRevision":"$revision",
        "source":"$source","url":"$url","sha256":"$digest","sizeBytes":100
    }"""

    private fun manifest(items: String? = item()): NapCatAssetManifest = NapCatAssetManifest.parse("""{
        "schemaVersion":1,"napcatVersion":"v4.18.42","qqVersion":"53644","rootfsVersion":"v4.29.0",
        "assets":[{"name":"base-arm.tar.xz","source":"$armSource","url":"$armMirror","sha256":"$armDigest","sizeBytes":10}]
        ${if (items == null) "" else ",\"preinstalledRootfs\":[$items]"}
    }""")

    @Test
    fun `预装gzip优先且回退原包使用各自摘要`() {
        val candidates = manifest().rootfsCandidates("aarch64", armSource, armDigest)
        assertEquals(listOf(preinstalled, armMirror, armSource), candidates.map { it.url })
        assertEquals(listOf(derivedDigest, armDigest, armDigest), candidates.map { it.sha256 })
        assertEquals(listOf(true, false, false), candidates.map { it.preinstalledRootfs })
        assertEquals(listOf(armMirror, armSource), manifest().candidates(armSource, armDigest).map { it.url })
    }

    @Test
    fun `旧schema1清单保留原始候选`() {
        assertEquals(
            listOf(NapCatDownloadCandidate(armMirror, armDigest), NapCatDownloadCandidate(armSource, armDigest)),
            manifest(null).rootfsCandidates("aarch64", armSource, armDigest)
        )
    }

    @Test
    fun `架构和原始source必须同时精确命中`() {
        assertEquals(
            listOf(NapCatDownloadCandidate(x86Source, x86Digest)),
            manifest().rootfsCandidates("x86_64", x86Source, x86Digest)
        )
        val otherVersion = armSource.replace("v4.29.0", "v4.30.0")
        assertEquals(
            listOf(NapCatDownloadCandidate(otherVersion, armDigest)),
            manifest().rootfsCandidates("aarch64", otherVersion, armDigest)
        )
        val x86Preinstalled = "https://cnb.example/preinstalled-x86.tar.gz"
        val candidates = manifest(item() + "," + item("x86_64", x86Source, x86Preinstalled))
            .rootfsCandidates("x86_64", x86Source, x86Digest)
        assertEquals(x86Preinstalled, candidates.first().url)
        assertTrue(candidates.first().preinstalledRootfs)
        assertFalse(candidates.last().preinstalledRootfs)
    }

    @Test
    fun `未知架构拒绝而不是按x86选择摘要`() {
        assertThrows(IllegalArgumentException::class.java) { NapCatRootfs.url("riscv64") }
        assertThrows(IllegalArgumentException::class.java) { NapCatRootfs.sha256("arm64") }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().rootfsCandidates("unknown", armSource, armDigest)
        }
        assertThrows(IllegalArgumentException::class.java) { manifest(item(architecture = "unknown")) }
    }

    @Test
    fun `预装元数据缺摘要或配方不匹配必须失败`() {
        assertThrows(IllegalArgumentException::class.java) { manifest(item(digest = "")) }
        assertThrows(IllegalArgumentException::class.java) { manifest(item(profile = "general")) }
        assertThrows(IllegalArgumentException::class.java) { manifest(item(revision = "2")) }
        assertThrows(IllegalArgumentException::class.java) { manifest(item(url = armSource)) }
        assertThrows(IllegalArgumentException::class.java) { manifest(item() + "," + item()) }
    }

    @Test
    fun `预装gzip保留marker内容且不追加根目录`() {
        val marker = """{"schemaVersion":1,"profile":"napcat","distro":"debian","abi":"aarch64","recipeRevision":"1"}"""
        val source = createArchive("preinstalled.tar.gz", true, "etc/aharou/preinstalled.json", marker)
        val target = File(temporary.root, "normalized.tar.gz")
        NapCatRootfs.normalize(source, target, preinstalled = true)
        assertArchive(target, "etc/aharou/preinstalled.json", marker)
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `官方xz回退仍剥离原始根目录前缀`() {
        val source = createArchive("official.tar.xz", false, "debian-rootfs/etc/os-release", "ID=debian\n")
        val target = File(temporary.root, "normalized.tar.gz")
        NapCatRootfs.normalize(source, target)
        assertArchive(target, "etc/os-release", "ID=debian\n")
    }

    private fun createArchive(name: String, gzip: Boolean, entryName: String, text: String): File {
        val source = temporary.newFile(name)
        val compressed = if (gzip) GzipCompressorOutputStream(source.outputStream())
            else XZCompressorOutputStream(source.outputStream())
        TarArchiveOutputStream(compressed).use { tar ->
            val content = text.toByteArray(Charsets.UTF_8)
            val entry = TarArchiveEntry(entryName).apply { size = content.size.toLong() }
            tar.putArchiveEntry(entry)
            tar.write(content)
            tar.closeArchiveEntry()
        }
        return source
    }

    private fun assertArchive(file: File, name: String, text: String) {
        TarArchiveInputStream(GzipCompressorInputStream(file.inputStream())).use { tar ->
            assertEquals(name, tar.nextEntry?.name)
            assertEquals(text, tar.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `预装候选也不能绕过原包官方摘要冲突检查`() {
        assertThrows(IOException::class.java) {
            manifest().rootfsCandidates("aarch64", armSource, "d".repeat(64))
        }
    }
}
