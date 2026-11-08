package com.aharou.feature.im.data.napcat

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NapCatAssetCatalogTest {
    private val official = "https://official.example/releases/v1/NapCat.Shell.zip"
    private val mirror = "https://cnb.example/releases/v1/NapCat.Shell.zip"
    private val sha256 = "a".repeat(64)

    private fun manifest(source: String = official, digest: String = sha256): String = """
        {
          "schemaVersion": 1,
          "napcatVersion": "v4.18.42",
          "qqVersion": "3.2.34-53644",
          "rootfsVersion": "v4.29.0",
          "assets": [{
            "name": "NapCat.Shell.zip",
            "source": "$source",
            "url": "$mirror",
            "sha256": "$digest",
            "sizeBytes": 123
          }]
        }
    """.trimIndent()

    @Test
    fun `来源匹配时优先CNB且官方备用使用相同摘要`() {
        val candidates = NapCatAssetManifest.parse(manifest()).candidates(official)

        assertEquals(listOf(mirror, official), candidates.map { it.url })
        assertEquals(listOf(sha256, sha256), candidates.map { it.sha256 })
    }

    @Test
    fun `未知版本只用官方来源而不拼接镜像地址`() {
        val unknown = official.replace("/v1/", "/v2/")
        val candidates = NapCatAssetManifest.parse(manifest()).candidates(unknown)

        assertEquals(listOf(NapCatDownloadCandidate(unknown, null)), candidates)
    }

    @Test
    fun `未知版本仍保留调用方提供的官方摘要`() {
        val unknown = official.replace("/v1/", "/v2/")
        val officialDigest = "b".repeat(64)

        assertEquals(
            listOf(NapCatDownloadCandidate(unknown, officialDigest)),
            NapCatAssetManifest.parse(manifest()).candidates(unknown, officialDigest)
        )
    }

    @Test
    fun `仅文件名相同不能选中其他来源的镜像`() {
        val otherSource = official + "?version=other"

        assertEquals(
            listOf(NapCatDownloadCandidate(otherSource, null)),
            NapCatAssetManifest.parse(manifest()).candidates(otherSource)
        )
    }

    @Test
    fun `官方摘要与清单冲突时明确失败`() {
        val exception = assertThrows(IOException::class.java) {
            NapCatAssetManifest.parse(manifest()).candidates(official, "b".repeat(64))
        }

        assertEquals("NapCat asset checksum metadata conflict: NapCat.Shell.zip", exception.message)
    }

    @Test
    fun `摘要比较忽略大小写且保留官方值`() {
        val officialDigest = sha256.uppercase()

        assertEquals(
            listOf(officialDigest, officialDigest),
            NapCatAssetManifest.parse(manifest()).candidates(official, officialDigest).map { it.sha256 }
        )
    }

    @Test
    fun `解析时拒绝无效摘要`() {
        assertThrows(IllegalArgumentException::class.java) {
            NapCatAssetManifest.parse(manifest(digest = "invalid"))
        }
    }

    @Test
    fun `解析时拒绝不支持的清单版本`() {
        assertThrows(IllegalArgumentException::class.java) {
            NapCatAssetManifest.parse(manifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
    }

    @Test
    fun `拒绝重复官方来源而不任意选择`() {
        val text = manifest()
        val start = text.indexOf("{", text.indexOf("\"assets\""))
        val end = text.indexOf("}", start) + 1
        val asset = text.substring(start, end)

        assertThrows(IllegalArgumentException::class.java) {
            NapCatAssetManifest.parse(text.replace(asset, "$asset, $asset"))
        }
    }
}
