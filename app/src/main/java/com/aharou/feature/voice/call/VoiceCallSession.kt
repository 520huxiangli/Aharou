package com.aharou.feature.voice.call

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.StreamingAsrEngine
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModels
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * 两条硬约束：
 *  - **出声期间不收音**：否则麦克风会把自己的播报录进去，形成自问自答。
 *  - **播报走 [SpeechQueue] 的分段队列**：一轮回复可能很长，边合成边念、能被打断。
 */
@Singleton
internal class VoiceCallSession @Inject constructor(
    private val agentTurnRunner: AgentTurnRunner,
    private val speechQueue: SpeechQueue,
    private val asrEngine: StreamingAsrEngine,
    private val modelManager: VoiceModelManager,
    private val recorder: VoiceRecorder,
) {

    enum class State { Idle, Preparing, Listening, Thinking, Speaking }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 边说边出的实时识别文本，供界面显示；不在收音时为空串。 */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 当前该摆在胶囊上的台词：优先它正在说的，其次你正在说的（边说边出的识别文本）。
     * 通话是一来一回，同一时刻只需要显示正在开口的那一方。
     */
    val displayText: StateFlow<String> = combine(speechQueue.currentText, _liveText) { speaking, live ->
        speaking.ifBlank { live }
    }.stateIn(scope, SharingStarted.Eagerly, "")

    private var asrSession: StreamingAsrEngine.AsrSession? = null
    private var ongoingTurn: Job? = null
    private var sessionId: String = ""

    /**
     * 开始通话：先把工作区的会话拿到（没有就建），再进收音循环。
     *
     * 工作区为空（还没开过工作区）时直接失败——容器挂载点都没定，跑了也没地方落。
     */
    fun start(workspacePath: String) {
        if (_state.value != State.Idle) return
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
            beginListening()
        }
    }

    /** 挂断：收掉录音、播报、识别，回到空闲。 */
    fun stop() {
        teardown()
        _error.value = null
    }

    private fun teardown() {
        ongoingTurn?.cancel()
        ongoingTurn = null
        stopRecording()
        asrSession?.release()
        asrSession = null
        speechQueue.stop()
        _liveText.value = ""
        _state.value = State.Idle
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
        asrSession = session
        _liveText.value = ""
        when (recorder.start(::onPcmChunk)) {
            RecordStartResult.Started -> _state.value = State.Listening
            RecordStartResult.NoPermission -> fail("MIC_PERMISSION")
            RecordStartResult.Failed -> fail("RECORD_FAILED")
        }
    }

    /** 录音线程回调：喂模型、刷实时文本、检测说话结束。 */
    private fun onPcmChunk(samples: FloatArray) {
        // 出声/思考期间不收音：既避免录到自己的声音，也避免误触发下一轮
        if (_state.value != State.Listening) return
        val session = asrSession ?: return
        val live = session.feed(samples)
        if (_liveText.value != live) _liveText.value = live
        // 停顿满阈值（1.4s 静音）即认为一句话说完，直接提交
        if (session.isEndpoint() && live.isNotBlank()) submit(live.trim())
    }

    private fun submit(text: String) {
        if (_state.value != State.Listening || text.isBlank()) return
        _state.value = State.Thinking
        _liveText.value = ""
        stopRecording()
        asrSession?.let {
            it.release()
            asrSession = null
        }
        ongoingTurn = scope.launch {
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
            if (_state.value != State.Idle) beginListening()
        }
    }

    /** 等队列播完：先等段数归零，再等最后一段真的出完声。 */
    private suspend fun awaitSpeechDone() {
        speechQueue.pendingCount.first { it == 0 }
        speechQueue.isSpeaking.first { !it }
    }

    private fun stopRecording() {
        if (recorder.isRecording) recorder.stop()
    }

    private fun fail(code: String) {
        FileLogger.w(TAG, "通话失败：$code")
        teardown()
        _error.value = code
    }

    private companion object {
        const val TAG = "VoiceCallSession"
    }
}
