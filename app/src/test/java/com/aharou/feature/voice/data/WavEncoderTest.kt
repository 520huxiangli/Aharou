package com.aharou.feature.voice.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WavEncoder：把 [-1,1] 归一化 PCM 包成 44 字节 RIFF/WAVE 头 + 16-bit 小端数据的 WAV。
 *
 * 纯字节逻辑，不碰 Android。断言的字节布局对齐 WAV 规范，任何一处写错都会让云端
 * `/audio/transcriptions` 拒绝接收（表现为「转录失败」且不报具体字段），所以逐个字段核。
 */
class WavEncoderTest {

    private val headerSize = 44

    private fun ByteArray.intLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    /** 读第 index 个样本，还原成有符号 int16。 */
    private fun ByteArray.sampleAt(index: Int): Int {
        val raw = shortLe(headerSize + index * 2)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    @Test
    fun headerContainsRiffWaveFmtAndDataTags() {
        val wav = WavEncoder.encode(floatArrayOf(0f), 16000)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals("data", wav.ascii(36, 4))
    }

    @Test
    fun fileSizeIsHeaderPlusTwoBytesPerSample() {
        assertEquals(headerSize, WavEncoder.encode(FloatArray(0), 16000).size)
        assertEquals(headerSize + 2, WavEncoder.encode(FloatArray(1), 16000).size)
        assertEquals(headerSize + 2000, WavEncoder.encode(FloatArray(1000), 16000).size)
    }

    @Test
    fun riffAndDataChunkSizesMatchSampleCount() {
        val wav = WavEncoder.encode(FloatArray(500), 16000)
        // RIFF chunk 大小 = 文件总长 - 8 字节 = 36 + data
        assertEquals(36 + 500 * 2, wav.intLe(4))
        assertEquals(500 * 2, wav.intLe(40))
    }

    @Test
    fun fmtChunkDescribes16kMonoPcm16() {
        val wav = WavEncoder.encode(FloatArray(0), 16000)
        assertEquals(16, wav.intLe(16)) // PCM 子块固定 16 字节
        assertEquals(1, wav.shortLe(20)) // 1 = PCM，无压缩
        assertEquals(1, wav.shortLe(22)) // 单声道
        assertEquals(16000, wav.intLe(24))
        assertEquals(16000 * 2, wav.intLe(28)) // 字节率 = 采样率 × 声道 × 位深/8
        assertEquals(2, wav.shortLe(32)) // 块对齐
        assertEquals(16, wav.shortLe(34)) // 位深
    }

    @Test
    fun sampleRateChangeUpdatesSampleRateAndByteRate() {
        val wav = WavEncoder.encode(FloatArray(0), 8000)
        assertEquals(8000, wav.intLe(24))
        assertEquals(8000 * 2, wav.intLe(28))
    }

    @Test
    fun samplesAreEncodedLittleEndianInt16() {
        val wav = WavEncoder.encode(floatArrayOf(0f, 0.5f, -0.5f, 1f), 16000)
        assertEquals(0, wav.sampleAt(0))
        assertEquals(16383, wav.sampleAt(1)) // 0.5 × 32767 截断
        assertEquals(-16383, wav.sampleAt(2))
        assertEquals(32767, wav.sampleAt(3))
        // 小端：最低字节在前。0.5 → 0x3FFF → [0xFF, 0x3F]
        assertEquals(0xFF, wav[headerSize + 2].toInt() and 0xFF)
        assertEquals(0x3F, wav[headerSize + 3].toInt() and 0xFF)
    }

    @Test
    fun negativeFullScaleDoesNotOverflowToPositive() {
        // -1.0 × 32767 = -32767，不会溢出成 +32768；写成 -32768 或正值都会被服务端当爆音
        val wav = WavEncoder.encode(floatArrayOf(-1f), 16000)
        assertEquals(-32767, wav.sampleAt(0))
        assertEquals(0x01, wav[headerSize].toInt() and 0xFF)
        assertEquals(0x80, wav[headerSize + 1].toInt() and 0xFF)
    }

    @Test
    fun outOfRangeSamplesAreClampedToShortRange() {
        val wav = WavEncoder.encode(floatArrayOf(2f, -2f), 16000)
        assertEquals(32767, wav.sampleAt(0))
        assertEquals(-32768, wav.sampleAt(1))
    }

    @Test
    fun emptySamplesStillProduceAValidHeader() {
        val wav = WavEncoder.encode(FloatArray(0), 16000)
        assertEquals(headerSize, wav.size)
        assertEquals(36, wav.intLe(4))
        assertEquals(0, wav.intLe(40))
    }
}
