package com.aharou.feature.voice.tts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import android.net.Uri
import com.aharou.feature.voice.data.OfflineAsrEngine
import com.aharou.feature.voice.data.RecordStartResult
import com.aharou.feature.voice.data.TtsPlayer
import com.aharou.feature.voice.data.VoiceRecorder
import com.aharou.feature.voice.domain.VoiceModelManager
import com.aharou.feature.voice.domain.VoiceModelState
import com.aharou.feature.voice.domain.VoiceModelStatus
import com.aharou.feature.voice.domain.VoiceModels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 配置里标记「本地克隆音色」的前缀：`voice.tts_voice` 填 `local:<id>` 就走本机合成。 */
internal const val LOCAL_VOICE_PREFIX = "local:"

/** 从配置值里取出克隆音色 id；不是本地音色时返回 null。 */
internal fun localVoiceIdOrNull(voice: String): String? =
    voice.takeIf { it.startsWith(LOCAL_VOICE_PREFIX) }
        ?.removePrefix(LOCAL_VOICE_PREFIX)
        ?.takeIf { it.isNotBlank() }

/** 当前在选哪一套音色：AI 回复的音色，还是用户自己那条语音条的音色。 */
enum class VoiceTarget { AI, USER }

/** 录制克隆音色时的界面状态。 */
internal sealed interface VoiceProfileState {
    data object Idle : VoiceProfileState
    data object Recording : VoiceProfileState
    data object Transcribing : VoiceProfileState

    /** 录完了，等用户确认转写文本与名字。 */
    data class Review(val text: String, val durationMs: Long) : VoiceProfileState
    data class Error(@StringRes val messageRes: Int) : VoiceProfileState
}

/**
 * 克隆音色的录制、转写与增删。
 *
 * 转写这一步不是可选的：ZipVoice 要求参考音频与参考文本逐字对应，让用户自己敲一遍既麻烦又几乎
 * 必然出错，所以录完直接跑本地 SenseVoice 出文本，再交给用户核对。文本错了音色就走样，
 * 这一点在界面上必须说清楚。
 */
