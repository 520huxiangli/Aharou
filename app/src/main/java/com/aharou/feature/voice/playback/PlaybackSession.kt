package com.aharou.feature.voice.playback

import android.content.Context
import android.os.SystemClock
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.voice.data.CloudSpeechClient
import com.aharou.feature.voice.data.SystemTtsPlayer
import com.aharou.feature.voice.data.TtsCache
import com.aharou.feature.voice.data.TtsPlayer
import com.aharou.feature.voice.data.VoiceAudioSession
import com.aharou.feature.voice.tts.VoiceProfileStore
import com.aharou.feature.voice.tts.ZipVoiceTtsEngine
import com.aharou.feature.voice.tts.localVoiceIdOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 队列里的一段待播文本，带上它属于哪一轮（用于打断时丢弃）。 */
private data class SpeechSegment(val text: String, val generation: Long)

/**
 * 语音合成的总入口：挑音色、查缓存、云端或本机合成。所有播报方共用这一份，但排队与打断不共用
 * （见 [newSession]）。
 */
@Singleton
internal class SpeechPlayback @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cloudSpeech: CloudSpeechClient,
    private val ttsCache: TtsCache,
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val providerRepository: AIProviderRepository,
    private val profileStore: VoiceProfileStore,
    private val localTts: ZipVoiceTtsEngine,
    private val audioSession: VoiceAudioSession,
) {

    /**
     * 一份独立的播报会话：自己的队列、打断代号、半句缓存与播放器。
     *
     * 通话与朗读各持一份，各自打断只影响自己——以前共用一个队列时，点一下朗读就会把
     * 通话正念着的那句掐掉，还会把对方的半句缓冲和播完计数一起清空。
     */
    fun newSession(): PlaybackSession = PlaybackSession(
        player = TtsPlayer(context, audioSession),
        systemTts = SystemTtsPlayer(context),
        synthesize = ::synthesize,
    )

    private suspend fun synthesize(text: String): ByteArray? {
        val voice = ttsSettings.getVoice()
        // 本地克隆音色优先：合成在本机完成，不联网也不占云端额度。
        localVoiceIdOrNull(voice)?.let { id ->
            val loaded = profileStore.load(id)
            if (loaded != null) {
                // 同步过云端的音色优先用云端，失败再落回本机合成。
                val cloudUri = loaded.profile.cloudVoiceUri
                if (cloudUri.isNotBlank()) {
                    val pid = ttsSettings.getTtsProviderId()
                    val cloudModel = ttsSettings.getTtsModel()
                    if (pid.isNotBlank() && cloudModel.isNotBlank()) {
                        val provider = runCatching { providerRepository.getProviderById(pid) }.getOrNull()
                        val apiKey = provider?.firstUsableApiKey.orEmpty()
                        if (provider != null && apiKey.isNotBlank()) {
                            val cloudKey = TtsCache.key(text, cloudUri, cloudModel, provider.baseUrl)
                            ttsCache.get(cloudKey)?.let { file ->
                                runCatching { file.readBytes() }.getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { return it }
                            }
                            val cloudBytes = runCatching {
                                cloudSpeech.synthesize(provider.baseUrl, apiKey, cloudModel, text, cloudUri)
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                FileLogger.w(TAG, "云端克隆合成失败：$text", e)
                            }.getOrNull()
                            if (cloudBytes != null) {
                                ttsCache.put(cloudKey, cloudBytes)
                                return cloudBytes
                            }
                        }
                    }
                }
                val localKey = TtsCache.key(text, voice, LOCAL_TTS_MODEL, LOCAL_TTS_SOURCE)
                ttsCache.get(localKey)?.let { file ->
                    runCatching { file.readBytes() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
                val bytes = runCatching { localTts.synthesize(text, loaded) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        FileLogger.w(TAG, "本地克隆合成失败：$text", e)
                    }
                    .getOrNull()
                if (bytes != null) {
                    ttsCache.put(localKey, bytes)
                    return bytes
                }
            }
        }
        val providerId = ttsSettings.getTtsProviderId()
        val model = ttsSettings.getTtsModel()
        if (providerId.isBlank() || model.isBlank()) return null
        val provider = runCatching { providerRepository.getProviderById(providerId) }.getOrNull() ?: return null
        val key = provider.firstUsableApiKey
        if (key.isBlank()) return null
        val cacheKey = TtsCache.key(text, voice, model, provider.baseUrl)
        // 命中缓存就直接复用本地音频，省一次云端往返
        ttsCache.get(cacheKey)?.let { file ->
            runCatching { file.readBytes() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val bytes = runCatching {
            cloudSpeech.synthesize(
                baseUrl = provider.baseUrl,
                apiKey = key,
                model = model,
                text = text,
                voice = voice,
            )
        }.onFailure { FileLogger.w(TAG, "语音合成失败：$text", it) }.getOrNull() ?: return null
        ttsCache.put(cacheKey, bytes)
        return bytes
    }

    internal companion object {
        const val TAG = "SpeechPlayback"

        /** 云端 TTS 单次长度上限普遍在 200 字左右，这里取四分之一留足余量。 */
        const val MAX_SEGMENT_LENGTH = 50

        /**
         * 每轮首段的长度上限。首段决定什么时候能开口，切短就早出声；
         * 后续段仍按 [MAX_SEGMENT_LENGTH]，免得整段回复碎成一句一顿。
         */
        const val FIRST_SEGMENT_LENGTH = 18

        /** 本地合成的缓存标识：没有 provider/model 可填，用固定串与云端结果区分开。 */
        const val LOCAL_TTS_MODEL = "zipvoice-local"
        const val LOCAL_TTS_SOURCE = "local"

        /** 朗读单条时用的缓存标识，与播报队列共用同一套规则。 */
        const val TAG_READ_ALOUD = "read_aloud"

        val SENTENCE_ENDINGS: Set<Char> = setOf('。', '！', '？', '；', '…', '\n', '!', '?', ';', ':')

        /** 超长又没有句末标点时退而求其次的断点。 */
        val SOFT_BREAKS: Set<Char> = setOf('，', '、', ',', ' ', '—')

        /**
         * 把文本切成一批可立即送合成的句子；返回切不掉的部分（等后续流式增量补齐）。
         */
        fun splitComplete(text: String): List<String> = splitInto(text).first

        /**
         * 切出能立刻送合成的句子，同时把没切出去的尾巴带回去（末尾没标点、也没攒够长度）。
         */
        fun splitInto(text: String): Pair<List<String>, String> =
            splitInto(text, MAX_SEGMENT_LENGTH)

        internal fun splitInto(text: String, firstLimit: Int): Pair<List<String>, String> {
            val out = mutableListOf<String>()
            var rest = text
            var limit = firstLimit
            while (true) {
                val cut = cutIndex(rest, limit)
                if (cut <= 0) break
                rest.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                rest = rest.substring(cut)
                limit = MAX_SEGMENT_LENGTH
            }
            return out to rest
        }

        /** 返回下一个切点（不含）的下标；0 表示还切不了，得继续攒。 */
        internal fun cutIndex(text: String, maxLength: Int = MAX_SEGMENT_LENGTH): Int {
            val limit = minOf(text.length, maxLength)
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
            if (text.length > maxLength) {
                for (j in limit - 1 downTo 1) {
                    if (text[j] in SOFT_BREAKS) return j + 1
                }
                return limit
            }
            return 0
        }
    }
}

/**
 * 一路播报：把流式文本切句后逐段合成、排队播出，并给调用方一份自己的打断代号。
 *
 * 为什么要切句，而不是等整段写完再合成一次：
 *  1. 云端 TTS 普遍有单次长度上限（硅基流动 200 字），整段送去会被静默截断；
 *  2. 首句一出就能开口，不用等整段生成完。
 *
 * 打断靠「代号」：新的一轮回复开口时把 [generation] 自增，队列里还排着的旧段发现代号对不上
 * 就直接丢掉，正在播的那段也被 [TtsPlayer.stop] 掐掉，不会出现两轮回复混在一起念。
 */
internal class PlaybackSession(
    private val player: TtsPlayer,
    private val systemTts: SystemTtsPlayer,
    private val synthesize: suspend (String) -> ByteArray?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<SpeechSegment>(Channel.UNLIMITED)

    /** 已经合成好、排队等播的段；容量够大，合成不用等播放。 */
    private val sounds = Channel<Sound>(Channel.BUFFERED)

    /** 一段的台词与它的声音；[bytes] 为空表示没合成出来，要走系统引擎。 */
    private class Sound(val segment: SpeechSegment, val bytes: ByteArray?)

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
     * 切不出句末标点的那半句：先留着，等本轮结束或下一条消息进来再推。
     * 通话路径每条回复只 [enqueue] 一次，丢了它末句就永远念不到。
     */
    private var pendingTail = ""

    /**
     * 追加要播的文本，内部会按句切分。
     *
     * 上一条消息留下的尾巴要先补播，再排这次的句子——两条消息之间顺序不能颠倒。
     *
     * @param interrupt 这是新一轮回复的开头：丢掉队列里还没播的、以及正在播的那段
     */
    fun enqueue(text: String, interrupt: Boolean = false) {
        if (interrupt) stop()
        val current = generation.get()
        val (segments, rest) = splitWithFirstShort(text)
        val carry = pendingTail
        pendingTail = rest.trim()
        if (carry.isNotEmpty()) pushSegment(carry, current)
        segments.forEach { pushSegment(it, current) }
    }

    /**
     * 本轮回复结束：把最后没等到句末标点的半句也推入队列。
     *
     * 流式朗读靠 [feed] 的 `finish` 收尾；通话路径没有这个入口时，「好的」「已删除」
     * 这类不带标点的末句一个字也念不出来，界面上有字、耳机里却是静音。
     */
    fun finishUtterance() {
        val tail = pendingTail
        pendingTail = ""
        if (tail.isNotEmpty()) pushSegment(tail)
    }

    private var spokenLength = 0

    /**
     * 喂流式累积文本（界面那边 streamingText 每帧都在变长）。
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
            val cut = findCut(rest.substring(consumed), segmentLimitForNext())
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

    /** 单条朗读：播一段已经拿到手的音频，播完回调。与队列共用同一路播放器，互不越界。 */
    fun playOnce(bytes: ByteArray, onCompletion: () -> Unit = {}) {
        stop()
        scope.launch { player.play(bytes, tag = SpeechPlayback.TAG_READ_ALOUD) { onCompletion() } }
    }

    /** 没配云端合成时用手机自带引擎念一段，等它念完。 */
    suspend fun speakSystem(text: String): Boolean = systemTts.speakAndWait(text)

    private fun pushSegment(sentence: String, gen: Long = generation.get()) {
        _pending.value += 1
        queue.trySend(SpeechSegment(sentence, gen))
    }

    /**
     * 还没轮到自己开口时段落切短一点，首字能更早送合成；已经在排或正在念时用常规长度。
     */
    private fun segmentLimitForNext(): Int =
        if (_pending.value == 0 && !_isSpeaking.value) SpeechPlayback.FIRST_SEGMENT_LENGTH
        else SpeechPlayback.MAX_SEGMENT_LENGTH

    /** 首段按短上限切，后续段用常规上限（具体长度由 [segmentLimitForNext] 逐段决定）。 */
    private fun splitWithFirstShort(text: String): Pair<List<String>, String> =
        SpeechPlayback.splitInto(text, segmentLimitForNext())

    /** 打断并清空：停掉当前播放，丢弃队列里排着的段。 */
    fun stop() {
        generation.incrementAndGet()
        while (queue.tryReceive().isSuccess) Unit
        while (sounds.tryReceive().isSuccess) Unit
        player.stop()
        systemTts.stop()
        _pending.value = 0
        _isSpeaking.value = false
        _currentText.value = ""
        spokenLength = 0
        pendingTail = ""
    }

    /** 关掉这一路播报：停播并释放协程与播放器。用完的会话不该再被使用。 */
    fun close() {
        stop()
        scope.cancel()
    }

    /**
     * 合成与播放并行：播放第 N 段时，下一段已经在合成。
     *
     * 以前一段接一段串行做，每句都要等上一次网络往返，连着说的时候中间会一顿一顿。
     */
    private suspend fun consume() = coroutineScope {
        val maker = launch {
            try {
                for (segment in queue) {
                    if (segment.generation != generation.get()) continue
                    // 只记阶段名与毫秒，不带文本——日志会同步到别处，说的是什么不归它管
                    val synthStart = SystemClock.elapsedRealtime()
                    val bytes = synthesize(segment.text)
                    FileLogger.i(SpeechPlayback.TAG, "timing stage=synthesize ms=${SystemClock.elapsedRealtime() - synthStart}")
                    // 合成期间可能已经被下一轮打断
                    if (segment.generation != generation.get()) continue
                    sounds.send(Sound(segment, bytes))
                }
            } finally {
                sounds.close()
            }
        }
        try {
            for (sound in sounds) {
                val segment = sound.segment
                if (segment.generation != generation.get()) continue
                _isSpeaking.value = true
                _currentText.value = segment.text
                val bytes = sound.bytes
                if (bytes != null) {
                    val playStart = SystemClock.elapsedRealtime()
                    player.playAndWait(bytes, tag = "call_${segment.generation}")
                    FileLogger.i(SpeechPlayback.TAG, "timing stage=playback ms=${SystemClock.elapsedRealtime() - playStart}")
                } else {
                    // 没配云端合成（或合成失败）：落回手机自带的引擎，至少能出声
                    systemTts.speakAndWait(segment.text)
                }
                if (segment.generation == generation.get()) {
                    _isSpeaking.value = false
                    _currentText.value = ""
                }
                // 打断会把计数清零，旧段的收尾不能再减：新一轮刚排进去的段会被少算，
                // 通话编排等 pendingCount 归零时会提前判定「说完了」。
                if (segment.generation == generation.get()) {
                    _pending.value = (_pending.value - 1).coerceAtLeast(0)
                }
            }
        } finally {
            maker.cancel()
        }
    }

    private fun findCut(text: String, maxLength: Int): Int =
        SpeechPlayback.cutIndex(text, maxLength)
}
