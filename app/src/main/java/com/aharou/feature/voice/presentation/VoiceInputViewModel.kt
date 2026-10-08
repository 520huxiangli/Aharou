package com.aharou.feature.voice.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.voice.data.CloudSpeechClient
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.StreamingAsrEngine
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModelState
import com.aharou.feature.voice.domain.VoiceModels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** 语音输入的整体状态，驱动按钮外观与提示文案。 */
internal sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    /** 模型还没准备好：带下载/解压进度的提示（首次要下百来 MB，之后直接命中本地）。 */
    data class Preparing(val progress: Float, @StringRes val messageRes: Int) : VoiceInputState

    /** 正在收音；[liveText] 是边说边出的实时识别文本（云端模式恒为空）。 */
    data class Recording(val liveText: String = "") : VoiceInputState

    /** 松手/断句后正在收尾识别。 */
    data object Recognizing : VoiceInputState

    data class Error(@StringRes val messageRes: Int) : VoiceInputState
}

/**
 * 语音输入的流程编排。
 *
 * 两条路径：
 *  - **本地流式**（默认）：录音块实时喂给 zipformer，边说边出字；识别到静音端点即自动结束并回调结果。
 *  - **云端**（配了识别模型时）：录音期间只累积，结束时整段上传。云端接口普遍是批处理，没有流式。
 *
 * 云端失败自动回退本地，避免用户白说一遍。
 */
