package com.aharou.feature.voice.data

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import com.aharou.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话提示音：用系统 [ToneGenerator] 发短促单音，不引入任何音频资产。
 *
 * 此前整条通话链路一点声都没有：接通没反馈、挂了没反馈、喊了唤醒词也不知道听没听见。
 *
 * 全程不申请音频焦点，所以不会把正在播的 TTS 打断；同一音效设最小间隔，
 * 避免状态来回切换时连着响。
 */
@Singleton
internal class VoiceSoundEffects @Inject constructor() {

    enum class Effect(val tone: Int, val durationMs: Int) {
        /** 唤醒词命中。 */
        WAKE(ToneGenerator.TONE_PROP_BEEP, 120),

        /** 通话接通（开始聆听）。 */
        CONNECT(ToneGenerator.TONE_PROP_ACK, 150),

        /** 挂断。 */
        HANGUP(ToneGenerator.TONE_PROP_BEEP2, 180),
    }

    @Volatile
    private var toneGenerator: ToneGenerator? = null

    private val lastPlayedAt = HashMap<Int, Long>()

    fun play(effect: Effect) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastPlayedAt) {
            val last = lastPlayedAt[effect.tone] ?: 0L
            if (now - last < MIN_INTERVAL_MS) return
            lastPlayedAt[effect.tone] = now
        }
        val generator = ensureToneGenerator() ?: return
        runCatching { generator.startTone(effect.tone, effect.durationMs) }
            .onFailure { FileLogger.w(TAG, "播放提示音失败", it) }
    }

    private fun ensureToneGenerator(): ToneGenerator? {
        toneGenerator?.let { return it }
        return synchronized(this) {
            toneGenerator ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME_PERCENT) }
                .onFailure { FileLogger.w(TAG, "创建提示音发生器失败", it) }
                .getOrNull()
                ?.also { toneGenerator = it }
        }
    }

    private companion object {
        const val TAG = "VoiceSoundEffects"

        /** 同一音效的最小间隔，防止状态切换时连发。 */
        const val MIN_INTERVAL_MS = 600L

        const val VOLUME_PERCENT = 70
    }
}
