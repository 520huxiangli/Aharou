package com.aharou.feature.voice.data

import com.aharou.core.util.FileLogger
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 整段离线识别（SenseVoice），负责「把这一句听得更准」。
 *
 * 与 [StreamingAsrEngine] 是分工而不是替代：流式引擎负责边说边出字与判断停顿
 * （它内置端点检测），这里在检测到停顿后，对**完整一句**重新识别一次，取更准的结果去提交。
 *
 * 之所以不能直接拿它替掉流式：SenseVoice 是非自回归架构，必须拿到完整音频才能开始算，
 * 结构上就给不出实时字幕，也判断不了「你说完了没有」。
 *
 * 一个 recognizer 约 230MB，按模型目录缓存复用。
 */
@Singleton
internal class OfflineAsrEngine @Inject constructor() {

    private var recognizer: OfflineRecognizer? = null
    private var loadedDir: String? = null

    /**
     * 识别整句 PCM（[-1, 1] 归一化）。
     *
     * 返回 null 表示不可用/失败，调用方应退回流式结果而不是当作「什么都没听到」。
     */
    suspend fun recognize(modelDir: File, samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String? =
        withContext(Dispatchers.Default) {
            if (samples.isEmpty()) return@withContext null
            val engine = ensureLoaded(modelDir) ?: return@withContext null
            runCatching {
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    runCatching { stream.release() }
                }
            }.getOrElse {
                FileLogger.e(TAG, "离线识别失败", it)
                null
            }
        }

    /** 预热：提前加载识别器（首次要 1~2 秒），避免卡在提交那一刻。 */
    suspend fun warmUp(modelDir: File) = withContext(Dispatchers.Default) {
        ensureLoaded(modelDir)
    }

    private fun ensureLoaded(modelDir: File): OfflineRecognizer? {
        val key = modelDir.absolutePath
        recognizer?.let { if (loadedDir == key) return it }
        release()

        val model = File(modelDir, MODEL_FILE)
        val tokens = File(modelDir, TOKENS_FILE)
        if (!model.isFile || !tokens.isFile) {
            FileLogger.w(TAG, "离线模型文件缺失：$key")
            return null
        }
        return try {
            val startedAt = System.currentTimeMillis()
            val engine = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = LANGUAGE_AUTO,
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false,
                    ),
                ),
            )
            recognizer = engine
            loadedDir = key
            FileLogger.i(TAG, "离线识别器加载完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
            engine
        } catch (e: Exception) {
            FileLogger.e(TAG, "离线识别器加载失败", e)
            null
        }
    }

    /** 释放 native 资源。 */
    fun release() {
        recognizer?.let { runCatching { it.release() } }
        recognizer = null
        loadedDir = null
    }

    private companion object {
        const val TAG = "OfflineAsrEngine"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val LANGUAGE_AUTO = "auto"

        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}