@HiltViewModel
internal class VoiceInputViewModel @Inject constructor(
    private val modelManager: VoiceModelManager,
    private val recorder: VoiceRecorder,
    private val asrEngine: StreamingAsrEngine,
    private val sttSettings: VoiceSttSettingsRepository,
    private val providerRepository: AIProviderRepository,
    private val cloudSpeech: CloudSpeechClient,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /** 结果回调；在端点自动结束或手动停止时触发。 */
    private var resultCallback: ((String) -> Unit)? = null

    private var session: StreamingAsrEngine.AsrSession? = null

    @Volatile
    private var finishing = false

    /**
     * 开始收音。模型未就绪时先下载（首次要下百来 MB），再开录音。
     *
     * @param onResult 识别出结果时回调（端点自动结束或用户手动停止都会触发）
     */
    fun start(onResult: (String) -> Unit) {
        if (_state.value is VoiceInputState.Recording) return
        resultCallback = onResult
        viewModelScope.launch {
            val cloud = cloudSttConfig()
            if (cloud == null && !modelManager.isReady(VoiceModels.ASR_ZH)) {
                _state.value = VoiceInputState.Preparing(0f, R.string.voice_model_downloading)
                val ok = runCatching {
                    modelManager.ensureModel(VoiceModels.ASR_ZH) { state ->
                        when (state) {
                            is VoiceModelState.Downloading -> _state.value =
                                VoiceInputState.Preparing(state.progress, R.string.voice_model_downloading)
                            VoiceModelState.Extracting -> _state.value =
                                VoiceInputState.Preparing(0f, R.string.voice_model_extracting)
                            else -> Unit
                        }
                    }
                }.isSuccess
                if (!ok) {
                    _state.value = VoiceInputState.Error(R.string.voice_model_failed)
                    return@launch
                }
            }

            if (cloud != null) {
                beginRecording(onChunk = null)
            } else {
                // 本地流式：先把 session 建好再开录音，否则前几块 PCM 会被丢掉
                val modelDir = modelManager.modelDir(VoiceModels.ASR_ZH)
                val s = asrEngine.startSession(modelDir, SAMPLE_RATE)
                if (s == null) {
                    _state.value = VoiceInputState.Error(R.string.voice_model_failed)
                    return@launch
                }
                session = s
                beginRecording(onChunk = ::onPcmChunk)
            }
        }
    }

    /** 录音线程回调：喂模型、更新实时文本、判端点。 */
    private fun onPcmChunk(samples: FloatArray) {
        val s = session ?: return
        val live = s.feed(samples)
        if (_state.value !is VoiceInputState.Recording || (live.isNotEmpty() && live != currentLiveText())) {
            _state.value = VoiceInputState.Recording(liveText = live)
        }
        if (s.isEndpoint()) {
            s.reset()
            finishAndDeliver()
        }
    }

    private fun currentLiveText(): String =
        (_state.value as? VoiceInputState.Recording)?.liveText.orEmpty()

    private fun beginRecording(onChunk: ((FloatArray) -> Unit)?) {
        when (recorder.start(onChunk)) {
            RecordStartResult.Started -> _state.value = VoiceInputState.Recording()
            RecordStartResult.NoPermission ->
                _state.value = VoiceInputState.Error(R.string.voice_permission_required)
            RecordStartResult.Failed ->
                _state.value = VoiceInputState.Error(R.string.voice_record_failed)
        }
    }

    /** 手动停止（松手/点停止）：结束录音并出结果。 */
    fun stop() {
        if (recorder.isRecording) finishAndDeliver()
    }

    /** 收尾并回调结果；幂等，可被端点和手动停止同时触发。 */
    private fun finishAndDeliver() {
        if (finishing) return
        finishing = true
        val callback = resultCallback
        val localSession = session
        session = null
        resultCallback = null

        // 云端模式：录音全程只累积（录音器内部已有整段缓冲），结束时一次性取走
        val samples = recorder.stop()
        if (localSession == null) {
            deliverCloud(samples, callback)
        } else {
            val text = localSession.finish().orEmpty().trim()
            localSession.release()
            finishing = false
            if (text.isBlank()) {
                _state.value = VoiceInputState.Error(R.string.voice_not_heard)
            } else {
                _state.value = VoiceInputState.Idle
                callback?.invoke(text)
            }
        }
    }

    private fun deliverCloud(samples: FloatArray, callback: ((String) -> Unit)?) {
        _state.value = VoiceInputState.Recognizing
        viewModelScope.launch {
            val text = recognizeOnCloud(samples)
            finishing = false
            if (text.isBlank()) {
                _state.value = VoiceInputState.Error(R.string.voice_not_heard)
            } else {
                _state.value = VoiceInputState.Idle
                callback?.invoke(text)
            }
        }
    }

    /** 云端识别；失败回退本地（录音已结束，用整段音频跑一次非流式收尾）。 */
    private suspend fun recognizeOnCloud(samples: FloatArray): String {
        val config = cloudSttConfig()
        if (config != null && samples.isNotEmpty()) {
            val cloud = runCatching {
                cloudSpeech.transcribe(
                    baseUrl = config.first,
                    apiKey = config.second,
                    model = config.third,
                    samples = samples,
                    sampleRate = SAMPLE_RATE,
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "云端识别失败，回退本地识别", e)
                ""
            }
            if (cloud.isNotBlank()) return cloud
        }
        return recognizeLocally(samples)
    }

    /** 本地兜底：整段 PCM 走流式引擎跑一遍（喂完即 finish）。 */
    private suspend fun recognizeLocally(samples: FloatArray): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty() || !modelManager.isReady(VoiceModels.ASR_ZH)) return@withContext ""
        val s = asrEngine.startSession(modelManager.modelDir(VoiceModels.ASR_ZH), SAMPLE_RATE)
            ?: return@withContext ""
        try {
            s.feed(samples)
            s.finish().trim()
        } finally {
            s.release()
        }
    }

    /** 取当前可用的云端识别配置（baseUrl / apiKey / model）；未配置返回 null。 */
    private suspend fun cloudSttConfig(): Triple<String, String, String>? {
        val providerId = sttSettings.getSttProviderId()
        val model = sttSettings.getSttModel()
        if (providerId.isBlank() || model.isBlank()) return null
        val provider = runCatching { providerRepository.getProviderById(providerId) }.getOrNull()
            ?: return null
        val key = provider.firstUsableApiKey
        if (key.isBlank()) return null
        return Triple(provider.baseUrl, key, model)
    }

    /** 取消当前操作（权限被拒、离开页面）。 */
    fun cancel() {
        resultCallback = null
        recorder.cancel()
        session?.release()
        session = null
        finishing = false
        _state.value = VoiceInputState.Idle
    }

    /** 清掉错误提示（提示展示完或用户开始新一轮输入时）。 */
    fun clearError() {
        if (_state.value is VoiceInputState.Error) _state.value = VoiceInputState.Idle
    }

    override fun onCleared() {
        recorder.cancel()
        session?.release()
        session = null
        FileLogger.i(TAG, "VoiceInputViewModel 释放")
        super.onCleared()
    }

    private companion object {
        const val TAG = "VoiceInputViewModel"
        const val SAMPLE_RATE = 16000
    }
}
