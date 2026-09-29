package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音合成与朗读偏好 → 配置通道。
 *
 * 语音识别（STT）的模型选择在 [com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository]，
 * 字段与文本输入框绑定，暂未接入。
 *
 * 三个 TTS 字段在仓库里是同一个写入方法（`setTtsModel(providerId, model, voice)`），
 * 所以每个字段的 setter 都要把另外两个的当前值带上，避免改一个把其余清空。
 */
@Singleton
class ConfigVoiceFields @Inject constructor(
    private val ttsSettings: VoiceTtsSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreStrField(
                path = "voice.tts_provider",
                displayName = "语音合成供应商",
                description = "朗读与语音通话使用的 TTS 供应商 id（取值见 providers 集合）。留空则回退到手机自带的语音引擎。",
                flow = ttsSettings.providerIdFlow,
                setter = { id ->
                    ttsSettings.setTtsModel(id, ttsSettings.getTtsModel(), ttsSettings.getVoice())
                },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.tts_model",
                displayName = "语音合成模型",
                description = "TTS 模型名。需与 voice.tts_provider 配套设置，只设其一不会生效。",
                flow = ttsSettings.modelFlow,
                setter = { model ->
                    ttsSettings.setTtsModel(ttsSettings.getTtsProviderId(), model, ttsSettings.getVoice())
                },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.tts_voice",
                displayName = "语音合成音色",
                description = "音色名，留空用服务端默认音色。",
                flow = ttsSettings.voiceFlow,
                setter = { ttsSettings.setVoice(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "voice.auto_read",
                displayName = "自动朗读",
                description = "开启后 AI 每条回复自动念出来，边生成边念。关闭时手动点气泡上的喇叭按钮仍可朗读。",
                flow = ttsSettings.autoReadAloudFlow,
                setter = { ttsSettings.setAutoReadAloud(it) },
            ),
        )
    }
}
