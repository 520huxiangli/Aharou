package com.aharou.feature.voice.data

import com.aharou.core.util.FileLogger
import com.aharou.feature.voice.domain.VoiceWakeWord
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 唤醒词检测（流式关键词检测，KWS）。
 *
 * 与 [StreamingAsrEngine] 同构：按模型目录缓存 native 实例、[mutex] 串行化整段会话。
 * 区别在于它只回答「有没有听到唤醒词」，不产出识别文本——模型因此小得多（3.3M 参数），
 * 常驻跑 CPU 的代价可以接受。
 *
 * 单次会话里命中后会自行 [WakeSession.feed] 内部复位，所以调用方可以一直喂到释放，
 * 不必为每一句唤醒重建 stream。
 */
@Singleton
internal class WakeWordEngine @Inject constructor() {

    private var spotter: KeywordSpotter? = null
    private var loadedDir: String? = null

    /** 开一段检测会话；调用方用完必须 [WakeSession.release]，否则后续会话会永久阻塞。 */
    suspend fun startSession(
        modelDir: File,
        keywords: String,
        sampleRate: Int = SAMPLE_RATE,
    ): WakeSession? = withContext(Dispatchers.Default) {
        val lock = sessionMutex
        lock.lock()
        val engine = ensureLoaded(modelDir) ?: run {
            lock.unlock()
            return@withContext null
        }
        val stream = runCatching { engine.createStream(keywords) }.getOrElse {
            FileLogger.e(TAG, "创建唤醒词流失败", it)
            lock.unlock()
            return@withContext null
        }
        if (stream.ptr == 0L) {
            FileLogger.e(TAG, "创建唤醒词流失败：关键词不合法（$keywords）")
            lock.unlock()
            return@withContext null
        }
        WakeSession(engine, stream, sampleRate, lock)
    }

    private fun ensureLoaded(modelDir: File): KeywordSpotter? {
        val key = modelDir.absolutePath
        spotter?.let { if (loadedDir == key) return it }
        release()

        val encoder = File(modelDir, ENCODER_FILE)
        val decoder = File(modelDir, DECODER_FILE)
        val joiner = File(modelDir, JOINER_FILE)
        val tokens = File(modelDir, TOKENS_FILE)
        val keywords = File(modelDir, KEYWORDS_FILE)
        if (listOf(encoder, decoder, joiner, tokens, keywords).any { !it.isFile }) {
            FileLogger.w(TAG, "唤醒词模型文件缺失：$key")
            return null
        }
        return try {
            val startedAt = System.currentTimeMillis()
            val engine = KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 1,
                        provider = "cpu",
                        modelType = "zipformer2",
                        debug = false,
                    ),
                    keywordsFile = keywords.absolutePath,
                    keywordsScore = VoiceWakeWord.SCORE,
                    keywordsThreshold = VoiceWakeWord.THRESHOLD,
                ),
            )
            spotter = engine
            loadedDir = key
            FileLogger.i(TAG, "唤醒词检测器加载完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
            engine
        } catch (e: Exception) {
            FileLogger.e(TAG, "唤醒词检测器加载失败", e)
            null
        }
    }

    /** 释放 native 资源。 */
    fun release() {
        spotter?.let { runCatching { it.release() } }
        spotter = null
        loadedDir = null
    }

    private companion object {
        const val TAG = "WakeWordEngine"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80

        const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val KEYWORDS_FILE = "keywords.txt"

        /** native spotter 与 stream 都不能并发使用。 */
        private val sessionMutex = Mutex()
    }

    /**
     * 一次检测会话。 [feed] 命中后内部自动复位，可继续接收后续音频。
     */
    internal class WakeSession(
        private val engine: KeywordSpotter,
        private val stream: OnlineStream,
        private val sampleRate: Int,
        private val lock: Mutex,
    ) {
        private var released = false

        /**
         * 喂入一块 PCM（[-1, 1] 归一化），返回本次命中的唤醒词显示名；未命中返回空串。
         */
        fun feed(samples: FloatArray): String {
            if (released || samples.isEmpty()) return ""
            var hit = ""
            runCatching {
                stream.acceptWaveform(samples, sampleRate)
                while (engine.isReady(stream)) engine.decode(stream)
                val keyword = engine.getResult(stream).keyword
                if (keyword.isNotBlank()) {
                    hit = keyword
                    // 命中即复位：同一段音频里连喊两次也能各算一次
                    engine.reset(stream)
                }
            }.onFailure { FileLogger.e(TAG, "唤醒词检测失败", it) }
            return hit
        }

        /** 释放会话并交还互斥锁。必须调用。 */
        fun release() {
            if (released) return
            released = true
            runCatching { stream.release() }
            lock.unlock()
        }
    }
}
