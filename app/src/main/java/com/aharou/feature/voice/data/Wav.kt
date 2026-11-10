package com.aharou.feature.voice.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 把录音样本包成 WAV：识别服务只认带头的文件，裸 PCM 会被当成坏请求。 */
internal object Wav {

    /** 16 kHz 单声道 16 位：语音模型训练用的就是这个采样率。 */
    const val RATE = 16_000

    fun header(dataBytes: Int, sampleRate: Int = RATE, channels: Int = 1): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * 2 * channels)
            putShort((2 * channels).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()

    /** [-1, 1] 的样本转成 16 位小端 PCM，交给识别接口。 */
    fun pcm16(samples: FloatArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (s in samples) putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }.array()

    fun file(samples: FloatArray, sampleRate: Int = RATE): ByteArray {
        val data = pcm16(samples)
        return header(data.size, sampleRate) + data
    }
}
