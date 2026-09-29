package com.aharou.feature.voice.data

import com.aharou.core.util.FileLogger
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
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
 * 流式语音识别（zipformer transducer）。
 *
 * 相比之前的非流式 Paraformer，流式的意义在于三件事：
 *  1. 边说边出字，用户能看到识别在动，说错了可以立刻停；
 *  2. 内置端点检测（[ENDPOINT_RULE2_SILENCE] 秒静音即判一句话说完），不必依赖松手；
 *  3. 长句不会攒到最后一口气推理，首字延迟低。
 *
 * 代价是模型更大（int8 量化后约 160MB）、精度略低于非流式——但体积不是本项目的约束。
 *
 * [recognizer] 加载一次约 160MB，按模型目录缓存复用；native 侧非线程安全，[mutex] 串行化
 * 整段识别会话（feed/decode 都在锁内）。
 */
@Singleton
internal class StreamingAsrEngine @Inject constructor() {

    private var recognizer: OnlineRecognizer? = null
    private var loadedDir: String? = null

    /**
     * 开一段识别会话。同一时刻只允许一段（[mutex] 保护），
     * 调用方必须在结束时 [AsrSession.release]，否则后续会话会阻塞。
     */
    suspend fun startSession(modelDir: File, sampleRate: Int = SAMPLE_RATE): AsrSession? = withContext(Dispatchers.Default) {
        val lock = sessionMutex
        lock.lock()
        val engine = ensureLoaded(modelDir)
        if (engine == null) {
            lock.unlock()
            return@withContext null
        }
        AsrSession(engine, engine.createStream(), sampleRate, lock)
    }

    private fun ensureLoaded(modelDir: File): OnlineRecognizer? {
        val key = modelDir.absolutePath
        recognizer?.let { if (loadedDir == key) return it }
        release()

        val encoder = File(modelDir, ENCODER_FILE)
        val decoder = File(modelDir, DECODER_FILE)
        val joiner = File(modelDir, JOINER_FILE)
        val tokens = File(modelDir, TOKENS_FILE)
        if (listOf(encoder, decoder, joiner, tokens).any { !it.isFile }) {
            FileLogger.w(TAG, "流式模型文件缺失：$key")
            return null
        }
        return try {
            val startedAt = System.currentTimeMillis()
            val engine = OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false,
                    ),
                    // rule2：静音满 [ENDPOINT_RULE2_SILENCE] 秒就判一句话结束（默认 1.4s）。
                    // rule1/rule3 关掉——rule1 需要解码出非空结果才生效，短促语气词会被吃掉；
                    // rule3 是「说了 20 秒强制断」，按需再说。
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = ENDPOINT_RULE2_SILENCE, minUtteranceLength = 0f),
                        rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
                    ),
                    enableEndpoint = true,
                ),
            )
            recognizer = engine
            loadedDir = key
            FileLogger.i(TAG, "流式识别器加载完成，耗时 ${System.currentTimeMillis() - startedAt}ms")
            engine
        } catch (e: Exception) {
            FileLogger.e(TAG, "流式识别器加载失败", e)
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
        const val TAG = "StreamingAsrEngine"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val ENDPOINT_RULE2_SILENCE = 1.4f

        const val ENCODER_FILE = "encoder.int8.onnx"
        const val DECODER_FILE = "decoder.onnx"
        const val JOINER_FILE = "joiner.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        /** 整段会话互斥：native recognizer 与 stream 都不能并发使用。 */
        private val sessionMutex = Mutex()
    }

    /**
     * 一次识别会话。调用方每次拿到新音频块就 [feed]，拿到当前累计文本；
     * [isEndpoint] 为 true 表示检测到说话结束。
     */
    internal class AsrSession(
        private val engine: OnlineRecognizer,
        private val stream: OnlineStream,
        private val sampleRate: Int,
        private val lock: Mutex,
    ) {
        private var released = false

        /** 当前累计识别文本（每次 [feed] 后取最新）。 */
        var text: String = ""
            private set

        /**
         * 喂入一块 PCM（[-1, 1] 归一化），返回排空解码器后的最新文本。
         * 释放后调用返回上一次文本。
         */
        fun feed(samples: FloatArray): String {
            if (released || samples.isEmpty()) return text
            runCatching {
                stream.acceptWaveform(samples, sampleRate)
                // 解码到「没东西可解」为止：一块 PCM 可能触发多次解码
                while (engine.isReady(stream)) engine.decode(stream)
                text = engine.getResult(stream).text
            }.onFailure { FileLogger.e(TAG, "流式识别失败", it) }
            return text
        }

        /** 是否检测到一句话结束（静音满阈值）。 */
        fun isEndpoint(): Boolean = !released && runCatching { engine.isEndpoint(stream) }.getOrDefault(false)

        /** 复位流，开始下一句（端点触发后调用，保留同一 recognizer）。 */
        fun reset() {
            if (released) return
            runCatching { engine.reset(stream) }
            text = ""
        }

        /** 通知输入结束并排空残余解码（拿到最后几个字）。 */
        fun finish(): String {
            if (released) return text
            runCatching {
                stream.inputFinished()
                while (engine.isReady(stream)) engine.decode(stream)
                text = engine.getResult(stream).text
            }.onFailure { FileLogger.e(TAG, "流式收尾失败", it) }
            return text
        }

        /** 释放会话并交还互斥锁。必须调用，否则后续识别会永久阻塞。 */
        fun release() {
            if (released) return
            released = true
            runCatching { stream.release() }
            lock.unlock()
        }
    }
}
