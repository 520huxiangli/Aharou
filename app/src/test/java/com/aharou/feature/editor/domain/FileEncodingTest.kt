package com.aharou.feature.editor.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

/**
 * FileEncoding：字节 → 字符串的编码判定与解码。
 *
 * 覆盖三条优先级规则：BOM 优先、无 BOM 时严格 UTF-8、失败回退 GBK；以及 BOM 被剥除、
 * 空输入、UTF-16 小端/大端字节序等边界。GBK 用例用 GBK 编码器生成输入，验证「这些字节
 * 不是合法 UTF-8，因此必须走 GBK 回退」这条判定确实生效。
 */
class FileEncodingTest {

    private val gbk: Charset = Charset.forName("GBK")

    @Test
    fun decode_emptyBytes_returnsEmptyUtf8() {
        val decoded = FileEncoding.decode(ByteArray(0))
        assertEquals("", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun decode_utf8Bom_stripsBomAndReportsUtf8() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(Charsets.UTF_8)
        val decoded = FileEncoding.decode(bytes)
        assertEquals("hi", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun decode_utf8BomOnly_yieldsEmptyText() {
        val decoded = FileEncoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertEquals("", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun decode_utf16LeBom_decodesAndStripsBom() {
        val decoded = FileEncoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00))
        assertEquals("A", decoded.text)
        assertEquals(FileEncoding.UTF_16_LE, decoded.encoding)
    }

    @Test
    fun decode_utf16BeBom_decodesAndStripsBom() {
        val decoded = FileEncoding.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41))
        assertEquals("A", decoded.text)
        assertEquals(FileEncoding.UTF_16_BE, decoded.encoding)
    }

    @Test
    fun decode_utf16LeBomOnly_yieldsEmptyText() {
        val decoded = FileEncoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertEquals("", decoded.text)
        assertEquals(FileEncoding.UTF_16_LE, decoded.encoding)
    }

    @Test
    fun decode_plainAscii_detectedAsUtf8() {
        val decoded = FileEncoding.decode("hello world".toByteArray(Charsets.UTF_8))
        assertEquals("hello world", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun decode_validUtf8Multibyte_detectedAsUtf8() {
        val decoded = FileEncoding.decode("中文测试".toByteArray(Charsets.UTF_8))
        assertEquals("中文测试", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun decode_gbkChinese_fallsBackToGbk() {
        val decoded = FileEncoding.decode("中文测试".toByteArray(gbk))
        assertEquals("中文测试", decoded.text)
        assertEquals(FileEncoding.GBK, decoded.encoding)
    }

    @Test
    fun decode_invalidUtf8Bytes_fallsBackToGbk() {
        // 0xFF 在任何位置都不是合法 UTF-8，必须落到 GBK 回退分支。
        val decoded = FileEncoding.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(FileEncoding.GBK, decoded.encoding)
    }

    @Test
    fun displayName_matchesEachEncoding() {
        assertEquals("UTF-8", FileEncoding.UTF_8.displayName)
        assertEquals("UTF-16 LE", FileEncoding.UTF_16_LE.displayName)
        assertEquals("UTF-16 BE", FileEncoding.UTF_16_BE.displayName)
        assertEquals("GBK", FileEncoding.GBK.displayName)
    }

    @Test
    fun bomDefinition_matchesEachEncoding() {
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), FileEncoding.UTF_8.bom)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), FileEncoding.UTF_16_LE.bom)
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), FileEncoding.UTF_16_BE.bom)
        assertNull(FileEncoding.GBK.bom)
    }
}