@HiltViewModel
internal class VoiceProfileViewModel @Inject constructor(
    private val recorder: VoiceRecorder,
    private val asrEngine: OfflineAsrEngine,
    private val modelManager: VoiceModelManager,
    private val store: VoiceProfileStore,
    private val ttsEngine: ZipVoiceTtsEngine,
    private val player: TtsPlayer,
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val decoder: AudioFileDecoder,
    private val providerRepository: AIProviderRepository,
    private val cloudVoice: CloudVoiceClient,
) : ViewModel() {

    private val _state = MutableStateFlow<VoiceProfileState>(VoiceProfileState.Idle)
    val state: StateFlow<VoiceProfileState> = _state.asStateFlow()

    private val _profiles = MutableStateFlow<List<VoiceProfile>>(emptyList())
    val profiles: StateFlow<List<VoiceProfile>> = _profiles.asStateFlow()

    /** 服务端已有的自定义音色（只能用于云端合成，音色列表里展示供直接选用）。 */
    private val _cloudVoices = MutableStateFlow<List<CloudVoice>>(emptyList())
    val cloudVoices: StateFlow<List<CloudVoice>> = _cloudVoices.asStateFlow()

    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading: StateFlow<Boolean> = _cloudLoading.asStateFlow()

    /** 当前生效的 voice 配置值，用来高亮云端/本地哪个在选中。 */
    private val _currentVoice = MutableStateFlow("")
    val currentVoice: StateFlow<String> = _currentVoice.asStateFlow()

    /** 正在上传到云端的音色 id；null 表示没有进行中的上传。 */
    private val _uploadingId = MutableStateFlow<String?>(null)
    val uploadingId: StateFlow<String?> = _uploadingId.asStateFlow()

    /** 当前选中的克隆音色 id；选中云端音色或没选时为 null。 */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    /** 克隆模型自身的状态与下载进度：它不在设置页「下载全部」里，得单独管。 */
    private val _modelStatus = MutableStateFlow(modelManager.status(VoiceModels.TTS_CLONE))
    val modelStatus: StateFlow<VoiceModelStatus> = _modelStatus.asStateFlow()

    private val _modelState = MutableStateFlow<VoiceModelState>(VoiceModelState.Idle)
    val modelState: StateFlow<VoiceModelState> = _modelState.asStateFlow()

    /** 本次录音的样本与时长，等用户确认文本后才落盘。 */
    private var pendingSamples: FloatArray? = null
    private var pendingDurationMs = 0L
    private var stopJob: Job? = null

    /** 当前正在编辑的音色槽位；选择与高亮都跟着它走。 */
    private val _target = MutableStateFlow(VoiceTarget.AI)
    val target: StateFlow<VoiceTarget> = _target.asStateFlow()

    /** 设置页两行各自显示的当前值（与编辑槽位无关）。 */
    val aiVoiceValue: StateFlow<String> =
        ttsSettings.voiceFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val userVoiceValue: StateFlow<String> =
        ttsSettings.userVoiceFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        refresh()
        viewModelScope.launch {
            combine(ttsSettings.voiceFlow, ttsSettings.userVoiceFlow, _target) { ai, user, t ->
                if (t == VoiceTarget.USER) user else ai
            }.collect { applyCurrentVoice(it) }
        }
    }

    /** 切换到另一个音色槽位（AI / 用户）。 */
    fun setTarget(value: VoiceTarget) {
        _target.value = value
    }

    private fun applyCurrentVoice(value: String) {
        _currentVoice.value = value
        _selectedId.value = localVoiceIdOrNull(value)
    }

    private suspend fun writeVoice(value: String) {
        if (_target.value == VoiceTarget.USER) ttsSettings.setUserVoice(value) else ttsSettings.setVoice(value)
    }

    fun refresh() {
        _modelStatus.value = modelManager.status(VoiceModels.TTS_CLONE)
        viewModelScope.launch { _profiles.value = store.list() }
    }

    /** 下载（已下载则重新下载）克隆模型；进度走 [modelState]。 */
    fun downloadModel() {
        val running = _modelState.value
        if (running is VoiceModelState.Downloading || running is VoiceModelState.Extracting) return
        viewModelScope.launch {
            runCatching {
                modelManager.ensureModel(VoiceModels.TTS_CLONE, allowDownload = true) {
                    _modelState.value = it
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "下载克隆模型失败", e)
                _modelState.value = VoiceModelState.Failed(e.message ?: "")
            }
            _modelStatus.value = modelManager.status(VoiceModels.TTS_CLONE)
        }
    }

    fun startRecording() {
        if (_state.value is VoiceProfileState.Recording) return
        if (recorder.isRecording) {
            _state.value = VoiceProfileState.Error(R.string.voice_profile_busy)
            return
        }
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                runCatching { modelManager.ensureModel(VoiceModels.TTS_CLONE, allowDownload = false) }.isSuccess &&
                    runCatching { modelManager.ensureModel(VoiceModels.ASR_ZH_OFFLINE, allowDownload = false) }.isSuccess
            }
            if (!ready) {
                _state.value = VoiceProfileState.Error(R.string.voice_profile_need_model)
                return@launch
            }
            when (recorder.start()) {
                RecordStartResult.Started -> {
                    _state.value = VoiceProfileState.Recording
                    // 参考音频不是越长越好：几十秒的样本既拖慢合成，也更容易夹进环境噪音。
                    stopJob = viewModelScope.launch {
                        delay(MAX_RECORD_MS)
                        stopRecording()
                    }
                }
                RecordStartResult.NoPermission ->
                    _state.value = VoiceProfileState.Error(R.string.voice_input_no_permission)
                RecordStartResult.Failed ->
                    _state.value = VoiceProfileState.Error(R.string.voice_profile_record_failed)
            }
        }
    }

    fun stopRecording() {
        if (_state.value !is VoiceProfileState.Recording) return
        stopJob?.cancel()
        stopJob = null
        val samples = recorder.stop()
        if (samples.isEmpty()) {
            _state.value = VoiceProfileState.Error(R.string.voice_profile_empty)
            return
        }
        submitSamples(samples)
    }

    /**
     * 从音频文件导入音色：把用户选中的文件解成 16k 单声道，再走和录音一样的转写确认流程。
     *
     * 不要求非得在 App 里录，是为了让「微信里存的一句话」「已有录音」也能当参考音频。
     */
    fun importAudio(uri: Uri) {
        val current = _state.value
        if (current is VoiceProfileState.Recording || current is VoiceProfileState.Transcribing) return
        _state.value = VoiceProfileState.Transcribing
        viewModelScope.launch {
            val samples = runCatching { decoder.decode(uri, SAMPLE_RATE) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "解码音频文件失败", e)
                _state.value = VoiceProfileState.Error(R.string.voice_profile_decode_failed)
                return@launch
            }
            if (samples.isEmpty()) {
                _state.value = VoiceProfileState.Error(R.string.voice_profile_empty)
                return@launch
            }
            submitSamples(samples)
        }
    }

    /** 拿到一段参考音频后的公共尾巴：存起来、转写、进确认页。 */
    private fun submitSamples(samples: FloatArray) {
        pendingSamples = samples
        pendingDurationMs = samples.size * 1000L / SAMPLE_RATE
        _state.value = VoiceProfileState.Transcribing
        viewModelScope.launch {
            val text = withContext(Dispatchers.Default) {
                val dir = runCatching {
                    modelManager.ensureModel(VoiceModels.ASR_ZH_OFFLINE, allowDownload = false)
                }.getOrNull() ?: return@withContext null
                runCatching { asrEngine.recognize(dir, samples, SAMPLE_RATE) }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                pendingSamples = null
                _state.value = VoiceProfileState.Error(R.string.voice_profile_transcribe_failed)
                return@launch
            }
            _state.value = VoiceProfileState.Review(text = text.trim(), durationMs = pendingDurationMs)
        }
    }

    /** 取消这次录制，不落盘。 */
    fun discard() {
        stopJob?.cancel()
        stopJob = null
        if (recorder.isRecording) recorder.cancel()
        pendingSamples = null
        _state.value = VoiceProfileState.Idle
    }

    /**
     * 保存音色。[text] 是用户在确认页上改过的参考文本，逐字对应音频这件事由用户负责。
     */
    fun save(name: String, text: String) {
        val samples = pendingSamples ?: return
        if (text.isBlank()) {
            _state.value = VoiceProfileState.Error(R.string.voice_profile_text_required)
            return
        }
        viewModelScope.launch {
            val profile = runCatching {
                store.save(name = name, samples = samples, sampleRate = SAMPLE_RATE, referenceText = text)
            }.getOrElse {
                FileLogger.e(TAG, "保存音色失败", it)
                _state.value = VoiceProfileState.Error(R.string.voice_profile_save_failed)
                return@launch
            }
            pendingSamples = null
            _state.value = VoiceProfileState.Idle
            // 转写用的 SenseVoice 占着两百多 MB，而紧接着就是加载克隆模型，先交回去。
            asrEngine.release()
            refresh()
            select(profile.id)
        }
    }

    fun select(id: String) {
        viewModelScope.launch { writeVoice("$LOCAL_VOICE_PREFIX$id") }
    }

    /** 取消音色选择，朗读回落到云端合成或系统引擎。 */
    fun clearSelection() {
        viewModelScope.launch { writeVoice("") }
    }

    /**
     * 把这个音色的参考音频传到云端，换一个可复用的音色 ID 存进档案。
     *
     * 用的是「语音合成模型」里已配的 provider：云端克隆和云端合成必须是同一家，
     * 否则拿到的 uri 在另一家的合成接口上不认。
     */
    fun uploadToCloud(id: String) {
        if (_uploadingId.value != null) return
        viewModelScope.launch {
            _uploadingId.value = id
            try {
                val loaded = store.load(id) ?: return@launch
                val providerId = ttsSettings.getTtsProviderId()
                val model = ttsSettings.getTtsModel()
                val provider = if (providerId.isNotBlank()) {
                    runCatching { providerRepository.getProviderById(providerId) }.getOrNull()
                } else {
                    null
                }
                if (provider == null || model.isBlank() || provider.firstUsableApiKey.isBlank()) {
                    _state.value = VoiceProfileState.Error(R.string.voice_profile_cloud_no_provider)
                    return@launch
                }
                val uri = cloudVoice.uploadVoice(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.firstUsableApiKey,
                    model = model,
                    customName = loaded.profile.id,
                    text = loaded.profile.referenceText,
                    samples = loaded.samples,
                    sampleRate = loaded.profile.sampleRate,
                )
                store.updateCloudVoiceUri(id, uri)
                refresh()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "上传云端音色失败", e)
                _state.value = VoiceProfileState.Error(R.string.voice_profile_cloud_failed)
            } finally {
                _uploadingId.value = null
            }
        }
    }

    /** 拉取服务端已建好的自定义音色；拿不到就保持原列表。 */
    fun refreshCloudVoices() {
        if (_cloudLoading.value) return
        viewModelScope.launch {
            _cloudLoading.value = true
            try {
                val providerId = ttsSettings.getTtsProviderId()
                val provider = if (providerId.isNotBlank()) {
                    runCatching { providerRepository.getProviderById(providerId) }.getOrNull()
                } else {
                    null
                }
                val apiKey = provider?.firstUsableApiKey.orEmpty()
                if (provider == null || apiKey.isBlank()) {
                    _state.value = VoiceProfileState.Error(R.string.voice_profile_cloud_no_provider)
                    return@launch
                }
                _cloudVoices.value = cloudVoice.listVoices(provider.baseUrl, apiKey)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "拉取云端音色失败", e)
                _state.value = VoiceProfileState.Error(R.string.voice_profile_cloud_list_failed)
            } finally {
                _cloudLoading.value = false
            }
        }
    }

    /** 直接用一个云端音色：把它的 uri 写进音色配置，合成时原样传给服务端。 */
    fun selectCloudVoice(voice: CloudVoice) {
        viewModelScope.launch { writeVoice(voice.uri) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (_selectedId.value == id) clearSelection()
            refresh()
        }
    }

    /** 试听：用该音色念一句固定的话，合成完直接播。 */
    fun preview(id: String) {
        if (_playingId.value != null) return
        viewModelScope.launch {
            _playingId.value = id
            try {
                val loaded = store.load(id) ?: return@launch
                val audio = ttsEngine.synthesize(PREVIEW_TEXT, loaded)
                player.play(audio) {
                    viewModelScope.launch { _playingId.value = null }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "试听失败", e)
                _state.value = VoiceProfileState.Error(R.string.voice_profile_preview_failed)
            } finally {
                _playingId.value = null
            }
        }
    }

    fun clearError() {
        if (_state.value is VoiceProfileState.Error) _state.value = VoiceProfileState.Idle
        player.stop()
    }

    override fun onCleared() {
        super.onCleared()
        if (recorder.isRecording) recorder.cancel()
        player.stop()
    }

    private companion object {
        const val TAG = "VoiceProfileViewModel"
        const val SAMPLE_RATE = 16000
        const val MAX_RECORD_MS = 20_000L
        const val PREVIEW_TEXT = "你好，我是小染，这是用你的声音说的话。"
    }
}
