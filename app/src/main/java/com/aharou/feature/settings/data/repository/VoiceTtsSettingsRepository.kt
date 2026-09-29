package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private const val TAG = "VoiceTtsSettings"

private val Context.voiceTtsDataStore by preferencesDataStore(
    name = "voice_tts_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「语音合成模型」选择（providerId + model + voice 三项）。
 *
 * 比识别那边多一个 [voiceFlow]（音色名）：各家的音色标识格式不同——OpenAI 是 `alloy`
 * 这类固定枚举，CosyVoice2 是 `FunAudioLLM/CosyVoice2-0.5B:alex`，阿里百炼是 `longxiaochun`——
 * 没法用统一枚举表达，故按纯文本透传给服务端，留空则用服务端默认音色。
 */
@Singleton
class VoiceTtsSettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.voiceTtsDataStore, "voice_tts_provider_id", "voice_tts_model"
) {
    private val voiceKey = stringPreferencesKey("voice_tts_voice")
    private val autoReadKey = booleanPreferencesKey("voice_tts_auto_read")

    /** 当前持久化的音色名流；未设置时为空字符串（=用服务端默认音色）。 */
    val voiceFlow: Flow<String> = dataStore.data.map { it[voiceKey] ?: "" }

    /**
     * 自动朗读开关流：开了之后 AI 每条回复落地就自动念。
     *
     * 读失败就一直重试，**绝不让这条流结束**：流一旦结束，收它的 UI 就再也收不到值，
     * 开关会冻在最后一次显示上——表现就是「显示关、实际开、怎么点都关不掉」。
     */
    val autoReadAloudFlow: Flow<Boolean> = dataStore.data
        .map { it[autoReadKey] ?: false }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "自动朗读开关读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    /** 读一次自动朗读开关。 */
    suspend fun isAutoReadAloud(): Boolean = autoReadAloudFlow.first()

    /** 写自动朗读开关。 */
    suspend fun setAutoReadAloud(enabled: Boolean) {
        dataStore.edit { it[autoReadKey] = enabled }
    }

    /** 写入合成模型与音色（设空字符串即等同清除该项）。 */
    suspend fun setTtsModel(providerId: String, model: String, voice: String) {
        dataStore.edit {
            it[providerIdKey] = providerId
            it[modelKey] = model
            it[voiceKey] = voice
        }
    }

    /** 只改音色，保留当前模型选择（音色输入框失焦时调用）。 */
    suspend fun setVoice(voice: String) {
        dataStore.edit { it[voiceKey] = voice }
    }

    /** 清空配置——AI 回复不再朗读。 */
    suspend fun clear() {
        dataStore.edit {
            it.remove(providerIdKey)
            it.remove(modelKey)
            it.remove(voiceKey)
        }
    }

    /** 读取一次当前合成 providerId（冷读用）。 */
    suspend fun getTtsProviderId(): String = readProviderId()

    /** 读取一次当前合成 model（冷读用）。 */
    suspend fun getTtsModel(): String = readModel()

    /** 读取一次当前音色（冷读用）。 */
    suspend fun getVoice(): String = voiceFlow.first()
}
