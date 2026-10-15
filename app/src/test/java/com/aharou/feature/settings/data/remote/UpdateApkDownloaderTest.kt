package com.aharou.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 更新包完整性校验：哈希计算是纯函数，直接对固定字节比对已知 sha256。 */
class UpdateApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sha256Hex_matchesKnownDigestForText() {
        val file = tempFolder.newFile("hello").apply { writeBytes("hello".toByteArray()) }
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256Hex(file)
        )
    }

    @Test
    fun sha256Hex_handlesEmptyFile() {
        val file = tempFolder.newFile("empty")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(file)
        )
    }

    @Test
    fun sha256Hex_readsAcrossBufferBoundary() {
        // 128KB 全零：跨过内部 64KB 缓冲边界，验证分块读取没有漏字节或重复计数
        val file = tempFolder.newFile("zeros").apply { writeBytes(ByteArray(128 * 1024)) }
        assertEquals(
            "fa43239bcee7b97ca62f007cc68487560a39e19f74f3dde7486db3f98df8e471",
            sha256Hex(file)
        )
    }
}
