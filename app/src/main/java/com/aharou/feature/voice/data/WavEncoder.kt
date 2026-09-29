package com.aharou.feature.voice.data

import java.io.ByteArrayOutputStream

/**
 * 把 16-bit 单声道 PCM 包成 WAV。
 *
 * 云端转录接口（`/audio/transcriptions`）只接受带容器头的音频文件，而我们录音拿到的是裸 PCM，
 * 补一个 44 字节 RIFF 头即可，无需重新编码——这正是选 PCM16 而非压缩格式的好处。
 */
internal object WavEncoder {

    private const val HEADER_SIZE = 44
    private const val RIFF = 0x52494646 // "RIFF"
    private const val WAVE = 0x57415645 // "WAVE"
    private const val FMT = 0x666d7420 // "fmt "
    private const val DATA = 0x64617461 // "data"

    /** 音频容器格式（1 = PCM，无压缩）。 */
    private const val FORMAT_PCM = 1
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    /**
     * @param samples [-1, 1] 的归一化样本
     * @param sampleRate 采样率（识别用 16000）
     */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(HEADER_SIZE + dataSize)

        // RIFF chunk
        out.writeIntLE(RIFF)
        out.writeIntLE(36 + dataSize) // 后续所有字节数 = 36 + data
        out.writeIntLE(WAVE)

        // fmt subchunk
        out.writeIntLE(FMT)
        out.writeIntLE(16) // PCM 子块固定 16 字节
        out.writeShortLE(FORMAT_PCM)
        out.writeShortLE(CHANNELS)
        out.writeIntLE(sampleRate)
        out.writeIntLE(sampleRate * CHANNELS * BITS_PER_SAMPLE / 8) // 字节率
        out.writeShortLE(CHANNELS * BITS_PER_SAMPLE / 8) // 块对齐
        out.writeShortLE(BITS_PER_SAMPLE)

        // data subchunk
        out.writeIntLE(DATA)
        out.writeIntLE(dataSize)
        for (s in samples) {
            // 负半轴 -1.0 乘 32768 会溢出 Short 范围，钳到 32767 避免爆音
            val v = (s * 32767f).toInt().coerceIn(-32768, 32767)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
