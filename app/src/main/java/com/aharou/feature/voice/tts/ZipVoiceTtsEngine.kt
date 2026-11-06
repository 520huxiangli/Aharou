package com.aharou.feature.voice.tts

import android.app.ActivityManager
import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModels
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本机声音克隆合成（sherpa-onnx ZipVoice）。
 *
 * 合成时要三样东西凑齐才行：模型目录（含 vocos 声码器）、一段参考音频、以及那段音频的逐字文本。
 * 文本对不上音色会明显走样，所以调用方必须先让用户确认过参考文本。
 *
 * native 侧非线程安全，整段合成在 [mutex] 内串行；模型加载一次后常驻，
 * 换的是参考音频而不是模型，所以按 profile 只做轻量缓存。
 */
@Singleton
class ZipVoiceTtsEngine @Inject constructor(
    private val modelManager: VoiceModelManager,
    @param:ApplicationContext private val context: Context,
) {

    private var engine: OfflineTts? = null
    private var loadedDir: String? = null
    private val mutex = Mutex()

    fun isModelReady(): Boolean = modelManager.isReady(VoiceModels.TTS_CLONE)

    /**
     * 用 [profile] 的音色念 [text]，返回 16-bit PCM 的 WAV 字节。
     *
     * 失败抛 [IOException]：模型没下、模型目录损坏都归到这里，由调用方决定回落到云端还是系统引擎。
     */
    suspend fun synthesize(
        text: String,
        profile: LoadedVoiceProfile,
        speed: Float = 1.0f,
    ): ByteArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) throw IOException("合成文本为空")
        mutex.withLock {
            // 124MB 的 decoder 加上声码器，加载后 native 占用不是小数目；
            // 手机整体内存吃紧时系统会直接把进程清掉，所以在动手前先看一眼余量。
            val freeMb = availableMemoryMb()
            if (freeMb in 1 until MIN_LOAD_FREE_MB) {
                throw IOException("可用内存不足（${freeMb}MB），无法加载克隆模型")
            }
            val tts = ensureLoaded() ?: throw IOException("声音克隆模型未就绪")
            val config = GenerationConfig(
                speed = speed,
                referenceAudio = profile.samples,
                referenceSampleRate = profile.profile.sampleRate,
                referenceText = profile.profile.referenceText,
                numSteps = NUM_STEPS,
            )
            val startedAt = System.currentTimeMillis()
            try {
                val audio = tts.generateWithConfig(text, config)
                if (audio.samples.isEmpty()) throw IOException("合成结果为空")
                FileLogger.i(
                    TAG,
                    "合成完成：${text.length} 字 -> ${audio.samples.size} 采样 / ${audio.sampleRate}Hz，" +
                        "耗时 ${System.currentTimeMillis() - startedAt}ms，可用内存 ${availableMemoryMb()}MB",
                )
                toPcm16Wav(audio.samples, audio.sampleRate)
            } finally {
                // 合成完内存还是很紧就主动交回去，宁可下次重新加载也不让系统拿进程开刀。
                if (availableMemoryMb() in 1 until KEEP_FREE_MB) release()
            }
        }
    }

    /** 可用内存（MB）；取不到时返回 -1，由调用方当作「不做判断」。 */
    private fun availableMemoryMb(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.availMem / (1024L * 1024L)).toInt()
    }

    /** 释放 native 资源；下次合成会重新加载。 */
    fun release() {
        engine?.let { runCatching { it.release() } }
        engine = null
        loadedDir = null
    }

    private fun ensureLoaded(): OfflineTts? {
        val dir = modelManager.modelDir(VoiceModels.TTS_CLONE)
        if (!modelManager.isReady(VoiceModels.TTS_CLONE)) {
            FileLogger.w(TAG, "克隆模型未就绪：${dir.absolutePath}")
            return null
        }
        val key = dir.absolutePath
        engine?.let { if (loadedDir == key) return it }
        release()
        return try {
            val startedAt = System.currentTimeMillis()
            val created = OfflineTts(
                assetManager = null,
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = File(dir, "tokens.txt").absolutePath,
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            vocoder = File(dir, VOCOS_FILE).absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                            lexicon = File(dir, "lexicon.txt").absolutePath,
                        ),
                        // 手机上这个模型是纯 CPU 推理，线程数直接决定速度；用满四个大核，
                        // 再多意义不大（模型 decoder 宽度有限，核多了只是在抢内存带宽）。
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                        provider = "cpu",
                        debug = false,
                    ),
                ),
            )
            engine = created
            loadedDir = key
            FileLogger.i(TAG, "克隆合成器加载完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
            created
        } catch (e: Exception) {
            FileLogger.e(TAG, "克隆合成器加载失败", e)
            null
        }
    }

    private companion object {
        const val TAG = "ZipVoiceTtsEngine"

        /** 加载前至少要有这么多可用内存，低于它就宁愿报错也不硬上。 */
        const val MIN_LOAD_FREE_MB = 600

        /** 合成后可用内存低于这个值就释放模型，避免被系统当成内存大户清理。 */
        const val KEEP_FREE_MB = 400
        const val VOCOS_FILE = "vocos_24khz.onnx"

        /**
         * 去噪步数。实测 4 步时 4 个字要 6.1 秒、19 个字要 11.4 秒，每多一步都是一整轮模型前向。
         * 降到 2 步能快一倍，但主观听感已经偏糊，所以宁可慢也不降到 2。
         */
        const val NUM_STEPS = 4
    }
}
