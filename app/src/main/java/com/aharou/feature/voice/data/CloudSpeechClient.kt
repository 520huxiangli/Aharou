package com.aharou.feature.voice.data

import com.aharou.core.net.AppProxy
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject

/**
 * 云端语音服务客户端：走 OpenAI 兼容的 `/audio/speech`（合成）。
 *
 * 之所以只做这一套协议：它是事实标准，OpenAI、Groq、硅基流动、阿里百炼、各类中转站都兼容或近似兼容，
 * 用户填 baseUrl + key + 模型名即可，无需为每家写一套签名适配（讯飞/百度那种私有鉴权不在范围内）。
 */
@Singleton
internal class CloudSpeechClient @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS)
            // 识别要等整段音频上传 + 服务端推理，合成要等生成整段语音，都放宽到 120s
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 云端语音合成，返回音频字节（MP3）。
     *
     * @param voice 音色名，留空则用服务端默认
     */
    suspend fun synthesize(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        voice: String,
        responseFormat: String = "mp3",
    ): ByteArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("response_format", responseFormat)
            if (voice.isNotBlank()) put("voice", voice)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/speech"))
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                // 失败时响应体是 JSON 错误信息，转成文本便于定位
                throw IOException("合成失败 HTTP ${resp.code}：${String(bytes).take(200)}")
            }
            if (bytes.isEmpty()) throw IOException("合成返回空音频")
            // 服务商故障时可能返回 JSON 错误页而不是音频，按内容类型挡一下
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.startsWith("application/json") || contentType.startsWith("text/")) {
                throw IOException("合成返回的不是音频（$contentType）：${String(bytes).take(200)}")
            }
            bytes
        }
    }

    /**
     * 流式合成：服务商真支持 PCM 流时返回 [SpeechAudio.Pcm]，否则返回整段音频或抛异常。
     *
     * 协议按 OpenAI 官方来：`response_format=pcm`（24kHz / 16bit 小端 / 单声道，裸采样无头）
     * 加 `stream_format=audio`（响应体就是 chunked 音频字节，不是 SSE）。
     * 兼容服务参差：不认这两个字段的回 400，忽略 response_format 的回 mp3/wav/opus。
     * 前者抛 [SpeechStreamUnavailableException]、后者整段读回成 [SpeechAudio.Whole]，
     * 由调用方回退老路径——不在这里替服务商猜。
     */
    suspend fun openSpeechStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        voice: String,
    ): SpeechAudio = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("response_format", "pcm")
            // 官方字段名；不认识它的兼容服务会直接 400，那正是回退要接住的情况
            put("stream_format", "audio")
            if (voice.isNotBlank()) put("voice", voice)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/speech"))
            .header("Authorization", "Bearer $apiKey")
            // 官方 SDK 就带这个头，部分网关按它决定要不要走流式
            .header("Accept", "application/octet-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw SpeechStreamUnavailableException("流式合成请求失败：${e.message}", e)
        }
        if (!response.isSuccessful) {
            val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
            response.close()
            throw SpeechStreamUnavailableException("流式合成 HTTP ${response.code}：${raw.take(200)}")
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw SpeechStreamUnavailableException("流式合成没有响应体")
        }
        val contentType = response.header("Content-Type").orEmpty()
        val input = body.byteStream()
        val head = ByteArray(HEAD_BYTES)
        val headLength = try {
            readAtLeast(input, head, HEAD_MIN)
        } catch (e: IOException) {
            response.close()
            throw SpeechStreamUnavailableException("读取流式响应失败：${e.message}", e)
        }
        if (headLength <= 0) {
            response.close()
            throw SpeechStreamUnavailableException("流式合成返回空响应")
        }
        if (contentType.startsWith("application/json") || contentType.startsWith("text/")) {
            // 有的中转出错也回 200，只是把 JSON 错误塞在流里
            val raw = String(readAll(input, head, headLength))
            response.close()
            throw SpeechStreamUnavailableException("流式合成返回的不是音频（$contentType）：${raw.take(200)}")
        }
        if (isEncodedAudio(contentType, head, headLength)) {
            // 忽略 response_format 的服务商：流式拿不到可直放的裸采样，整段收下交 MediaPlayer
            val bytes = readAll(input, head, headLength)
            response.close()
            call.cancel()
            return@withContext SpeechAudio.Whole(bytes)
        }
        if (startsWith(head, headLength, RIFF)) {
            when (val wav = readWavHeader(input, head, headLength)) {
                is WavParse.Ok -> return@withContext SpeechAudio.Pcm(
                    PcmSpeechStream(
                        call = call,
                        body = body,
                        input = input,
                        pending = wav.pending,
                        sampleRate = wav.sampleRate,
                        channelCount = wav.channels,
                    )
                )
                is WavParse.Failed -> {
                    // 头没解析出来：把已经读到的字节并回整段，别丢掉这次响应再花一次钱
                    val bytes = readAll(input, wav.bytes, wav.length)
                    response.close()
                    call.cancel()
                    return@withContext SpeechAudio.Whole(bytes)
                }
            }
        }
        return@withContext SpeechAudio.Pcm(
            PcmSpeechStream(
                call = call,
                body = body,
                input = input,
                pending = head.copyOfRange(0, headLength),
                // 裸 PCM 的采样率只能从 Content-Type 的 rate= 里取，取不到按 OpenAI 的 24kHz
                sampleRate = parseSampleRate(contentType) ?: DEFAULT_PCM_RATE,
                channelCount = 1,
            )
        )
    }

    /**
     * 拼接端点：baseUrl 可能已含 `/v1`（多数服务商）也可能不含。
     * 含 `audio/` 时视为用户已填完整路径，直接用。
     */
    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/$path")) return base
        if (base.contains("/audio/")) return base
        if (!base.endsWith("/v1")) base += "/v1"
        return "$base/$path"
    }

    private companion object {
        /** 流式响应先读这么多字节做格式判定：够覆盖 WAV 头，又不会明显拖延首包。 */
        const val HEAD_BYTES = 4096

        /** 判定格式至少要看 4 字节，但流可能一次只吐一两个字节，读到这个数就够分类。 */
        const val HEAD_MIN = 4
    }
}

