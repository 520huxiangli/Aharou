package com.aharou.feature.voice.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.voice.call.SpeechQueue
import com.aharou.feature.voice.data.CloudSpeechClient
import com.aharou.feature.voice.data.SystemTtsPlayer
import com.aharou.feature.voice.data.TtsPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 朗读状态，驱动按钮外观。 */
internal sealed interface ReadAloudState {
    data object Idle : ReadAloudState
    data object Synthesizing : ReadAloudState
    data object Playing : ReadAloudState
    data class Error(@StringRes val messageRes: Int) : ReadAloudState
}

/**
 * AI 回复的朗读。走云端 `/audio/speech` 合成后本地播放。
 *
 * 同一时刻只播一条：发起新的朗读会打断上一条（[currentText] 用于让 UI 知道哪条在播）。
 */
@HiltViewModel
internal class ReadAloudViewModel @Inject constructor(
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val providerRepository: AIProviderRepository,
    private val cloudSpeech: CloudSpeechClient,
    private val player: TtsPlayer,
    private val systemTts: SystemTtsPlayer,
    private val speechQueue: SpeechQueue,
) : ViewModel() {

    private val _state = MutableStateFlow<ReadAloudState>(ReadAloudState.Idle)
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    /** 正在朗读（或正在合成）的那条文本；未朗读时为空。UI 据此高亮对应按钮。 */
    private val _currentText = MutableStateFlow<String?>(null)
    val currentText: StateFlow<String?> = _currentText.asStateFlow()

    private var job: Job? = null

    /** 上一次喂进来的流式累积文本：用来判断新一轮（文本变短了）与收尾。 */
    private var lastStreamText = ""

    /** 自动朗读开关（设置页那个）。 */
    val autoReadEnabled: StateFlow<Boolean> = ttsSettings.autoReadAloudFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 上一次自动念过的消息 id：同一条消息不重复念（消息列表每帧重组都会回调）。 */
    private var lastAutoReadId: String? = null

    /**
     * 边回边念：把流式累积文本喂给播报队列，由它切句、合成、排队读。
     *
     * 开关关着时直接丢弃（不缓存），避免开关一打开就把攒了半天的旧文本倒出来念一遍。
     */
    fun feedStream(accumulated: String) {
        if (!autoReadEnabled.value || accumulated.isBlank()) return
        // 文本比上次短 = 新的一轮开始了，先打断上一轮的尾巴
        val interrupt = accumulated.length < lastStreamText.length
        lastStreamText = accumulated
        speechQueue.feed(accumulated, interrupt = interrupt)
    }

    /** 一轮结束（流式文本清空）：把还没成句的尾巴也读掉。 */
    fun finishStream() {
        if (!autoReadEnabled.value) return
        if (lastStreamText.isNotBlank()) speechQueue.feed(lastStreamText, finish = true)
        lastStreamText = ""
    }

    /** 立即停下播报。 */
    fun stopStream() {
        lastStreamText = ""
        speechQueue.stop()
    }

    /**
     * 自动朗读（非流式兑底）：开关开着且这条还没念过，就整条念出来。由界面在「回复落地」时调。
     * 开关关着时也要记 id——否则用户中途打开开关，会突然把好几条旧回复连着念一遍。
     */
    suspend fun autoRead(messageId: String, text: String) {
        if (messageId == lastAutoReadId) return
        lastAutoReadId = messageId
        if (text.isBlank() || !ttsSettings.isAutoReadAloud()) return
        if (_currentText.value == text) return
        toggle(text)
    }

    /** 朗读一段文本；正在播同一段时再点即停止。 */
    fun toggle(text: String) {
        if (_currentText.value == text && _state.value is ReadAloudState.Playing) {
            stop()
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            player.stop()
            _currentText.value = text
            _state.value = ReadAloudState.Synthesizing
            try {
                val providerId = ttsSettings.getTtsProviderId()
                val model = ttsSettings.getTtsModel()
                val provider = if (providerId.isNotBlank()) {
                    runCatching { providerRepository.getProviderById(providerId) }.getOrNull()
                } else {
                    null
                }
                if (provider == null || model.isBlank() || provider.firstUsableApiKey.isBlank()) {
                    // 没配云端合成：落回手机自带的引擎；连它也没有才算真没法念
                    _state.value = ReadAloudState.Playing
                    val spoken = systemTts.speakAndWait(text)
                    _currentText.value = null
                    _state.value = if (spoken) {
                        ReadAloudState.Idle
                    } else {
                        ReadAloudState.Error(R.string.voice_tts_not_configured)
                    }
                    return@launch
                }
                val audio = cloudSpeech.synthesize(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.firstUsableApiKey,
                    model = model,
                    text = text,
                    voice = ttsSettings.getVoice(),
                )
                _state.value = ReadAloudState.Playing
                player.play(audio) {
                    // 自然播完：回到空闲。在 IO 线程回调，切回主线程改状态
                    viewModelScope.launch {
                        _currentText.value = null
                        _state.value = ReadAloudState.Idle
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "朗读失败", e)
                _currentText.value = null
                _state.value = ReadAloudState.Error(R.string.voice_tts_failed)
            }
        }
    }

    /** 停止朗读。 */
    fun stop() {
        job?.cancel()
        job = null
        player.stop()
        systemTts.stop()
        _currentText.value = null
        _state.value = ReadAloudState.Idle
    }

    fun clearError() {
        if (_state.value is ReadAloudState.Error) _state.value = ReadAloudState.Idle
    }

    override fun onCleared() {
        player.stop()
        systemTts.stop()
        super.onCleared()
    }

    private companion object {
        const val TAG = "ReadAloudViewModel"
    }
}
