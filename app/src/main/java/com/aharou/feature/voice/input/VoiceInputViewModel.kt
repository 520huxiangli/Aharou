package com.aharou.feature.voice.input

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.StreamingAsrEngine
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 语音输入状态。 */
internal sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object Preparing : VoiceInputState

    /** 正在收音；[text] 是边说边出的识别结果，[level] 是给波形用的当前振幅（0..1）。 */
    data class Listening(val text: String, val level: Float) : VoiceInputState
    data object Finishing : VoiceInputState

    /** 一次性提示（模型没下、没权限、麦被占用），[messageRes] 对应文案。 */
    data class Failed(@StringRes val messageRes: Int) : VoiceInputState
}

/**
 * 按住说话的语音输入：录音走 [VoiceRecorder]，识别走 [StreamingAsrEngine]。
 *
 * 与语音通话共用同一个录音器与识别引擎，两者互斥：通话在跑时 [VoiceRecorder.start] 直接失败，
 * 所以这里不做排队，宁可提示「先挂断」也不抢麦。
 *
 * 长按模式下**不靠引擎的端点检测收尾**：说话中间停顿 1.4 秒是常有的事，自动断句会把一句话切成两半，
 * 收尾一律以松手为准（[finish]）。超过 [MAX_RECORD_MS] 才兜底自动收，避免一直按着不放手时无限录音。
 */
@HiltViewModel
internal class VoiceInputViewModel @Inject constructor(
    private val recorder: VoiceRecorder,
    private val asrEngine: StreamingAsrEngine,
    private val modelManager: VoiceModelManager,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /**
     * 输入框应有的内容（按下前已输入的文本 + 实时识别文本）。
     *
     * null 表示这次界面还没用过语音输入——UI 只在拿到非 null 时回写输入框，避免初始值把草稿清空。
     * 结束后不清空：下次 [start] 会把它重置成当时的草稿。
     */
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    /** 按下前输入框里的内容，识别结果接在它后面。 */
    private var prefix = ""

    private var session: StreamingAsrEngine.AsrSession? = null

    /** 每次 start 自增：加载模型要几秒，期间用户可能已经松手取消，用它作废那次启动。 */
    private var generation = 0

    /** 收尾进行中，挡住重复调用（松手与超时兜底可能同时到）。 */
    private var closing = false

    private var startedAtMs = 0L

    fun start(prefix: String) {
        if (_state.value !is VoiceInputState.Idle) return
        if (recorder.isRecording) {
            viewModelScope.launch { flash(R.string.voice_input_busy) }
            return
        }
        val gen = ++generation
        this.prefix = prefix
        closing = false
        _draft.value = prefix
        _state.value = VoiceInputState.Preparing
        viewModelScope.launch(Dispatchers.Default) {
            val dir = runCatching {
                modelManager.ensureModel(VoiceModels.ASR_ZH, allowDownload = false)
            }.getOrNull()
            if (gen != generation) return@launch
            if (dir == null) {
                flash(R.string.voice_input_need_model)
                return@launch
            }
            val created = asrEngine.startSession(dir)
            if (gen != generation) {
                created?.release()
                return@launch
            }
            if (created == null) {
                flash(R.string.voice_input_model_load_failed)
                return@launch
            }
            session = created
            startedAtMs = System.currentTimeMillis()
            when (recorder.start(onChunk = ::onChunk)) {
                RecordStartResult.Started -> {
                    FileLogger.i(TAG, "开始按住说话")
                    _state.value = VoiceInputState.Listening("", 0f)
                }
                RecordStartResult.NoPermission -> {
                    releaseSession()
                    flash(R.string.voice_input_no_permission)
                }
                RecordStartResult.Failed -> {
                    releaseSession()
                    flash(R.string.voice_input_failed)
                }
            }
        }
    }

    /** 松手：结束录音并把最终文本留在 [draft] 里。 */
    fun finish() {
        viewModelScope.launch(Dispatchers.Default) { close() }
    }

    private suspend fun close() {
        val current = _state.value
        if (closing || current is VoiceInputState.Idle || current is VoiceInputState.Failed) return
        closing = true
        generation++
        _state.value = VoiceInputState.Finishing
        recorder.stop()
        val text = synchronized(this) { session?.finish().orEmpty() }
        releaseSession()
        _draft.value = prefix
        _state.value = VoiceInputState.Idle
        // 还在准备阶段就松手，说明按得太短，不提交也不提示：这时候说「没听清」只会让人以为麦克风坏了。
        val committing = current is VoiceInputState.Listening
        if (committing && text.isNotBlank()) {
            _draft.value = prefix + text
            FileLogger.i(TAG, "按住说话结束，识别 ${text.length} 字")
        } else if (committing) {
            flash(R.string.voice_input_empty)
        }
        closing = false
    }

    private fun onChunk(samples: FloatArray) {
        if (closing) return
        val current = session ?: return
        val text = current.feed(samples)
        var peak = 0f
        for (sample in samples) {
            val value = abs(sample)
            if (value > peak) peak = value
        }
        val level = (peak * 6f).coerceIn(0f, 1f)
        _state.update { state ->
            if (state is VoiceInputState.Listening) VoiceInputState.Listening(text, level) else state
        }
        _draft.value = prefix + text
        if (System.currentTimeMillis() - startedAtMs > MAX_RECORD_MS) {
            viewModelScope.launch(Dispatchers.Default) { close() }
        }
    }

    private fun releaseSession() {
        val current = synchronized(this) {
            val value = session
            session = null
            value
        }
        current?.release()
    }

    /** 一次性提示：显示 [messageRes] 两秒后自己回 Idle，不把界面卡在错误态。 */
    private suspend fun flash(@StringRes messageRes: Int) {
        _state.value = VoiceInputState.Failed(messageRes)
        delay(FLASH_MS)
        if (_state.value == VoiceInputState.Failed(messageRes)) {
            _state.value = VoiceInputState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        generation++
        // 录音器是与语音通话共用的单例：只收自己开的那次，不能把正在进行的通话一起掐掉。
        if (session != null && recorder.isRecording) recorder.cancel()
        releaseSession()
    }

    private companion object {
        const val TAG = "VoiceInputViewModel"

        /** 长按最长录多久：够说完一大段，又不至于一直按着把电池耗掉。 */
        const val MAX_RECORD_MS = 60_000L
        const val FLASH_MS = 2_000L
    }
}
