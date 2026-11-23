package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.voice.data.CloudSttClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 插件侧语音识别（`Tools.Stt.transcribe`）。
 *
 * 只吃 WAV（PCM）：其它编码要先在端上解码，插件进程里没有那条链路，
 * 与其猜格式解出错音，不如明确说只支持什么。识别本身走用户自己配的云端识别模型，
 * 没有配置时直接报错，不静默返回空串假装「没听清」。
 */
@Singleton
class PluginSttService @Inject constructor(
    private val cloudSttClient: CloudSttClient
) {

    suspend fun transcribe(params: kotlinx.serialization.json.JsonObject): JsonElement {
        val audio = params["audio"] as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "Stt.transcribe 需要 audio 字节数组")
        if (audio.isEmpty()) throw PluginHostException("PARAM_INVALID", "audio 不能为空")
        val bytes = ByteArray(audio.size) { index ->
            ((audio[index] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0).toByte()
        }
        val samples = decodeWavPcm16(bytes)
        if (!cloudSttClient.configured()) {
            throw PluginHostException("STT_NOT_CONFIGURED", "还没有配置语音识别模型，去设置里选一个云端识别模型")
        }
        val text = cloudSttClient.transcribe(samples)
            ?: throw PluginHostException("STT_FAILED", "识别失败：模型没返回结果")
        return buildJsonObject { put("text", text) }
    }

    /** 解析 16-bit PCM 的 WAV；其它位深/编码明确拒绝。 */
    private fun decodeWavPcm16(bytes: ByteArray): FloatArray {
        if (bytes.size < 44) throw PluginHostException("AUDIO_INVALID", "音频太短，不是有效的 WAV")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get(0) != 'R'.code.toByte() || buffer.get(1) != 'I'.code.toByte() ||
            buffer.get(2) != 'F'.code.toByte() || buffer.get(3) != 'F'.code.toByte()
        ) {
            throw PluginHostException("AUDIO_INVALID", "只支持 WAV 格式的音频")
        }
        var cursor = 12
        var bitsPerSample = 0
        var channels = 0
        var dataOffset = -1
        var dataLength = 0
        while (cursor + 8 <= bytes.size) {
            val chunkId = String(bytes, cursor, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(cursor + 4)
            val body = cursor + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) throw PluginHostException("AUDIO_INVALID", "WAV 头不完整")
                    channels = buffer.getShort(body + 2).toInt()
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = minOf(chunkSize, bytes.size - body)
                }
            }
            if (dataOffset >= 0 && bitsPerSample != 0) break
            cursor = body + chunkSize + (chunkSize and 1)
        }
        if (dataOffset < 0 || bitsPerSample == 0) {
            throw PluginHostException("AUDIO_INVALID", "WAV 缺少 fmt 或 data 段")
        }
        if (bitsPerSample != 16) {
            throw PluginHostException("AUDIO_INVALID", "只支持 16-bit PCM 的 WAV（收到 $bitsPerSample-bit）")
        }
        val frameCount = dataLength / 2 / maxOf(1, channels)
        val out = FloatArray(frameCount)
        for (index in 0 until frameCount) {
            val raw = buffer.getShort(dataOffset + index * 2 * maxOf(1, channels))
            out[index] = raw / 32768f
        }
        return out
    }
}
