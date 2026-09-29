package com.aharou.feature.voice.call

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.voice.data.CloudSpeechClient
import com.aharou.feature.voice.data.SystemTtsPlayer
import com.aharou.feature.voice.data.TtsPlayer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 队列里的一段待播文本，带上它属于哪一轮（用于打断时丢弃）。 */
private data class SpeechSegment(val text: String, val generation: Long)

/**
 * 语音播报队列：把 AI 的流式回复切句后逐段合成、排队播出。
 *
 * 为什么要切句，而不是等整段写完再合成一次：
 *  1. 云端 TTS 普遍有单次长度上限（硅基流动 200 字），整段送去会被静默截断；
 *  2. 首句一出就能开口，不用等整段生成完。
 *
 * 打断靠「代号」：新的一轮回复开口时把 [generation] 自增，队列里还排着的旧段发现代号对不上
 * 就直接丢掉，正在播的那段也被 [TtsPlayer.stop] 掐掉，不会出现两轮回复混在一起念。
 */
@Singleton
internal class SpeechQueue @Inject constructor(
    private val cloudSpeech: CloudSpeechClient,
    private val player: TtsPlayer,
    private val systemTts: SystemTtsPlayer,
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val providerRepository: AIProviderRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<SpeechSegment>(Channel.UNLIMITED)

    private val generation = AtomicLong(0)

    private val _isSpeaking = MutableStateFlow(false)

    /** 是否正在出声（含合成等待）。通话编排据此判断 AI 这一轮说完没有。 */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _pending = MutableStateFlow(0)

    /** 队列里还没处理完的段数。通话编排等它归零再收尾。 */
    val pendingCount: StateFlow<Int> = _pending.asStateFlow()

    private val _currentText = MutableStateFlow("")

    /** 正在念的那段文本；没在念时为空串。悬浮球靠它显示「它正在说什么」。 */
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val worker: Job = scope.launch { consume() }

    /**
     * 追加要播的文本，内部会按句切分。
     *
     * @param interrupt 这是新一轮回复的开头：丢掉队列里还没播的、以及正在播的那段
     */
    fun enqueue(text: String, interrupt: Boolean = false) {
        if (interrupt) stop()
        val current = generation.get()
        val segments = splitComplete(text)
        if (segments.isEmpty()) return
        _pending.value += segments.size
        segments.forEach { queue.trySend(SpeechSegment(it, current)) }
    }

    private var spokenLength = 0

    /**
     * 喂流式累积文本（界面那边 [streamingText] 每帧都在变长）。
     *
     * 只把**新成句**的部分排进队列，已排过的不重复。[finish] = 本轮结束，
     * 把剩下的半句（没等到句末标点）也切出来——不然最后几个字永远念不到。
     */
    fun feed(accumulated: String, interrupt: Boolean = false, finish: Boolean = false) {
        if (interrupt) {
            stop()
        }
        if (spokenLength > accumulated.length) spokenLength = 0
        val rest = accumulated.substring(spokenLength)
        var consumed = 0
        while (true) {
            val cut = findCut(rest.substring(consumed))
            if (cut <= 0) break
            val sentence = rest.substring(consumed, consumed + cut).trim()
            if (sentence.isNotEmpty()) pushSegment(sentence)
            consumed += cut
        }
        if (finish) {
            val tail = rest.substring(consumed).trim()
            if (tail.isNotEmpty()) pushSegment(tail)
            consumed = rest.length
        }
        spokenLength += consumed
    }

    private fun pushSegment(sentence: String) {
        _pending.value += 1
        queue.trySend(SpeechSegment(sentence, generation.get()))
    }

    /** 打断并清空：停掉当前播放，丢弃队列里排着的段。 */
    fun stop() {
        generation.incrementAndGet()
        while (queue.tryReceive().isSuccess) Unit
        player.stop()
        systemTts.stop()
        _pending.value = 0
        _isSpeaking.value = false
        _currentText.value = ""
        spokenLength = 0
    }

    private suspend fun consume() {
        for (segment in queue) {
            try {
                if (segment.generation != generation.get()) continue
                _isSpeaking.value = true
                _currentText.value = segment.text
                val bytes = synthesize(segment.text)
                // 合成期间可能已经被下一轮打断
                if (segment.generation != generation.get()) continue
                if (bytes != null) {
                    player.playAndWait(bytes, tag = "call_${segment.generation}")
                } else {
                    // 没配云端合成（或合成失败）：落回手机自带的引擎，至少能出声
                    systemTts.speakAndWait(segment.text)
                }
                if (segment.generation == generation.get()) {
                    _isSpeaking.value = false
                    _currentText.value = ""
                }
            } finally {
                _pending.value = (_pending.value - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun synthesize(text: String): ByteArray? {
        val providerId = ttsSettings.getTtsProviderId()
        val model = ttsSettings.getTtsModel()
        if (providerId.isBlank() || model.isBlank()) return null
        val provider = runCatching { providerRepository.getProviderById(providerId) }.getOrNull() ?: return null
        val key = provider.firstUsableApiKey
        if (key.isBlank()) return null
        return runCatching {
            cloudSpeech.synthesize(
                baseUrl = provider.baseUrl,
                apiKey = key,
                model = model,
                text = text,
                voice = ttsSettings.getVoice(),
            )
        }.onFailure { FileLogger.w(TAG, "语音合成失败：$text", it) }.getOrNull()
    }

    internal companion object {
        const val TAG = "SpeechQueue"

        /** 云端 TTS 单次长度上限普遍在 200 字左右，这里取四分之一留足余量。 */
        const val MAX_SEGMENT_LENGTH = 50

        val SENTENCE_ENDINGS: Set<Char> = setOf('。', '！', '？', '；', '…', '\n', '!', '?', ';', ':')

        /** 超长又没有句末标点时退而求其次的断点。 */
        val SOFT_BREAKS: Set<Char> = setOf('，', '、', ',', ' ', '—')

        /**
         * 把文本切成一批可立即送合成的句子；返回切不掉的部分（等后续流式增量补齐）。
         */
        fun splitComplete(text: String): List<String> {
            val out = mutableListOf<String>()
            var rest = text
            while (true) {
                val cut = findCut(rest)
                if (cut <= 0) break
                rest.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                rest = rest.substring(cut)
            }
            return out
        }

        /** 返回下一个切点（不含）的下标；0 表示还切不了，得继续攒。 */
        private fun findCut(text: String): Int {
            val limit = minOf(text.length, MAX_SEGMENT_LENGTH)
            var i = 0
            while (i < limit) {
                val c = text[i]
                if (c in SENTENCE_ENDINGS) {
                    // 小数点不是句末：3.14 是一个词
                    if (c == '.' && i > 0 && i + 1 < text.length &&
                        text[i - 1].isDigit() && text[i + 1].isDigit()
                    ) {
                        i++
                        continue
                    }
                    // 连续标点算一次
                    var j = i + 1
                    while (j < text.length && text[j] in SENTENCE_ENDINGS) j++
                    return j
                }
                i++
            }
            // 攒够上限还没有句末标点：退到软断点，再不行硬切——不切会被云端截断
            if (text.length > MAX_SEGMENT_LENGTH) {
                for (j in limit - 1 downTo 1) {
                    if (text[j] in SOFT_BREAKS) return j + 1
                }
                return limit
            }
            return 0
        }
    }
}
