package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.settings.data.repository.AssistantOverlaySettingsRepository
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceWakeSettingsRepository
import com.aharou.feature.voice.assistant.PowerKeyWatcherService
import com.aharou.feature.voice.call.VoiceCallService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 语音合成与朗读偏好 → 配置通道。
 *
 * 语音识别（STT）的 provider/model 在 [com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository]，
 * 两者在仓库里共用一个写入方法（`setSttModel(providerId, model)`），所以每个字段的 setter 都要把
 * 另一个的当前值带上，避免改一个把另一个清空。
 *
 * 三个 TTS 字段在仓库里也是同一个写入方法（`setTtsModel(providerId, model, voice)`），
 * 同理每个 setter 都要把另外两个的当前值带上。
 *
 * 两处开关除了写值还要**启停对应服务**（`voice.wake_enabled` → VoiceCallService，
 * `voice.assistant_power_key` → PowerKeyWatcherService）。原来只有设置页的点击路径会启停，
 * config 通道只写开关，结果关掉后麦克风仍常驻收音、通知栏还挂着「唤醒已开启」。
 */
@Singleton
class ConfigVoiceFields @Inject constructor(
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val sttSettings: VoiceSttSettingsRepository,
    private val wakeSettings: VoiceWakeSettingsRepository,
    private val overlaySettings: AssistantOverlaySettingsRepository,
    @param:ApplicationContext private val context: Context,
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
                description = "音色名，留空用服务端默认音色；填 local:<id> 则用本机克隆的音色（id 见设置里「我的音色」）。",
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

        registry.register(
            DataStoreStrField(
                path = "voice.stt_provider",
                displayName = "语音识别供应商",
                description = "语音输入使用的识别供应商 id（取值见 providers 集合）。留空则回退到 APK 内置的离线模型。",
                flow = sttSettings.providerIdFlow,
                setter = { id -> sttSettings.setSttModel(id, sttSettings.getSttModel()) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "voice.stt_model",
                displayName = "语音识别模型",
                description = "识别模型名。需与 voice.stt_provider 配套设置，只设其一不会生效；两者都空时用内置离线模型。",
                flow = sttSettings.modelFlow,
                setter = { model -> sttSettings.setSttModel(sttSettings.getSttProviderId(), model) },
            ),
        )

        registry.register(
            ClosureField(
                path = "voice.wake_enabled",
                displayName = "语音唤醒",
                description = "常驻监听麦克风、用唤醒词唤起语音输入。默认关闭。",
                valueSchema = ConfigSchema.Bool,
                // 写入要等 DataStore 落盘，而 writer 是同步签名，与 DataStoreBoolField 一样用 runBlocking 桥接。
                reader = { ConfigValue.Bool(runBlocking { wakeSettings.enabledFlow.first() }) },
                writer = { v ->
                    val enabled = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    runBlocking { wakeSettings.setEnabled(enabled) }
                    if (enabled) VoiceCallService.start(context, wakeMode = true) else VoiceCallService.stop(context)
                },
            ),
        )
        registry.register(
            DataStoreBoolField(
                path = "voice.assistant_screen_text",
                displayName = "助手浮层附带屏幕文本",
                description = "系统助手浮层唤起时，把当前屏幕上的可见文字当作上下文发给模型。关掉后仍能对话，只是模型不知道你正看着什么。",
                flow = overlaySettings.screenTextFlow,
                setter = { overlaySettings.setScreenText(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "voice.assistant_screenshot",
                displayName = "助手浮层附带截图",
                description = "把当前屏幕截图当作图片附件发给模型，能理解画面内容。比文本更耗流量，且画面内容会整个离开设备，默认关闭。",
                flow = overlaySettings.screenshotFlow,
                setter = { overlaySettings.setScreenshot(it) },
            ),
        )
        registry.register(
            ClosureField(
                path = "voice.assistant_power_key",
                displayName = "长按电源键唤起助手",
                description = "靠 Shizuku 监听电源键，按住约 0.8 秒弹出助手浮层；短按不受影响。需保持 Shizuku 就绪。",
                valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(runBlocking { overlaySettings.powerKeyFlow.first() }) },
                writer = { v ->
                    val enabled = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    runBlocking { overlaySettings.setPowerKey(enabled) }
                    if (enabled) PowerKeyWatcherService.start(context) else PowerKeyWatcherService.stop(context)
                },
            ),
        )
    }
}
