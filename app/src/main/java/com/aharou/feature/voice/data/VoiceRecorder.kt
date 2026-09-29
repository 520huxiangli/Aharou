package com.aharou.feature.voice.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 启动录音的结果：区分「没权限」与「设备/参数问题」，好给不同提示。 */
internal enum class RecordStartResult { Started, NoPermission, Failed }

/**
 * 录音：16kHz / 单声道 / PCM16，转为 [-1, 1] 的 FloatArray 交给识别引擎。
 *
 * 音源选 [MediaRecorder.AudioSource.VOICE_RECOGNITION]：系统会为它启用降噪与自动增益，
 * 比默认 MIC 更适合识别（这是它的设计用途）。实时通话/回声消除场景才需要 VOICE_COMMUNICATION。
 */
@Singleton
internal class VoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var buffer: MutableList<Float>? = null

    @Volatile
    private var recording = false

    /** 可选的实时回调节点：每读到一块 PCM 就回调一次（流式识别用）。 */
    @Volatile
    private var chunkListener: ((FloatArray) -> Unit)? = null

    /** 录音是否进行中；用于 UI 状态与防止重复启动。 */
    val isRecording: Boolean get() = recording

    /**
     * 开始录音。已在进行中时返回 [RecordStartResult.Failed]。
     *
     * @param onChunk 每读到一块 PCM（[-1,1] 归一化）就回调一次，用于流式识别。
     *                回调在录音线程上同步执行，实现里不要做耗时操作，也不要直接更新 UI。
     */
    @SuppressLint("MissingPermission")
    fun start(onChunk: ((FloatArray) -> Unit)? = null): RecordStartResult {
        if (recording) return RecordStartResult.Failed
        chunkListener = onChunk
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FileLogger.w(TAG, "缺少录音权限")
            return RecordStartResult.NoPermission
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            FileLogger.e(TAG, "无效的录音缓冲大小：$minBuffer")
            return RecordStartResult.Failed
        }
        // 取最小值的 2 倍：留出余量吸收调度抖动，又不至于让 stop 后还要读很久残留
        val bufferBytes = minBuffer * 2
        val recorder = try {
            AudioRecord(MEDIA_SOURCE, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
        } catch (e: Exception) {
            FileLogger.e(TAG, "创建 AudioRecord 失败", e)
            return RecordStartResult.Failed
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            FileLogger.e(TAG, "AudioRecord 未初始化成功")
            recorder.release()
            return RecordStartResult.Failed
        }

        val samples = ArrayList<Float>(SAMPLE_RATE * 8)
        buffer = samples
        recording = true
        record = recorder

        recorder.startRecording()
        worker = Thread({
            val chunk = ShortArray(bufferBytes / 2)
            try {
                while (recording) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n <= 0) {
                        if (n < 0) FileLogger.w(TAG, "AudioRecord.read 返回 $n")
                        continue
                    }
                    synchronized(samples) {
                        for (i in 0 until n) samples.add(chunk[i] / 32768f)
                    }
                    // 流式识别：把同一块 PCM 交给回调（拷贝一份，避免与累积缓冲共享引用）
                    chunkListener?.let { listener ->
                        val copy = FloatArray(n)
                        for (i in 0 until n) copy[i] = chunk[i] / 32768f
                        listener(copy)
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "录音线程异常", e)
            }
        }, "voice-recorder").apply { isDaemon = true; start() }
        return RecordStartResult.Started
    }

    /**
     * 停止录音并返回采集到的 PCM。
     *
     * @return 归一化到 [-1, 1] 的样本；太短（< [MIN_DURATION_MS]）或全程静音时返回空数组，
     *         调用方据此提示「没听清」而不是把噪声当语音发出去。
     */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        chunkListener = null
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        record = null
        worker?.let { runCatching { it.join(500) } }
        worker = null

        val samples = synchronized(buffer ?: mutableListOf<Float>()) {
            (buffer ?: mutableListOf<Float>()).toFloatArray()
        }
        buffer = null

        val durationMs = samples.size * 1000L / SAMPLE_RATE
        if (durationMs < MIN_DURATION_MS) {
            FileLogger.i(TAG, "录音过短（${durationMs}ms），忽略")
            return FloatArray(0)
        }
        if (peakAmplitude(samples) < MIN_PEAK) {
            FileLogger.i(TAG, "录音近乎静音（峰值 ${"%.4f".format(peakAmplitude(samples))}），忽略")
            return FloatArray(0)
        }
        return samples
    }

    /** 丢弃当前录音，不返回数据（权限被拒 / 用户取消时用）。 */
    fun cancel() {
        if (!recording) return
        recording = false
        chunkListener = null
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        record = null
        worker?.let { runCatching { it.join(300) } }
        worker = null
        buffer = null
    }

    private fun peakAmplitude(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        return peak
    }

    private companion object {
        const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MEDIA_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        /** 短于 300ms 基本是误触，不值得送去识别。 */
        const val MIN_DURATION_MS = 300L

        /** 峰值低于此值视为静音（PCM16 满量程归一化后 0.02 约等于 -34dBFS）。 */
        const val MIN_PEAK = 0.02f
    }
}