/** 一次合成本该产出的音频：能流就流，服务商不支持流式时整段收下。 */
internal sealed interface SpeechAudio {
    class Pcm(val stream: PcmSpeechStream) : SpeechAudio
    class Whole(val bytes: ByteArray) : SpeechAudio
}

/**
 * 流式 PCM 句柄：分块读、可随时取消。
 *
 * [cancel] 是打断的关键——调用方在另一个线程调它，阻塞中的 read 会立刻抛 IOException，
 * 不然用户在播放中途说话时，这一轮要等到服务端生成完才醒。
 */
internal class PcmSpeechStream(
    private val call: Call,
    private val body: ResponseBody,
    private val input: InputStream,
    /** 判定格式时已经读进来、还没交给播放器的字节（WAV 头之后的第一段数据）。 */
    private val pending: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
) : Closeable {

    private var pendingOffset = 0
    private var closed = false

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pendingOffset < pending.size) {
            val count = minOf(length, pending.size - pendingOffset)
            System.arraycopy(pending, pendingOffset, buffer, offset, count)
            pendingOffset += count
            return count
        }
        return input.read(buffer, offset, length)
    }

    /** 立刻中断阻塞中的 [read]；不是线程安全的关闭，只用来取消 HTTP 调用。 */
    fun cancel() {
        if (closed) return
        runCatching { call.cancel() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { body.close() }
    }
}

/** 流式合成不可用（不支持 PCM/stream_format、HTTP 错误、返回错误页）：调用方应回退整段。 */
internal class SpeechStreamUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private val RIFF = "RIFF".toByteArray()
private val ID3 = "ID3".toByteArray()
private val OGGS = "OggS".toByteArray()
private val FLAC = "fLaC".toByteArray()
private val DATA = "data".toByteArray()
private val FMT = "fmt ".toByteArray()

/** OpenAI 的 PCM 固定 24kHz；其它兼容服务可能不同，以 Content-Type 的 rate= 为准。 */
private const val DEFAULT_PCM_RATE = 24_000

/** WAV 头最长按这个找 data 块，超过就当解析失败整段回退。 */
private const val MAX_WAV_HEADER = 8192

private sealed interface WavParse {
    class Ok(val sampleRate: Int, val channels: Int, val pending: ByteArray) : WavParse

