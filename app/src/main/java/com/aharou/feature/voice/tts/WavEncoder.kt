package com.aharou.feature.voice.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 浮点采样转 16-bit PCM WAV。
 *
 * 播放器与云端克隆接口都只吃文件/字节，不接裸 PCM，所以两头都要这一手。
 */
internal fun toPcm16Wav(samples: FloatArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val out = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII))
    out.putInt(36 + dataSize)
    out.put("WAVE".toByteArray(Charsets.US_ASCII))
    out.put("fmt ".toByteArray(Charsets.US_ASCII))
    out.putInt(16)
    out.putShort(1)
    out.putShort(1)
    out.putInt(sampleRate)
    out.putInt(sampleRate * 2)
    out.putShort(2)
    out.putShort(16)
    out.put("data".toByteArray(Charsets.US_ASCII))
    out.putInt(dataSize)
    for (sample in samples) {
        val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        out.putShort(value.toShort())
    }
    return out.array()
}

private const val WAV_HEADER_SIZE = 44
