package com.aharou.feature.voice.call

import android.content.pm.ApplicationInfo
import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.voice.data.OfflineAsrEngine
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.StreamingAsrEngine
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.data.WakeWordEngine
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModels
import com.aharou.feature.voice.domain.VoiceWakeWord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 免手通话的回合编排：听 → 想 → 说 → 再听，直到挂断。
 *
 * 只在服务进程里跑，不依赖任何界面作用域，所以是 [Singleton] 而非 ViewModel。
 * 消息的落库与历史组装全部交给 [AgentTurnRunner]，这里只管节奏。
 *
 * 三种硬约束：
 *  - **出声期间不收音**：否则麦克风会把自己的播报录进去，形成自问自答。
 *  - **播报走 [SpeechQueue] 的分段队列**：一轮回复可能很长，边合成边念、能被打断。
 *  - **唤醒词监听与识别不共用引擎**：唤醒跑 3.3M 的小模型（[WakeWordEngine]），
 *    唤醒后才换 160M 的识别模型——常驻监听才不至于把电吃光。
 */
@Singleton
internal class VoiceCallSession @Inject constructor(
    private val agentTurnRunner: AgentTurnRunner,
    private val speechQueue: SpeechQueue,
    private val asrEngine: StreamingAsrEngine,
    private val offlineAsrEngine: OfflineAsrEngine,
    private val wakeEngine: WakeWordEngine,
    private val modelManager: VoiceModelManager,
    private val recorder: VoiceRecorder,
    @param:ApplicationContext private val context: Context,
) {

    enum class State { Idle, Preparing, WakeListening, Listening, Thinking, Speaking }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 边说边出的实时识别文本，供界面显示；不在收音时为空串。 */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 唤醒词命中时的回调，供桌宠给视觉/触觉反馈。 */
    @Volatile
    var onWakeDetected: (() -> Unit)? = null

    /**
     * 当前该摆在胶囊上的台词：优先它正在说的，其次你正在说的（边说边出的识别文本）。
     * 通话是一来一回，同一时刻只需要显示正在开口的那一方。
     */
    val displayText: StateFlow<String> = combine(speechQueue.currentText, _liveText) { speaking, live ->
        speaking.ifBlank { live }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 音频回调线程与主线程共用的锁。
     *
     * 录音回调在独立线程里 feed，而引擎切换（释放旧 session、建新 session）在主线程。
     * 两边一旦重置，native 对象会被一边 release 一边 getResult，直接 abort
     * （实测：std::length_error: vector，栈在 OnlineRecognizer.getResult）。
     * 所有「碰 session」与「改 state」的地方都要拿这把锁，顺序天然对齐。
     */
    private val audioLock = Any()

    private var asrSession: StreamingAsrEngine.AsrSession? = null
    private var wakeSession: WakeWordEngine.WakeSession? = null
    private var ongoingTurn: Job? = null
    private var ackJob: Job? = null
    private var listenTimeoutJob: Job? = null
    private var sessionId: String = ""

    /** 唤醒监听模式：一回合结束后回到听唤醒词，而不是继续收音。 */
    private var wakeMode = false

    /**
     * 滚动缓冲：唤醒词命中前的最后一段音频。
     *
     * 「小染小染，帮我打开抖音」是一句连说，而 KWS 要听完整个词才报命中——
     * 等它报出来时，「帮我」已经进了这段缓冲。换识别引擎时把它补喂过去，那几个字才不会丢。
     */
    private val preRoll = ArrayDeque<FloatArray>()
    private var preRollSamples = 0

    /**
     * 本次聆听累计的整句 PCM，停顿后交给离线模型重新识别。
     *
     * 流式模型为了实时只能看左侧上下文，准头差；整句音频留着，让 [OfflineAsrEngine]
     * 用 SenseVoice 重跑一遍取更准的文本。上限 [UTTERANCE_MAX_SAMPLES] 封顶，防止没人说话时无限增长。
     */
    private val utterance = ArrayList<FloatArray>()
    private var utteranceSamples = 0

    /** 唤醒监听期的音频缓冲，仅调试构建用于落盘；见 [rememberWakeAudio]。 */
    private val wakeAudio = ArrayList<FloatArray>()
    private var wakeAudioSamples = 0
    private val wakeAudioLock = Any()

    /** 唤醒监听期每积累这么多秒就落一份音频，用于诊断「麦克风到底收到什么」。 */
    private val wakeDumpSeconds = 5

    /**
     * 开始通话。
     *
     * @param wakeMode true 时先只跑唤醒词检测，喊出唤醒词才转入识别。
     *
     * 工作区为空（还没开过工作区）时直接失败——容器挂载点都没定，跑了也没地方落。
     */
    fun start(workspacePath: String, wakeMode: Boolean = false) {
        if (_state.value != State.Idle) return
        this.wakeMode = wakeMode
        _error.value = null
        scope.launch {
            val path = workspacePath.ifBlank { agentTurnRunner.currentWorkspacePath() }
            if (path.isBlank()) {
                _error.value = "NO_WORKSPACE"
                _state.value = State.Idle
                return@launch
            }
            _state.value = State.Preparing
            sessionId = runCatching { agentTurnRunner.ensureSession(path) }
                .getOrElse {
                    FileLogger.e(TAG, "取通话会话失败", it)
                    ""
                }
            if (sessionId.isBlank()) {
                _error.value = "NO_SESSION"
                _state.value = State.Idle
                return@launch
            }
            if (wakeMode) {
                // 三个模型全部提前预热。识别器首次加载要 4 秒多，拖到唤醒词命中才加载的话，
                // 这几秒里录音还没开，用户接着说的指令会被整段丢掉（实测空窗 4.2s）。
                scope.launch {
                    runCatching {
                        modelManager.ensureModel(VoiceModels.ASR_ZH)
                        asrEngine.warmUp(modelManager.modelDir(VoiceModels.ASR_ZH))
                        FileLogger.i(TAG, "流式识别器预热完成")
                    }.onFailure { FileLogger.w(TAG, "流式识别器预热失败", it) }
                }
                scope.launch {
                    runCatching {
                        modelManager.ensureModel(VoiceModels.ASR_ZH_OFFLINE)
                        offlineAsrEngine.warmUp(modelManager.modelDir(VoiceModels.ASR_ZH_OFFLINE))
                        FileLogger.i(TAG, "离线识别器预热完成")
                    }.onFailure { FileLogger.w(TAG, "离线识别器预热失败", it) }
                }
                beginWakeListening()
            } else {
                beginListening()
            }
        }
    }

    /** 挂断：收掉录音、播报、识别，回到空闲。 */
    fun stop() {
        teardown()
        _error.value = null
    }

    private fun teardown(stopSpeech: Boolean = true) {
        synchronized(audioLock) {
            ongoingTurn?.cancel()
            ongoingTurn = null
            ackJob?.cancel()
            ackJob = null
            listenTimeoutJob?.cancel()
            listenTimeoutJob = null
            stopRecording()
            asrSession?.release()
            asrSession = null
            wakeSession?.release()
            wakeSession = null
            clearPreRoll()
            clearUtterance()
            _liveText.value = ""
            _state.value = State.Idle
        }
        if (stopSpeech) speechQueue.stop()
    }

    /** 用户主动打断当前播报/思考，直接回到收音。 */
    fun interrupt() {
        if (_state.value == State.Speaking || _state.value == State.Thinking) {
            speechQueue.stop()
            ongoingTurn?.cancel()
            ongoingTurn = null
            scope.launch { beginListening() }
        }
    }

    /**
     * 手动开始一次聆听（唤醒常驻时点麦克风按钮走这里），语义等同喊了一声唤醒词。
     * 只在正听唤醒词时生效；通话模式下的点按由调用方走挂断。
     */
    fun triggerListen() {
        if (_state.value != State.WakeListening) return
        onWakeDetected()
    }

    /**
     * 从识别切回唤醒监听：同样不重启录音，只把消费者换回 KWS。
     * 必须先把识别结果丢掉（[asrSession] 释放），否则 KWS 会收到被 ASR 消费过的同一批 PCM。
     */
    private suspend fun beginWakeListening() {
        val kwsReady = if (!modelManager.isReady(VoiceModels.KWS_ZH)) {
            runCatching { modelManager.ensureModel(VoiceModels.KWS_ZH) {} }.isSuccess
        } else {
            true
        }
        if (!kwsReady) {
            fail("WAKE_MODEL_UNAVAILABLE")
            return
        }
        val session = wakeEngine.startSession(
            modelManager.modelDir(VoiceModels.KWS_ZH),
            VoiceWakeWord.KEYWORDS,
        )
        if (session == null) {
            fail("WAKE_INIT_FAILED")
            return
        }
        val alreadyRecording = synchronized(audioLock) {
            // 先切状态、再换 session，全部在同一把锁里：回调线程要么看到旧状态配旧 session，
            // 要么看到新状态配新 session，不会撞上「已 release 的 session 还在被 feed」。
            asrSession?.let {
                it.release()
                asrSession = null
            }
            wakeSession = session
            clearPreRoll()
            clearUtterance()
            _liveText.value = ""
            val wasRecording = recorder.isRecording
            if (wasRecording) _state.value = State.WakeListening
            wasRecording
        }
        if (!alreadyRecording) {
            when (recorder.start(::onPcmChunk)) {
                RecordStartResult.Started -> synchronized(audioLock) { _state.value = State.WakeListening }
                RecordStartResult.NoPermission -> fail("MIC_PERMISSION")
                RecordStartResult.Failed -> fail("RECORD_FAILED")
            }
        }
    }

    private suspend fun beginListening() {
        if (!modelManager.isReady(VoiceModels.ASR_ZH)) {
            val ok = runCatching { modelManager.ensureModel(VoiceModels.ASR_ZH) {} }.isSuccess
            if (!ok) {
                fail("MODEL_UNAVAILABLE")
                return
            }
        }
        // 先把识别会话建好再开录音，否则开头几个 PCM 块会丢
        val session = asrEngine.startSession(modelManager.modelDir(VoiceModels.ASR_ZH))
        if (session == null) {
            fail("ASR_INIT_FAILED")
            return
        }
        val alreadyRecording = synchronized(audioLock) {
            // 先切状态、再换 session（理由同 beginWakeListening）
            wakeSession?.let {
                it.release()
                wakeSession = null
            }
            asrSession = session
            _liveText.value = ""
            clearUtterance()
            val wasRecording = recorder.isRecording
            if (wasRecording) _state.value = State.Listening
            wasRecording
        }
        if (!alreadyRecording) {
            when (recorder.start(::onPcmChunk)) {
                RecordStartResult.Started -> synchronized(audioLock) { _state.value = State.Listening }
                RecordStartResult.NoPermission -> fail("MIC_PERMISSION")
                RecordStartResult.Failed -> fail("RECORD_FAILED")
            }
        }
        // 补喂唤醒词之后、识别引擎启动之前那段音频。
        // 必须和音频回调一样拿锁：录音线程没停（这就是不回退录音的代价），
        // 不拿锁两边会同时 feed 同一个 native stream/recognizer，直接 abort
        // （实测 std::length_error: vector，栈在 OnlineRecognizer.getResult）。
        if (preRoll.isNotEmpty()) {
            val blocks = synchronized(audioLock) {
                val b = preRoll.toList()
                clearPreRoll()
                b
            }
            synchronized(audioLock) {
                // 回溯缓存也要进整句缓冲：唤醒词尾与指令开头都在这一小段里
                blocks.forEach {
                    session.feed(it)
                    rememberUtterance(it)
                }
                val live = session.text
                if (live.isNotBlank()) _liveText.value = live
            }
        }
        scheduleListenTimeout()
    }

    /** 唤醒词命中：只换识别引擎，录音不中断。 */
    private fun onWakeDetected() {
        if (_state.value != State.WakeListening) return
        FileLogger.i(TAG, "唤醒词命中")
        onWakeDetected?.invoke()
        // 注意：这里不能 release 掉 wakeSession——beginListening 会在锁里接手，
        // 顺手把 KWS session 释放掉。在这里释放会和音频回调撞车（见 audioLock 注释）。
        scope.launch {
            beginListening()
            // 连说时（「小染小染，帮我打开抖音」）识别很快就出字，就不该再插一句应答；
            // 隔一会儿还没听到内容，才说明只是叫了一声，回一句「我在」等着。
            scheduleWakeAck()
        }
    }

    /** 录音线程回调：按当前状态分流——唤醒态喂 KWS，识别态喂 ASR。 */
    private fun onPcmChunk(samples: FloatArray) {
        synchronized(audioLock) {
            when (_state.value) {
                State.WakeListening -> {
                    rememberPreRoll(samples)
                    rememberWakeAudio(samples)
                    val hit = wakeSession?.feed(samples) ?: return
                    if (hit.isNotBlank()) onWakeDetected()
                }

                State.Listening -> {
                    // 出声/思考期间不收音：既避免录到自己的声音，也避免误触发下一轮
                    val session = asrSession ?: return
                    val live = session.feed(samples)
                    rememberUtterance(samples)
                    if (_liveText.value != live) _liveText.value = live
                    // 一开口就把超时计时废掉：人已经在说话了，不该再判「没人开口」
                    if (live.isNotBlank() && listenTimeoutJob?.isActive == true) {
                        listenTimeoutJob?.cancel()
                        listenTimeoutJob = null
                    }
                    // 停顿满阈值（1.4s 静音）即认为一句话说完
                    if (session.isEndpoint() && live.isNotBlank()) {
                        val command = stripWakeWord(live)
                        val audio = takeUtterance()
                        if (command.isBlank()) {
                            // 只听到唤醒词本身（识别常把「小染」听成「小冉」），当噪声丢掉继续听。
                            // 回溯缓存里本来就含唤醒词，不剥掉就会被当成指令发出去。
                            _liveText.value = ""
                        } else {
                            submit(command, audio)
                        }
                    }
                }

                else -> return
            }
        }
    }

    private fun submit(streamedText: String, audio: FloatArray) {
        if (_state.value != State.Listening) return
        ackJob?.cancel()
        ackJob = null
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        _state.value = State.Thinking
        _liveText.value = ""
        stopRecording()
        releaseAsr()
        ongoingTurn = scope.launch {
            // 停顿后用离线模型对整句重新识别。流式为了实时只能看左边那点上下文，
            // 准头差一截；这里的音频是完整的，能拿到更准的中文。失败就退回流式结果。
            val offlineText = if (audio.isNotEmpty()) {
                // 兜底：预热还没跑完（或失败）时，这里确保一次。
                // 模型不落盘时就 recognize 永远返回 null，表现是「识别毫无变化」。
                runCatching { modelManager.ensureModel(VoiceModels.ASR_ZH_OFFLINE) }
                offlineAsrEngine
                    .recognize(modelManager.modelDir(VoiceModels.ASR_ZH_OFFLINE), audio)
                    ?.let { stripWakeWord(it) }
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val text = offlineText ?: streamedText
            FileLogger.i(TAG, "提交文本：离线=${offlineText != null} 长度=${text.length} 内容=$text")
            dumpUtteranceForDebug()

            var first = true
            runCatching {
                agentTurnRunner.run(
                    AgentTurnRequest(sessionId = sessionId, text = text)
                ).collect { event ->
                    if (event is AgentEvent.AssistantText && event.content.isNotBlank()) {
                        speechQueue.enqueue(event.content, interrupt = first)
                        first = false
                        _state.value = State.Speaking
                    }
                }
            }.onFailure { FileLogger.e(TAG, "通话一轮失败", it) }

            awaitSpeechDone()
            if (_state.value == State.Idle) return@launch
            if (wakeMode) beginWakeListening() else beginListening()
        }
    }

    /**
     * 把本次录到的整句音频存成 WAV，带「提交文本：…」的行一起看。
     *
     * 识别结果离谱时，光看文本分不清是「麦克风没收到人声」「收到的是环境音」
     * 还是「音频没问题但模型认错」，把原始音频留下来听一遁最直接。
     */
    private fun dumpUtteranceForDebug() {
        if (!isDebuggable()) return
        runCatching {
            val pcm = synchronized(audioLock) {
                val total = utterance.sumOf { it.size }
                if (total == 0) return@runCatching
                val all = FloatArray(total)
                var offset = 0
                utterance.forEach { block ->
                    block.copyInto(all, offset)
                    offset += block.size
                }
                all
            }
            val dir = java.io.File(context.filesDir, "debug-audio").apply { mkdirs() }
            val out = java.io.File(dir, "utt-${System.currentTimeMillis()}.wav")
            val bytes = ByteArray(44 + pcm.size * 2)
            writeWavHeader(bytes, pcm.size)
            var i = 44
            pcm.forEach { s ->
                val v = (s.coerceIn(-1f, 1f) * 32767).toInt()
                bytes[i++] = (v and 0xFF).toByte()
                bytes[i++] = ((v shr 8) and 0xFF).toByte()
            }
            out.writeBytes(bytes)
            FileLogger.i(TAG, "调试音频已存：《${out.absolutePath}》时长=${pcm.size * 1000 / 16000}ms")
        }.onFailure { FileLogger.w(TAG, "调试音频落盘失败", it) }
    }

    /** 项目未开启 BuildConfig，用 ApplicationInfo 的标志判断调试构建。 */
    private fun isDebuggable(): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * 唤醒监听期的音频也周期性落盘（调试构建）。
     *
     * 唤醒词不命中时，提交路径根本走不到，光靠提交时的音频无从判断「麦克风到底收到什么」。
     * 每满 [WAKE_DUMP_SECONDS] 秒存一个文件，人喊的时候必有一份落在里面。
     */
    private fun rememberWakeAudio(samples: FloatArray) {
        if (!isDebuggable()) return
        synchronized(wakeAudioLock) {
            wakeAudio.add(samples)
            wakeAudioSamples += samples.size
            if (wakeAudioSamples < 16000 * wakeDumpSeconds) return
            val all = FloatArray(wakeAudioSamples)
            var offset = 0
            wakeAudio.forEach { block ->
                block.copyInto(all, offset)
                offset += block.size
            }
            wakeAudio.clear()
            wakeAudioSamples = 0
            writeWav(all, "wake-${System.currentTimeMillis()}.wav")
        }
    }

    /** 把一段归一化 PCM 存成 16k 单声道 WAV，名字取 [name]。 */
    private fun writeWav(pcm: FloatArray, name: String) {
        runCatching {
            val dir = java.io.File(context.filesDir, "debug-audio").apply { mkdirs() }
            val out = java.io.File(dir, name)
            val bytes = ByteArray(44 + pcm.size * 2)
            writeWavHeader(bytes, pcm.size)
            var i = 44
            pcm.forEach { s ->
                val v = (s.coerceIn(-1f, 1f) * 32767).toInt()
                bytes[i++] = (v and 0xFF).toByte()
                bytes[i++] = ((v shr 8) and 0xFF).toByte()
            }
            out.writeBytes(bytes)
            FileLogger.i(TAG, "调试音频已存：${out.absolutePath} 时长=${pcm.size * 1000 / 16000}ms")
        }.onFailure { FileLogger.w(TAG, "调试音频落盘失败", it) }
    }

    private fun writeWavHeader(bytes: ByteArray, samples: Int) {
        val dataLen = samples * 2
        fun putStr(off: Int, s: String) = s.forEachIndexed { i, c -> bytes[off + i] = c.code.toByte() }
        fun putInt(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[off + 2] = ((v shr 16) and 0xFF).toByte()
            bytes[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataLen)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, 16000)
        putInt(28, 32000)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataLen)
    }

    /** 等队列播完：先等段数归零，再等最后一段真的出完声。 */
    private suspend fun awaitSpeechDone() {
        speechQueue.pendingCount.first { it == 0 }
        speechQueue.isSpeaking.first { !it }
    }

    /**
     * 剥掉识别结果开头的唤醒词，只留真正的指令。
     *
     * 唤醒词会跟着回溯缓存一起进识别，而本机识别对「小染」的转写并不稳定
     * （常写成「小冉」「小然」），所以按字类匹配，不按固定字符串。
     * 剥完剩空串就意味着用户只是叫了一声，由调用方丢弃。
     */
    private fun stripWakeWord(text: String): String {
        val trimmed = text.trim()
        val match = WAKE_PREFIX.find(trimmed) ?: return trimmed
        return trimmed.substring(match.range.last + 1)
            .trimStart('，', ',', '。', '.', '！', '!', '、', '：', ':', ' ')
    }

    /** 释放识别会话；必须拿锁，否则可能和正在 feed 的音频回调撞车。 */
    private fun releaseAsr() = synchronized(audioLock) {
        asrSession?.release()
        asrSession = null
    }

    /** 累计本句音频，超过上限就从头部丢掉（防止长时间无人说话时无限增长）。 */
    private fun rememberUtterance(samples: FloatArray) {
        utterance.add(samples)
        utteranceSamples += samples.size
        while (utteranceSamples > UTTERANCE_MAX_SAMPLES && utterance.size > 1) {
            utteranceSamples -= utterance.removeAt(0).size
        }
    }

    /** 取出并清空本句音频（拼成连续 PCM 交给离线模型）。 */
    private fun takeUtterance(): FloatArray {
        if (utterance.isEmpty()) return FloatArray(0)
        val out = FloatArray(utteranceSamples)
        var offset = 0
        for (block in utterance) {
            block.copyInto(out, offset)
            offset += block.size
        }
        utterance.clear()
        utteranceSamples = 0
        return out
    }

    private fun clearUtterance() {
        utterance.clear()
        utteranceSamples = 0
    }

    /** 10 秒没人开口就结束这次聆听（唤醒模式下回听唤醒词，不硬耗着）。 */
    private fun scheduleListenTimeout() {
        listenTimeoutJob?.cancel()
        if (!wakeMode) return
        listenTimeoutJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (_state.value == State.Listening && _liveText.value.isBlank()) {
                FileLogger.i(TAG, "聆听超时，没听到人说话")
                stopRecording()
                releaseAsr()
                beginWakeListening()
            }
        }
    }

    /** 延迟播唤醒应答语：这段时间足够「连说」出字，出字就不播了。 */
    private fun scheduleWakeAck() {
        ackJob?.cancel()
        ackJob = scope.launch {
            delay(WAKE_ACK_DELAY_MS)
            if (_state.value == State.Listening && _liveText.value.isBlank()) speakWakeAck()
        }
    }

    /** 应答语「我在」：先停收音再说，免得把它自己录进去。 */
    private fun speakWakeAck() {
        if (_state.value != State.Listening) return
        _state.value = State.Speaking
        stopRecording()
        releaseAsr()
        speechQueue.enqueue(context.getString(R.string.voice_wake_ack), interrupt = true)
        scope.launch {
            awaitSpeechDone()
            if (_state.value == State.Idle) return@launch
            // 应答完继续收音（而不是回听唤醒词）：用户接下来那句就是指令
            beginListening()
        }
    }

    private fun rememberPreRoll(samples: FloatArray) {
        preRoll.addLast(samples)
        preRollSamples += samples.size
        while (preRollSamples > PRE_ROLL_SAMPLES && preRoll.isNotEmpty()) {
            preRollSamples -= preRoll.removeFirst().size
        }
    }

    private fun clearPreRoll() {
        preRoll.clear()
        preRollSamples = 0
    }

    private fun stopRecording() {
        if (recorder.isRecording) recorder.stop()
    }

    private fun fail(code: String) {
        FileLogger.w(TAG, "通话失败：$code")
        // 语音场景下人多半没在看屏幕，失败原因必须说出来，不能只画在界面上；
        // 所以这里不调 speechQueue.stop()，让刚排进去的那句提示能念完。
        teardown(stopSpeech = false)
        _error.value = code
        speechQueue.enqueue(speechForError(code), interrupt = true)
    }

    /** 把内部错误码翻成人话，并说清要开哪个权限、去哪儿开。 */
    private fun speechForError(code: String): String = when (code) {
        "MIC_PERMISSION" -> context.getString(R.string.voice_err_mic_permission)
        "MODEL_UNAVAILABLE", "WAKE_MODEL_UNAVAILABLE" -> context.getString(R.string.voice_err_model)
        "NO_WORKSPACE" -> context.getString(R.string.voice_err_no_workspace)
        "NO_SESSION" -> context.getString(R.string.voice_err_no_session)
        else -> context.getString(R.string.voice_err_audio)
    }

    private companion object {
        const val TAG = "VoiceCallSession"

        /** 唤醒前的回溯缓存：盖住「唤醒词报出」到「识别引擎启动」之间的空档，接住紧跟唤醒词的那句指令。 */
        const val PRE_ROLL_MS = 1200
        const val PRE_ROLL_SAMPLES = 16000 * PRE_ROLL_MS / 1000

        /** 聆听超时：开了口但 10 秒没听到人说话，自动结束这次聆听。 */
        const val LISTEN_TIMEOUT_MS = 10_000L

        /** 唤醒词的转写容错：小/晓 + 染/冉/然/燃，最多两组。 */
        val WAKE_PREFIX = Regex("^[小晓][染冉然燃][小晓]?[染冉然燃]?")

        /** 本句音频上限：30 秒（16k 单声道），超了就丢最早的块。 */
        const val UTTERANCE_MAX_SAMPLES = 16000 * 30

        /** 唤醒后等多久还没听到内容，就播应答语。 */
        const val WAKE_ACK_DELAY_MS = 1200L
    }
}