    /** 已读到的字节要带回去，整段交给 MediaPlayer。 */
    class Failed(val bytes: ByteArray, val length: Int) : WavParse
}

private fun readAtLeast(input: InputStream, buffer: ByteArray, min: Int): Int {
    var total = 0
    while (total < min) {
        val read = input.read(buffer, total, buffer.size - total)
        if (read <= 0) break
        total += read
    }
    return total
}

private fun readAll(input: InputStream, prefix: ByteArray, prefixLength: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(prefix, 0, prefixLength)
    input.copyTo(out, 8192)
    return out.toByteArray()
}

private fun startsWith(bytes: ByteArray, length: Int, prefix: ByteArray): Boolean {
    if (length < prefix.size) return false
    for (i in prefix.indices) {
        if (bytes[i] != prefix[i]) return false
    }
    return true
}

private fun indexOf(bytes: ByteArray, length: Int, needle: ByteArray): Int {
    if (needle.isEmpty()) return -1
    for (i in 0..(length - needle.size)) {
        var matched = true
        for (j in needle.indices) {
            if (bytes[i + j] != needle[j]) {
                matched = false
                break
            }
        }
        if (matched) return i
    }
    return -1
}

/**
 * 识别「不是裸 PCM」的编码音频。
 *
 * 先看 Content-Type（最可靠），再嗅魔数；MP3 帧同步字要再核对码率与采样率索引，
 * 否则 PCM 里恰好以 0xFF 开头的采样会被误判成 mp3。
 */
private fun isEncodedAudio(contentType: String, head: ByteArray, length: Int): Boolean {
    val type = contentType.lowercase()
    // 明确标了 pcm 就别再拿魔数瞎猜：裸采样开头恰好撞上 MP3 同步字是可能的
    if (type.contains("pcm")) return false
    if (type.contains("mpeg") || type.contains("mp3") || type.contains("ogg") ||
        type.contains("opus") || type.contains("aac") || type.contains("flac")
    ) {
        return true
    }
    if (startsWith(head, length, ID3) || startsWith(head, length, OGGS) || startsWith(head, length, FLAC)) {
        return true
    }
    if (length >= 3 && (head[0].toInt() and 0xff) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) {
        val third = head[2].toInt() and 0xff
        val bitrateIndex = third ushr 4
        val rateIndex = (third ushr 2) and 0x3
        if (bitrateIndex in 1..14 && rateIndex != 3) return true
    }
    return false
}

private fun parseSampleRate(contentType: String): Int? =
    Regex("rate=(\\d+)").find(contentType)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it in 8_000..192_000 }

/**
 * 从 WAV 头里取采样率/声道，并把 data 块之后的数据留作待播内容。
 *
 * 有些服务商会忽略 response_format 直接回 WAV（本地 llama-server 就是这样），
 * 这种也能边收边播，只是要先去掉文件头。
 */
private fun readWavHeader(input: InputStream, head: ByteArray, headLength: Int): WavParse {
    var buffer = head
    var length = headLength
    var dataIndex = indexOf(buffer, length, DATA)
    while (dataIndex < 0 && length < MAX_WAV_HEADER) {
        if (length >= buffer.size) {
            buffer = buffer.copyOf(minOf(MAX_WAV_HEADER, buffer.size * 2))
        }
        val read = input.read(buffer, length, buffer.size - length)
        if (read <= 0) break
        length += read
        dataIndex = indexOf(buffer, length, DATA)
    }
    if (dataIndex < 0) return WavParse.Failed(buffer, length)
    var sampleRate = DEFAULT_PCM_RATE
    var channels = 1
    val fmtIndex = indexOf(buffer, length, FMT)
    if (fmtIndex >= 0 && fmtIndex + 16 <= length) {
        channels = le16(buffer, fmtIndex + 10).coerceIn(1, 2)
        sampleRate = le32(buffer, fmtIndex + 12).takeIf { it in 8_000..192_000 } ?: DEFAULT_PCM_RATE
    }
    // "data" 之后还有 4 字节的长度字段，再往后才是采样
    val start = dataIndex + DATA.size + 4
    if (start > length) return WavParse.Failed(buffer, length)
    return WavParse.Ok(sampleRate, channels, buffer.copyOfRange(start, length))
}

private fun le16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun le32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
