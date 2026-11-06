package com.aharou.feature.voice.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把用户选中的音频文件解成 16k 单声道 [-1,1] 样本，供克隆参考音频使用。
 *
 * 用 MediaExtractor + MediaCodec 而不是引第三方解码库：手机能播的格式（m4a / mp3 / wav / 微信语音的 amr）
 * 系统解码器都覆盖，装库只为了同样的能力多几 MB。
 *
 * 解码输出一律按 16bit PCM 解释——系统音频解码器实际都吐这个格式，
 * 真遇到 32bit float 的解码器会得到噪音，但那是极少数机型才有的问题，先不为它加复杂度。
 */
@Singleton
class AudioFileDecoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * @param maxSeconds 最多取开头这么多秒：参考音频不是越长越好，太长反而拖慢合成。
     */
    suspend fun decode(
        uri: Uri,
        targetRate: Int,
        maxSeconds: Int = 20,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    trackFormat = format
                    break
                }
            }
            val format = trackFormat ?: throw IOException("文件里没有音轨")
            if (trackIndex < 0) throw IOException("文件里没有音轨")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("未知音频格式")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            if (sourceRate <= 0) throw IOException("音频采样率无效")

            val mono = ArrayList<Float>(sourceRate * 4)
            val limitSamples = sourceRate.toLong() * maxSeconds
            var decodedFrames = 0L

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && decodedFrames < limitSamples) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val input = codec.getInputBuffer(inIndex)
                            val size = if (input == null) -1 else extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            val output = codec.getOutputBuffer(outIndex)
                            if (output != null && bufferInfo.size > 0) {
                                output.position(bufferInfo.offset)
                                output.limit(bufferInfo.offset + bufferInfo.size)
                                val shorts = ShortArray(bufferInfo.size / 2)
                                output.asShortBuffer().get(shorts)
                                appendMono(shorts, channels, mono)
                                decodedFrames += shorts.size / channels
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            FileLogger.i(TAG, "解码输出格式确定：${codec.outputFormat}")
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            if (mono.isEmpty()) throw IOException("没有解出音频数据")
            val samples = FloatArray(mono.size) { mono[it] }
            FileLogger.i(
                TAG,
                "解码完成：${samples.size} 采样点 / ${sourceRate}Hz / $channels 声道，转 ${targetRate}Hz",
            )
            resample(samples, sourceRate, targetRate)
        } finally {
            extractor.release()
        }
    }

    /** 多声道按平均混成单声道，顺带把 16bit 归一化到 [-1,1]。 */
    private fun appendMono(shorts: ShortArray, channels: Int, out: MutableList<Float>) {
        var index = 0
        while (index + channels <= shorts.size) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += shorts[index + channel] / 32768f
            }
            out.add(sum / channels)
            index += channels
        }
    }

    /** 线性插值重采样：参考音频对重采样精度不敏感，够用且没有额外依赖。 */
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val ratio = from.toDouble() / to
        val size = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(size)
        for (i in 0 until size) {
            val position = i * ratio
            val left = position.toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (position - left).toFloat()
            out[i] = samples[left] * (1 - fraction) + samples[right] * fraction
        }
        return out
    }

    private companion object {
        const val TAG = "AudioFileDecoder"

        /** 解码循环里等待缓冲的超时：给足时间，但别让它永远卡住。 */
        const val TIMEOUT_US = 10_000L
    }
}
